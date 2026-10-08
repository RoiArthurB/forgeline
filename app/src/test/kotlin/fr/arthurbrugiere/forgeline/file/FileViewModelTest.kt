package fr.arthurbrugiere.forgeline.file

import com.google.common.truth.Truth.assertThat
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.testing.FakeRepoRepository
import fr.arthurbrugiere.forgeline.core.testing.MainDispatcherRule
import fr.arthurbrugiere.forgeline.repo.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val id = RepoId("octo", "repo")
    private val repos = FakeRepoRepository()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.testDispatcher) { block() }

    private fun viewModel(path: String) = FileViewModel(FileTarget(id, path, "main"), repos)

    @Test
    fun loads_text_files() = test {
        repos.files["src/Main.kt"] = ForgeResult.Success("fun main() {}\n")

        val viewModel = viewModel("src/Main.kt")
        advanceUntilIdle()

        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Loaded(FileContent.Text("fun main() {}\n")))
        assertThat(repos.calls).containsExactly("file:octo/repo:src/Main.kt@main")
    }

    @Test
    fun binary_files_are_not_shown_as_text() = test {
        repos.files["font.woff2"] = ForgeResult.Success("wOF2\u0000\u0001garbage")

        val viewModel = viewModel("font.woff2")
        advanceUntilIdle()

        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Loaded(FileContent.Binary))
    }

    @Test
    fun failures_are_reported_and_can_be_retried() = test {
        repos.files["big.json"] = ForgeResult.Failure(ForgeError.Http(413, "File too large to preview"))
        val viewModel = viewModel("big.json")
        advanceUntilIdle()
        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Failed(ForgeError.Http(413, "File too large to preview")))

        repos.files["big.json"] = ForgeResult.Success("{}")
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Loaded(FileContent.Text("{}")))
    }

    @Test
    fun exposes_where_the_file_lives_on_the_forge() = test {
        val viewModel = viewModel("docs/GUIDE.md")

        assertThat(viewModel.state.value.webUrl).isEqualTo("https://blob.example/octo/repo/main/docs/GUIDE.md")
        assertThat(viewModel.state.value.readmeContext.directory).isEqualTo("docs/")
        assertThat(viewModel.state.value.readmeContext.rawBaseUrl).isEqualTo("https://raw.example/octo/repo/main/")
    }

    @Test
    fun a_picture_is_shown_from_where_its_bytes_are_served_without_being_read_as_text() = test {
        // Regression: a picture was read as text, found to be binary, and only said to be so.
        val viewModel = viewModel("docs/logo.PNG")
        advanceUntilIdle()

        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Loaded(FileContent.Picture("https://raw.example/octo/repo/main/docs/logo.PNG")))
        assertThat(repos.calls).isEmpty()
    }

    @Test
    fun a_picture_s_address_takes_the_spaces_and_signs_of_its_path() = test {
        val viewModel = viewModel("screen shots/vue d'ensemble #1.jpg")
        advanceUntilIdle()

        assertThat(viewModel.state.value.content)
            .isEqualTo(Loadable.Loaded(FileContent.Picture("https://raw.example/octo/repo/main/screen%20shots/vue%20d%27ensemble%20%231.jpg")))
    }

    @Test
    fun pictures_are_told_by_their_name() {
        assertThat(listOf("a.png", "b.JPG", "c.jpeg", "d.gif", "e.webp", "f.svg", "g.ico", "h.avif", "i.bmp").all(::isPicture)).isTrue()
        assertThat(listOf("png", "a.png.txt", "README.md", "Makefile", "x.pdf").none(::isPicture)).isTrue()
    }
}
