package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import androidx.compose.material3.MaterialTheme
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.m3.markdownColor
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import androidx.compose.runtime.getValue

/** Parses Markdown into a renderable state. Call off the main thread for large documents. */
fun parseForgeMarkdown(markdown: String): State = parseMarkdown(markdown)

/**
 * Renders parsed Markdown with the app theme, Coil images and highlighted code. Link taps go to
 * [onLinkClick] so the app decides what opens in-app and what goes to a Custom Tab.
 */
@Composable
fun ForgelineMarkdown(
    state: State,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = remember(onLinkClick) {
        object : UriHandler {
            override fun openUri(uri: String) = onLinkClick(uri)
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        val type = MaterialTheme.typography
        val soft = Soft.colors
        Markdown(
            state = state,
            modifier = modifier,
            // Code sits on the soft surface; links are ember, in the text face's medium weight (it has no bold).
            colors = markdownColor(
                text = soft.ink,
                codeBackground = soft.surface,
                inlineCodeBackground = soft.surface,
                dividerColor = soft.track.copy(alpha = soft.track.alpha * 2),
                tableBackground = soft.surface.copy(alpha = 0.5f),
            ),
            // Material headline/title scale: the default H1 uses display sizes, too big on a phone.
            typography = markdownTypography(
                h1 = type.headlineMedium,
                h2 = type.headlineSmall,
                h3 = type.titleLarge,
                h4 = type.titleMedium,
                h5 = type.titleSmall,
                h6 = type.labelLarge,
                code = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                textLink = TextLinkStyles(
                    style = SpanStyle(color = soft.accent, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
                ),
            ),
            imageTransformer = ForgelineImageTransformer,
            components = markdownComponents(
                codeBlock = highlightedCodeBlock,
                codeFence = highlightedCodeFence,
            ),
        )
    }
}

/** What a parse depends on: the text, where it lives (its relative links and images resolve there), and the theme. */
internal data class MarkdownCacheKey(val markdown: String, val context: ReadmeContext, val darkTheme: Boolean)

/**
 * Parsed Markdown kept in memory, so a comment that scrolls back into view or a README gone back to shows at once
 * instead of being parsed again behind a placeholder. The ones read longest ago give way first, once there are more
 * than [MAX_ENTRIES] or their text adds up to more than [MAX_CHARS]: a few long READMEs weigh what many comments do.
 */
internal object MarkdownAstCache {
    const val MAX_ENTRIES = 128

    /** About 4 MB of text, which the parsed trees multiply a few times. */
    const val MAX_CHARS = 2_000_000

    private val lock = Any()
    private val lru = LinkedHashMap<MarkdownCacheKey, State>(MAX_ENTRIES, 0.75f, true)
    private var chars = 0

    fun get(key: MarkdownCacheKey): State? = synchronized(lock) { lru[key] }

    fun put(key: MarkdownCacheKey, state: State) = synchronized(lock) {
        // Larger than everything the cache may hold: keeping it would push all the rest out for one document.
        if (key.markdown.length > MAX_CHARS) return@synchronized
        if (lru.put(key, state) == null) chars += key.markdown.length
        val eldest = lru.entries.iterator()
        while ((lru.size > MAX_ENTRIES || chars > MAX_CHARS) && eldest.hasNext()) {
            chars -= eldest.next().key.markdown.length
            eldest.remove()
        }
    }

    fun clear() = synchronized(lock) {
        lru.clear()
        chars = 0
    }
}

/**
 * Preprocesses and parses a README off the main thread; null until ready. What was parsed before is returned at
 * once (see [MarkdownAstCache]). [darkTheme] picks `<picture>` variants, so it should follow the app theme rather
 * than the system one.
 */
@Composable
fun rememberReadmeState(markdown: String, context: ReadmeContext, darkTheme: Boolean): State? {
    val key = remember(markdown, context, darkTheme) { MarkdownCacheKey(markdown, context, darkTheme) }
    // Looked up whenever what is shown changes, not only when the screen first appears: a state produced below keeps
    // its last value across such a change, which showed the previous document in place of one already parsed.
    val cached = remember(key) { MarkdownAstCache.get(key) }
    if (cached != null) return cached
    val state by produceState<State?>(initialValue = null, key) {
        val parsed = withContext(Dispatchers.Default) {
            parseForgeMarkdown(ReadmePreprocessor.prepare(markdown, context, darkTheme))
        }
        MarkdownAstCache.put(key, parsed)
        value = parsed
    }
    return state
}
