package dev.vantara.replicatorops.ui.lcars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * How much to scale the whole interface for this window. Based on the window's
 * shorter side, so turning a phone to landscape doesn't blow everything up:
 * small phones (320–360 dp) shrink a little, normal phones stay at 1×,
 * 7" tablets grow to about 1.3× and 10"+ tablets to 1.5×.
 */
fun screenScale(widthDp: Int, heightDp: Int): Float {
    val shortSide = minOf(widthDp, heightDp).toFloat()
    return when {
        shortSide <= 0f -> 1f
        shortSide < 380f -> (shortSide / 380f).coerceAtLeast(0.85f)
        shortSide <= 420f -> 1f
        else -> (1f + (shortSide - 420f) / 600f).coerceAtMost(1.5f)
    }
}

/**
 * Scales every dp and sp inside [content] (LCARS frame, text, buttons, dialogs),
 * so the same layout fits small phones, tablets, foldables and resizable windows.
 * The user's own font-size setting still applies on top.
 */
@Composable
fun ScaledToScreen(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val scale = screenScale(config.screenWidthDp, config.screenHeightDp)
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * scale, base.fontScale),
        content = content,
    )
}
