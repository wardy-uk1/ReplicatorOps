package dev.vantara.replicatorops.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Webcam view that polls the snapshot URL (about 3 frames a second) while the
 * screen is visible. Works with QIDI's mjpg-streamer / crowsnest snapshots.
 */
@Composable
fun CameraView(url: String?, http: OkHttpClient, apiKey: String?, modifier: Modifier = Modifier) {
    val s = LocalLcars.current
    var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(url, lifecycle) {
        if (url == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val bmp = withContext(Dispatchers.IO) { fetch(http, url, apiKey) }
                if (bmp != null) {
                    frame = bmp.asImageBitmap()
                    failed = false
                    delay(300)
                } else {
                    failed = true
                    delay(3000)
                }
            }
        }
    }

    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF080808)),
        contentAlignment = Alignment.Center,
    ) {
        val f = frame
        when {
            f != null -> ZoomableBox(Modifier.fillMaxSize()) {
                Image(f, contentDescription = "Printer camera. Pinch to zoom, double-tap to reset.", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            url == null -> LcarsLabel("Visual sensors offline", color = s.dim)
            failed -> LcarsLabel("No camera signal", color = s.alert)
            else -> LcarsLabel("Acquiring image…", color = s.accent2)
        }
    }
}

private fun fetch(http: OkHttpClient, url: String, apiKey: String?): Bitmap? = runCatching {
    val req = Request.Builder().url(url).apply { apiKey?.let { header("X-Api-Key", it) } }.build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) null else r.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
    }
}.getOrNull()
