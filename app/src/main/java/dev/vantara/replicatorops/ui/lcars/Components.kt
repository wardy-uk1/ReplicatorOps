package dev.vantara.replicatorops.ui.lcars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

data class NavItem(val label: String, val color: Color)

// ---- Text ------------------------------------------------------------------

@Composable
fun LcarsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalLcars.current.text,
    size: TextUnit = 16.sp,
    align: TextAlign? = null,
    bold: Boolean = false,
    maxLines: Int = 1,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = LcarsFont,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = LocalLcars.current.tracking.em,
        textAlign = align,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---- Frame -----------------------------------------------------------------

/**
 * Elbow shape: a sidebar column of width [sidebar] joined to a top bar of
 * height [bar], with a big outer curve and a small inner curve.
 */
private fun elbowPath(size: Size, sidebar: Float, bar: Float, outerR: Float, innerR: Float): Path {
    val w = size.width
    val h = size.height
    val r = minOf(outerR, sidebar, h)
    val ir = minOf(innerR, h - bar, w - sidebar).coerceAtLeast(0f)
    return Path().apply {
        moveTo(0f, h)
        lineTo(0f, r)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
        lineTo(w, 0f)
        lineTo(w, bar)
        lineTo(sidebar + ir, bar)
        arcTo(Rect(sidebar, bar, sidebar + 2 * ir, bar + 2 * ir), 270f, -90f, false)
        lineTo(sidebar, h)
        close()
    }
}

@Composable
private fun LcarsElbow(modifier: Modifier, color: Color, bar: Dp, flip: Boolean) {
    val s = LocalLcars.current
    Canvas(modifier) {
        val path = elbowPath(size, s.sidebarWidth.toPx(), bar.toPx(), s.outerRadius.toPx(), s.innerRadius.toPx())
        if (flip) {
            scale(1f, -1f, pivot = center) { drawPath(path, color) }
        } else {
            drawPath(path, color)
        }
    }
}

@Composable
private fun EndCap(color: Color, height: Dp) {
    Box(
        Modifier
            .width(22.dp)
            .height(height)
            .clip(RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
            .background(color)
    )
}

/** The full LCARS screen: top elbow + title, sidebar nav, bottom elbow + footer. */
@Composable
fun LcarsFrame(
    title: String,
    nav: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    footer: String,
    content: @Composable () -> Unit,
) {
    val s = LocalLcars.current
    val topHeight = s.outerRadius + 10.dp
    val bottomHeight = s.outerRadius * 0.75f + 6.dp
    val titleSize = if (s.barHeight > 20.dp) 28.sp else 22.sp

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(topHeight)) {
            LcarsElbow(Modifier.weight(1f).fillMaxHeight(), s.frame, s.barHeight, flip = false)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(maxOf(s.barHeight, 30.dp)), contentAlignment = Alignment.CenterStart) {
                LcarsLabel(title, color = s.frame, size = titleSize, bold = true)
            }
            Spacer(Modifier.width(8.dp))
            EndCap(s.frame, s.barHeight)
        }

        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(s.sidebarWidth).fillMaxHeight()) {
                nav.forEachIndexed { i, item ->
                    Spacer(Modifier.height(s.gap))
                    LcarsNavButton(item.label, item.color, i == selected) { onSelect(i) }
                }
                Spacer(Modifier.height(s.gap))
                Box(Modifier.fillMaxWidth().weight(1f).background(s.frameAlt))
                Spacer(Modifier.height(s.gap))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) { content() }
        }

        Row(Modifier.fillMaxWidth().height(bottomHeight), verticalAlignment = Alignment.Bottom) {
            LcarsElbow(Modifier.weight(1f).fillMaxHeight(), s.frame, s.bottomBarHeight, flip = true)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(maxOf(s.bottomBarHeight, 18.dp)), contentAlignment = Alignment.CenterStart) {
                LcarsLabel(footer, color = s.frame, size = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            EndCap(s.frame, s.bottomBarHeight)
        }
    }
}

@Composable
private fun LcarsNavButton(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    val s = LocalLcars.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(if (selected) s.accent else color)
            .clickable { Chirp.nav(); onClick() }
            .padding(end = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        LcarsLabel(label, color = Color.Black, size = 15.sp)
    }
}

// ---- Controls --------------------------------------------------------------

@Composable
fun LcarsButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalLcars.current.accent,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    sound: () -> Unit = Chirp::tap,
    onClick: () -> Unit,
) {
    val s = LocalLcars.current
    Box(
        modifier
            .defaultMinSize(minWidth = 72.dp)
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) color else s.dim)
            .clickable(enabled = enabled) { sound(); onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        LcarsLabel(text, color = if (enabled) Color.Black else Color(0xFF777777), size = 15.sp)
    }
}

/** Single-choice chip used for step sizes and style pickers. */
@Composable
fun LcarsChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = LocalLcars.current
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) s.accent else Color.Black)
            .border(2.dp, s.accent, RoundedCornerShape(percent = 50))
            .clickable { Chirp.tap(); onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        LcarsLabel(text, color = if (selected) Color.Black else s.accent, size = 14.sp)
    }
}

@Composable
fun LcarsSectionHeader(title: String, color: Color = LocalLcars.current.frameAlt, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(16.dp).height(20.dp)
                .clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        LcarsLabel(title, color = color, size = 18.sp, bold = true)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.weight(1f).height(10.dp)
                .clip(RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                .background(color)
        )
    }
}

@Composable
fun LcarsReadout(label: String, value: String, valueColor: Color = LocalLcars.current.text) {
    val s = LocalLcars.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LcarsLabel(label, color = s.accent2, size = 15.sp)
        Spacer(Modifier.width(8.dp))
        LcarsLabel(value, color = valueColor, size = 18.sp, align = TextAlign.End)
    }
}

@Composable
fun LcarsProgress(progress: Double, modifier: Modifier = Modifier, segments: Int = 20) {
    val s = LocalLcars.current
    val lit = (progress.coerceIn(0.0, 1.0) * segments).toInt()
    Row(modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < lit) s.accent else s.dim))
        }
    }
}

/** Panel with an LCARS bracket on the left. */
@Composable
fun LcarsBracketPanel(color: Color = LocalLcars.current.nav[1], modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(8.dp).fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
fun LcarsConfirm(
    title: String,
    text: String,
    confirmLabel: String,
    color: Color = LocalLcars.current.alert,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalLcars.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, color, RoundedCornerShape(24.dp)),
        title = { LcarsLabel(title, color = color, size = 22.sp, bold = true) },
        text = { Text(text, color = s.text, fontFamily = LcarsFont, fontSize = 17.sp) },
        confirmButton = {
            LcarsButton(confirmLabel, color = color, sound = Chirp::confirm) { onConfirm() }
        },
        dismissButton = {
            LcarsButton("Cancel", color = s.frameAlt) { onDismiss() }
        },
    )
}

// ---- Formatting ------------------------------------------------------------

fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN() || seconds < 0) return "--"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, sec)
}

fun formatTemp(t: Double): String = "%.0f°".format(t)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
