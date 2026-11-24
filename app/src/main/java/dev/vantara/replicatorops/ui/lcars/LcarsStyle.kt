package dev.vantara.replicatorops.ui.lcars

import dev.vantara.replicatorops.R
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One LCARS "era". Colours are original picks in the spirit of each era,
 * not copied from any official asset.
 */
data class LcarsStyle(
    val id: String,
    val name: String,
    val frame: Color,
    val frameAlt: Color,
    val nav: List<Color>,
    /** Sidebar button colours, one per menu item (nav colours plus two more). */
    val sidebar: List<Color>,
    val accent: Color,
    val accent2: Color,
    val alert: Color,
    val text: Color,
    val dim: Color,
    /** Readable colour for hints and secondary text (dim is for backgrounds only). */
    val hint: Color,
    val barHeight: Dp,
    val bottomBarHeight: Dp,
    val sidebarWidth: Dp,
    val outerRadius: Dp,
    val innerRadius: Dp,
    val gap: Dp,
    /** Letter spacing in em for labels. */
    val tracking: Float,
)

/** TNG / Voyager look: warm oranges, peach and lavender, chunky bars. */
val Century24 = LcarsStyle(
    id = "24",
    name = "24th Century",
    frame = Color(0xFFFF9900),
    frameAlt = Color(0xFFCC99CC),
    nav = listOf(Color(0xFFFFCC99), Color(0xFF9999FF), Color(0xFFCC6699), Color(0xFFFF9966)),
    sidebar = listOf(
        Color(0xFFFFCC99), Color(0xFF9999FF), Color(0xFFCC6699),
        Color(0xFFFF9966), Color(0xFFFFCC66), Color(0xFF9999CC), Color(0xFFFFAA90), Color(0xFFCC9966),
    ),
    accent = Color(0xFFFFCC66),
    accent2 = Color(0xFF99CCFF),
    alert = Color(0xFFDD4444),
    text = Color(0xFFFF9900),
    dim = Color(0xFF4A3320),
    hint = Color(0xFFD4C3A8),
    barHeight = 26.dp,
    bottomBarHeight = 18.dp,
    sidebarWidth = 96.dp,
    outerRadius = 44.dp,
    innerRadius = 20.dp,
    gap = 4.dp,
    tracking = 0.04f,
)

/** Picard-era look: deep blues with amber and red, thinner lines. */
val Century25 = LcarsStyle(
    id = "25",
    name = "25th Century",
    frame = Color(0xFF3D6DAD),
    frameAlt = Color(0xFF223F69),
    nav = listOf(Color(0xFF5B8FD6), Color(0xFFE8A04A), Color(0xFF8FB4E8), Color(0xFFC5523F)),
    sidebar = listOf(
        Color(0xFF5B8FD6), Color(0xFFE8A04A), Color(0xFF8FB4E8),
        Color(0xFFC5523F), Color(0xFFE8C07A), Color(0xFF4F7FC0), Color(0xFF6FA8C8), Color(0xFFB07A3A),
    ),
    accent = Color(0xFFE8A04A),
    accent2 = Color(0xFF7FD0E8),
    alert = Color(0xFFD6402F),
    text = Color(0xFF8FB4E8),
    dim = Color(0xFF1A2840),
    hint = Color(0xFFA9BCD8),
    barHeight = 14.dp,
    bottomBarHeight = 10.dp,
    sidebarWidth = 84.dp,
    outerRadius = 30.dp,
    innerRadius = 12.dp,
    gap = 3.dp,
    tracking = 0.08f,
)

fun styleFor(id: String): LcarsStyle = if (id == "25") Century25 else Century24

/**
 * Antonio (SIL Open Font License, see assets/licenses/Antonio-OFL.txt): a tall,
 * condensed face close to the LCARS look. One variable font file covers every weight.
 */
@OptIn(ExperimentalTextApi::class)
val LcarsFont: FontFamily = FontFamily(
    Font(R.font.antonio, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.antonio, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.antonio, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val LocalLcars = staticCompositionLocalOf { Century24 }

/**
 * The whole interface in alert colours: yellow alert, red alert, and double red alert
 * (which flashes between bright and dark red; [flash] picks the phase).
 */
fun alertStyle(base: LcarsStyle, condition: dev.vantara.replicatorops.alert.Condition, flash: Boolean = false): LcarsStyle {
    fun c(v: Long) = Color(v)
    return when (condition) {
        dev.vantara.replicatorops.alert.Condition.YELLOW -> base.copy(
            frame = c(0xFFFFCC00), frameAlt = c(0xFFCC9900),
            nav = listOf(c(0xFFFFE066), c(0xFFFFCC33), c(0xFFE6B800), c(0xFFFFD966)),
            sidebar = listOf(c(0xFFFFE066), c(0xFFFFCC33), c(0xFFE6B800), c(0xFFFFD966), c(0xFFFFF0A0), c(0xFFCCA300), c(0xFFFFDD55), c(0xFFE0B000)),
            accent = c(0xFFFFE680), accent2 = c(0xFFFFF0B3), text = c(0xFFFFCC00), dim = c(0xFF3A3000), hint = c(0xFFEEE0B0),
        )
        dev.vantara.replicatorops.alert.Condition.RED, dev.vantara.replicatorops.alert.Condition.DOUBLE_RED -> {
            val bright = condition == dev.vantara.replicatorops.alert.Condition.RED || !flash
            base.copy(
                frame = if (bright) c(0xFFDD2222) else c(0xFF770000),
                frameAlt = if (bright) c(0xFF991111) else c(0xFF440000),
                nav = listOf(c(0xFFFF5555), c(0xFFCC3333), c(0xFFFF7766), c(0xFFAA2222)),
                sidebar = if (bright) listOf(c(0xFFFF5555), c(0xFFCC3333), c(0xFFFF7766), c(0xFFAA2222), c(0xFFFF8877), c(0xFFDD4444), c(0xFFFF6655), c(0xFFBB3333))
                    else List(8) { c(0xFF551111) },
                accent = c(0xFFFF6666), accent2 = c(0xFFFFAAAA), text = c(0xFFFF4444), dim = c(0xFF3A1010), hint = c(0xFFE8BBBB),
                alert = c(0xFFFF2222),
            )
        }
        else -> base
    }
}

@Composable
fun LcarsTheme(style: LcarsStyle, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = style.accent,
        onPrimary = Color.Black,
        secondary = style.frame,
        onSecondary = Color.Black,
        tertiary = style.accent2,
        background = Color.Black,
        onBackground = style.text,
        surface = Color(0xFF0E0E0E),
        onSurface = style.text,
        surfaceVariant = style.dim,
        onSurfaceVariant = style.text,
        error = style.alert,
        outline = style.frame,
    )
    CompositionLocalProvider(LocalLcars provides style) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
