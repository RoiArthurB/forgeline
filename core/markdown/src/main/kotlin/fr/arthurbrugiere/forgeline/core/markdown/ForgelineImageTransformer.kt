package fr.arthurbrugiere.forgeline.core.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig

/**
 * Coil pictures, with a line-high placeholder until a picture's size is known. The renderer's own holds a 200dp
 * square for each: a row of badges still loading, or that never load, pushed the text a screen and more down.
 */
internal object ForgelineImageTransformer : ImageTransformer {
    /** The height of a badge, which is what most pictures set in a line of text are. */
    const val UNKNOWN_SIZE_DP = 20f

    @Composable
    override fun transform(link: String): ImageData? = Coil3ImageTransformerImpl.transform(link)

    @Composable
    override fun intrinsicSize(painter: Painter): Size = Coil3ImageTransformerImpl.intrinsicSize(painter)

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig =
        if (imageSize.isUnspecified) {
            PlaceholderConfig(Size(UNKNOWN_SIZE_DP, UNKNOWN_SIZE_DP))
        } else {
            super.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
        }
}
