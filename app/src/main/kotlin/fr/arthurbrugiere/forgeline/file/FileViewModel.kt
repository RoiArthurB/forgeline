package fr.arthurbrugiere.forgeline.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.arthurbrugiere.forgeline.core.data.repo.RepoRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.markdown.CodeHighlighter
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [id] must be the canonical repo name (see RepoRepository). */
data class FileTarget(val id: RepoId, val path: String, val ref: String) {
    val name: String get() = path.substringAfterLast('/')
}

sealed interface FileContent {
    data class Text(val text: String) : FileContent

    /** A picture, at the address its bytes are served from. */
    data class Picture(val url: String) : FileContent

    data object Binary : FileContent
}

private val pictureExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif", "ico")

/** Whether the file named [name] is a picture the app can show. */
fun isPicture(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in pictureExtensions

data class FileUiState(
    val target: FileTarget,
    val content: Loadable<FileContent> = Loadable.Loading,
    val webUrl: String,
    /** Resolves relative paths when the file is Markdown. */
    val readmeContext: ReadmeContext,
)

@HiltViewModel(assistedFactory = FileViewModel.Factory::class)
class FileViewModel @AssistedInject constructor(
    @Assisted private val target: FileTarget,
    private val repos: RepoRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(target: FileTarget): FileViewModel
    }

    private val _state = MutableStateFlow(
        FileUiState(
            target = target,
            webUrl = repos.blobBaseUrl(target.id, target.ref) + target.path,
            readmeContext = ReadmeContext(
                rawBaseUrl = repos.rawBaseUrl(target.id, target.ref),
                blobBaseUrl = repos.blobBaseUrl(target.id, target.ref),
                directory = target.path.substringBeforeLast('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "$it/" },
            ),
        ),
    )
    val state: StateFlow<FileUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        // Regression: a picture was read as text, found to be binary, and only said to be so.
        if (isPicture(target.name)) {
            _state.update { it.copy(content = Loadable.Loaded(FileContent.Picture(it.readmeContext.rawBaseUrl + encodedPath(target.path)))) }
            return
        }
        _state.update { it.copy(content = Loadable.Loading) }
        viewModelScope.launch {
            val content = when (val result = repos.fileText(target.id, target.path, target.ref)) {
                is ForgeResult.Failure -> Loadable.Failed(result.error)
                is ForgeResult.Success -> Loadable.Loaded(
                    if (CodeHighlighter.isBinary(result.value)) FileContent.Binary else FileContent.Text(result.value),
                )
            }
            _state.update { it.copy(content = content) }
        }
    }
}

/** A path as an address takes it: each folder and the name encoded, the slashes between them kept. */
private fun encodedPath(path: String): String =
    path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
