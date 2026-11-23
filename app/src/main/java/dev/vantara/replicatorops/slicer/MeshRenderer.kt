package dev.vantara.replicatorops.slicer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a plate's triangles as a shaded 3D view on the Q2C bed, in software, so the
 * preview works on every phone (no WebGL needed). Orthographic camera orbiting the
 * model: [turn] around the vertical axis, [tilt] from straight down (0) to side-on (π/2).
 */
class MeshRenderer(private val tris: FloatArray) {
    private val count = tris.size / 9
    private val minX: Float; private val maxX: Float
    private val minY: Float; private val maxY: Float
    private val maxZ: Float
    /** Per-triangle brightness for a fixed light, 0..1. */
    private val shade = FloatArray(count)
    private val keys = LongArray(count)
    private val verts = FloatArray(count * 6)
    private val colors = IntArray(count * 3)

    init {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
        var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var z1 = 0f
        var i = 0
        while (i < tris.size) {
            val x = tris[i]; val y = tris[i + 1]; val z = tris[i + 2]
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
            if (z > z1) z1 = z
            i += 3
        }
        if (count == 0) { x0 = 0f; x1 = 0f; y0 = 0f; y1 = 0f }
        minX = x0; maxX = x1; minY = y0; maxY = y1; maxZ = z1
        // Light from above, front-left.
        val lx = -0.35f; val ly = -0.55f; val lz = 0.76f
        for (t in 0 until count) {
            val b = t * 9
            val ux = tris[b + 3] - tris[b]; val uy = tris[b + 4] - tris[b + 1]; val uz = tris[b + 5] - tris[b + 2]
            val vx = tris[b + 6] - tris[b]; val vy = tris[b + 7] - tris[b + 1]; val vz = tris[b + 8] - tris[b + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 0f) { nx /= len; ny /= len; nz /= len }
            // Two-sided: models from the web aren't always wound consistently.
            shade[t] = 0.35f + 0.65f * kotlin.math.abs(nx * lx + ny * ly + nz * lz)
        }
    }

    /** Model centre (x, y) and the radius that frames it. */
    val cx: Float get() = (minX + maxX) / 2
    val cy: Float get() = (minY + maxY) / 2
    val radius: Float
        get() {
            val dx = maxX - minX; val dy = maxY - minY
            return max(10f, sqrt(dx * dx + dy * dy + maxZ * maxZ) / 2)
        }

    /**
     * Renders into [canvas] of [w]×[h] px. [zoom] 1 = fitted to the model.
     * [base] is the model colour; the bed is drawn under it.
     */
    fun draw(canvas: Canvas, w: Int, h: Int, turn: Float, tilt: Float, zoom: Float, base: Int, showBed: Boolean = true) {
        canvas.drawColor(Color.BLACK)
        val scale = min(w, h) * 0.46f / radius * zoom
        val ct = cos(turn); val st = sin(turn)
        val cT = cos(tilt); val sT = sin(tilt)
        val ox = w / 2f; val oy = h / 2f
        val midZ = maxZ / 2
        // Project (x, y, z) → screen; depth grows away from the viewer.
        fun px(x: Float, y: Float) = ox + ((x - cx) * ct - (y - cy) * st) * scale
        fun py(x: Float, y: Float, z: Float): Float {
            val yr = (x - cx) * st + (y - cy) * ct
            return oy - (yr * cT + (z - midZ) * sT) * scale
        }

        if (showBed) drawBed(canvas, ::px, ::py)

        // Painter's algorithm: sort triangles far → near using packed (depth, index) keys.
        for (t in 0 until count) {
            val b = t * 9
            val mx = (tris[b] + tris[b + 3] + tris[b + 6]) / 3 - cx
            val my = (tris[b + 1] + tris[b + 4] + tris[b + 7]) / 3 - cy
            val mz = (tris[b + 2] + tris[b + 5] + tris[b + 8]) / 3 - midZ
            val yr = mx * st + my * ct
            val depth = yr * sT - mz * cT  // larger = further away
            // Float bits → an Int that sorts the same way as the float.
            val bits = java.lang.Float.floatToIntBits(depth).let { if (it < 0) it xor 0x7FFFFFFF else it }
            keys[t] = (bits.toLong() shl 32) or t.toLong()
        }
        keys.sort()
        val r = Color.red(base); val g = Color.green(base); val bl = Color.blue(base)
        var vi = 0; var ci = 0
        for (k in count - 1 downTo 0) {
            val t = (keys[k] and 0xFFFFFFFFL).toInt()
            val b = t * 9
            for (v in 0 until 3) {
                val x = tris[b + v * 3]; val y = tris[b + v * 3 + 1]; val z = tris[b + v * 3 + 2]
                verts[vi++] = px(x, y); verts[vi++] = py(x, y, z)
            }
            val sh = shade[t]
            val c = Color.rgb((r * sh).toInt().coerceIn(0, 255), (g * sh).toInt().coerceIn(0, 255), (bl * sh).toInt().coerceIn(0, 255))
            colors[ci++] = c; colors[ci++] = c; colors[ci++] = c
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vi, verts, 0, null, 0, colors, 0, null, 0, 0, Paint())
    }

    private fun drawBed(canvas: Canvas, px: (Float, Float) -> Float, py: (Float, Float, Float) -> Float) {
        // The Q2C bed is 270 mm square; the model is centred on it, as the slicer does.
        val half = 135f
        val bx0 = cx - half; val bx1 = cx + half; val by0 = cy - half; val by1 = cy + half
        val grid = Paint().apply { color = 0xFF4A3320.toInt(); strokeWidth = 1.5f; isAntiAlias = true }
        val edge = Paint().apply { color = 0xFFFF9900.toInt(); strokeWidth = 3f; isAntiAlias = true }
        var g = 0f
        while (g <= 270f) {
            canvas.drawLine(px(bx0 + g, by0), py(bx0 + g, by0, 0f), px(bx0 + g, by1), py(bx0 + g, by1, 0f), grid)
            canvas.drawLine(px(bx0, by0 + g), py(bx0, by0 + g, 0f), px(bx1, by0 + g), py(bx1, by0 + g, 0f), grid)
            g += 30f
        }
        val corners = listOf(bx0 to by0, bx1 to by0, bx1 to by1, bx0 to by1)
        for (i in 0 until 4) {
            val (ax, ay) = corners[i]; val (bx, by) = corners[(i + 1) % 4]
            canvas.drawLine(px(ax, ay), py(ax, ay, 0f), px(bx, by), py(bx, by, 0f), edge)
        }
    }

    /** Square PNG thumbnail (base64) for the printer's screen. */
    fun thumbnailBase64(px: Int, base: Int = 0xFFFFCC66.toInt()): String {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), px, px, -0.6f, 0.95f, 1.05f, base, showBed = false)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
