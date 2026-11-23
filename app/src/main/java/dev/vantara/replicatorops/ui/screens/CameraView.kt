package dev.vantara.replicatorops.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.vantara.replicatorops.data.Webcam
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Camera button dialog: live view, with a full-screen option. */
@Composable
fun CameraDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val cam by vm.webcam.collectAsState()
    var full by remember { mutableStateOf(false) }
    val key = vm.prefs.apiKey.ifBlank { null }

    if (full) {
        Dialog(
            onDismissRequest = { full = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CameraView(cam, vm.http, key, Modifier.fillMaxSize(), fill = true)
                LcarsButton("Exit full screen", color = s.frameAlt, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { full = false }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel("Visual sensors", color = s.nav[1], size = 20.sp, bold = true) },
        text = { CameraView(cam, vm.http, key, Modifier.fillMaxWidth()) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcarsButton("Full screen", color = s.nav[1]) { full = true }
                LcarsButton("Close", color = s.frameAlt) { onDismiss() }
            }
        },
    )
}

/**
 * Live webcam view. Plays the MJPEG stream (mjpg-streamer / crowsnest) while the
 * screen is visible, and falls back to polling snapshots if the stream won't open.
 * Pinch to zoom, double-tap to reset.
 */
@Composable
fun CameraView(cam: Webcam?, http: OkHttpClient, apiKey: String?, modifier: Modifier = Modifier, fill: Boolean = false) {
    val s = LocalLcars.current
    var frame by remember(cam) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(cam) { mutableStateOf(false) }
    var live by remember(cam) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Keep the screen on while the camera is showing.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(cam, lifecycle) {
        if (cam == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var streamFailures = 0
            while (isActive) {
                if (streamFailures < 2) {
                    // Live stream first.
                    val ok = runCatching {
                        streamFrames(http, cam.stream, apiKey) { bmp ->
                            frame = bmp.asImageBitmap(); failed = false; live = true
                        }
                    }.isSuccess
                    live = false
                    if (!ok) streamFailures++
                    delay(500)
                } else {
                    // Snapshot fallback, about 3 frames a second.
                    val bmp = withContext(Dispatchers.IO) { snapshot(http, cam.snapshot, apiKey) }
                    if (bmp != null) {
                        frame = bmp.asImageBitmap(); failed = false
                        delay(300)
                    } else {
                        failed = true
                        delay(3000)
                    }
                }
            }
        }
    }

    Box(
        (if (fill) modifier else modifier.fillMaxWidth().aspectRatio(16f / 9f)).background(Color(0xFF080808)),
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        when {
            f != null -> ZoomableBox(Modifier.fillMaxSize()) {
                Image(f, contentDescription = "Printer camera. Pinch to zoom, double-tap to reset.", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            cam == null -> LcarsLabel("Visual sensors offline", color = s.hint)
            failed -> LcarsLabel("No camera signal", color = s.alert)
            else -> LcarsLabel("Acquiring image…", color = s.accent2)
        }
        if (f != null) {
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) s.alert else s.hint))
                Spacer(Modifier.width(5.dp))
                LcarsLabel(if (live) "Live" else "Snapshot", color = if (live) s.alert else s.hint, size = 11.sp)
            }
        }
    }
}

/** Reads an MJPEG (multipart) stream and hands over each JPEG frame. Returns when the stream ends. */
private suspend fun streamFrames(http: OkHttpClient, url: String, apiKey: String?, onFrame: (Bitmap) -> Unit) =
    withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(url).apply { apiKey?.let { header("X-Api-Key", it) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Stream refused (${r.code})")
            val type = r.header("Content-Type").orEmpty()
            if (!type.contains("multipart", ignoreCase = true)) throw IOException("Not an MJPEG stream")
            val input = r.body?.byteStream() ?: throw IOException("No stream")
            var buf = ByteArray(512 * 1024)
            var len = 0
            val chunk = ByteArray(32 * 1024)
            var lastFrame = 0L
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(chunk)
                if (n < 0) break
                if (len + n > buf.size) {
                    if (len + n > 8 * 1024 * 1024) len = 0 // runaway: start again
                    else buf = buf.copyOf(maxOf(buf.size * 2, len + n))
                }
                System.arraycopy(chunk, 0, buf, len, n)
                len += n
                // Pull out every complete JPEG (FFD8 … FFD9) in the buffer.
                while (true) {
                    val start = indexOf(buf, len, 0xD8, 0)
                    if (start < 0) { len = 0; break }
                    val end = indexOf(buf, len, 0xD9, start + 2)
                    if (end < 0) {
                        if (start > 0) { System.arraycopy(buf, start, buf, 0, len - start); len -= start }
                        break
                    }
                    val frameEnd = end + 2
                    val now = System.currentTimeMillis()
                    // Cap at ~15 fps to save battery.
                    if (now - lastFrame >= 66) {
                        BitmapFactory.decodeByteArray(buf, start, frameEnd - start)?.let { onFrame(it); lastFrame = now }
                    }
                    System.arraycopy(buf, frameEnd, buf, 0, len - frameEnd)
                    len -= frameEnd
                }
            }
        }
    }

/** Index of the marker 0xFF [code] at or after [from], or -1. */
private fun indexOf(buf: ByteArray, len: Int, code: Int, from: Int): Int {
    var i = maxOf(from, 0)
    while (i < len - 1) {
        if (buf[i] == 0xFF.toByte() && buf[i + 1] == code.toByte()) return i
        i++
    }
    return -1
}

private fun snapshot(http: OkHttpClient, url: String, apiKey: String?): Bitmap? = runCatching {
    val req = Request.Builder().url(url).apply { apiKey?.let { header("X-Api-Key", it) } }.build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) null else r.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
    }
}.getOrNull()

/** Downloads one snapshot, scaled down for a notification. */
fun cameraSnapshot(http: OkHttpClient, url: String, apiKey: String?, maxWidth: Int = 1024): Bitmap? =
    snapshot(http, url, apiKey)?.let { b ->
        if (b.width <= maxWidth) b else Bitmap.createScaledBitmap(b, maxWidth, b.height * maxWidth / b.width, true)
    }
