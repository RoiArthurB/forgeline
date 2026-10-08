package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import kotlin.math.roundToInt

const val PICTURE_TAG = "picture"

/** How close a double tap brings a picture, and how close two fingers may. */
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 6f

/**
 * A picture filling the room it is given, that two fingers bring closer and move around, and a double tap brings
 * closer where it lands, or back whole. One that can't be loaded says so and offers [onOpenInBrowser].
 */
@Composable
fun ZoomablePicture(url: String, description: String?, modifier: Modifier = Modifier, onOpenInBrowser: (() -> Unit)? = null, browserLabel: String? = null) {
    val colors = Soft.colors
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var moved by remember(url) { mutableStateOf(Offset.Zero) }
    var room by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    // The picture never leaves the room: it moves as far as what its zoom put out of sight.
    fun held(to: Offset, at: Float): Offset {
        val x = room.width * (at - 1f) / 2f
        val y = room.height * (at - 1f) / 2f
        return Offset(to.x.coerceIn(-x, x), to.y.coerceIn(-y, y))
    }

    if (loading is AsyncImagePainter.State.Error) {
        SoftNotice(
            stringResource(R.string.picture_error_title),
            stringResource(R.string.picture_error_body),
            action = browserLabel.takeIf { onOpenInBrowser != null },
            onAction = { onOpenInBrowser?.invoke() },
        )
        return
    }
    val zoomed = stringResource(R.string.picture_zoom, (zoom * 100).roundToInt())
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { room = it }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        if (zoom > 1f) {
                            zoom = 1f
                            moved = Offset.Zero
                        } else {
                            zoom = DOUBLE_TAP_ZOOM
                            // What was under the finger stays under it.
                            moved = held((Offset(room.width / 2f, room.height / 2f) - at) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(url) {
                detectTransformGestures { _, pan, change, _ ->
                    zoom = (zoom * change).coerceIn(1f, MAX_ZOOM)
                    moved = held(moved + pan, zoom)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            // Whole, not sized to the screen: zooming in then shows what the picture holds.
            model = ImageRequest.Builder(LocalContext.current).data(url).size(Size.ORIGINAL).build(),
            contentDescription = description ?: stringResource(R.string.picture),
            contentScale = ContentScale.Fit,
            onState = { loading = it },
            modifier = Modifier
                .fillMaxSize()
                .testTag(PICTURE_TAG)
                .semantics { stateDescription = zoomed }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = moved.x
                    translationY = moved.y
                },
        )
        if (loading is AsyncImagePainter.State.Loading || loading is AsyncImagePainter.State.Empty) {
            CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
        }
    }
}
