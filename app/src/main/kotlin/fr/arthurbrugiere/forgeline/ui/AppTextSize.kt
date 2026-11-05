package fr.arthurbrugiere.forgeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.model.TextSize

/**
 * The app's text at [size] next to the phone's own: every sp is multiplied before the phone turns it into pixels, so
 * the phone's curve for large text (which grows big text less than small) still applies on top.
 */
@Composable
fun AppTextSize(size: TextSize, content: @Composable () -> Unit) {
    if (size == TextSize.PHONE) return content()
    val phone = LocalDensity.current
    val scaled = remember(phone, size) { ScaledText(phone, size.scale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

private class ScaledText(private val phone: Density, private val scale: Float) : Density {
    override val density: Float get() = phone.density
    override val fontScale: Float get() = phone.fontScale * scale

    override fun TextUnit.toDp(): Dp = with(phone) { (value * scale).sp.toDp() }

    override fun Dp.toSp(): TextUnit = with(phone) { (toSp().value / scale).sp }

    override fun equals(other: Any?): Boolean = other is ScaledText && other.phone == phone && other.scale == scale

    override fun hashCode(): Int = 31 * phone.hashCode() + scale.hashCode()
}
