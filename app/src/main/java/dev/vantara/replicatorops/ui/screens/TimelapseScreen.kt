package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import dev.vantara.replicatorops.data.TimelapseFile
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.ReelState
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsProgress
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Timelapse archive: the printer's timelapse videos. Watch, save, share or delete. */
@Composable
fun TimelapseScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val reels by vm.reels.collectAsState()
    val loading by vm.reelsLoading.collectAsState()
    val error by vm.reelsError.collectAsState()
    val busy by vm.reelState.collectAsState()
    var selected by remember { mutableStateOf<TimelapseFile?>(null) }

    LaunchedEffect(p.online) { if (p.online) vm.refreshReels() }

    Column(Modifier.fillMaxSize()) {
        LcarsSectionHeader("Timelapse archive", color = s.nav[1])
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsButton(if (loading) "Scanning…" else "Refresh", color = s.nav[1], enabled = p.online && !loading) { vm.refreshReels() }
        }
        Spacer(Modifier.height(6.dp))
        when {
            !p.online -> LcarsLabel("Connect to the printer to see its timelapses.", color = s.hint, size = 14.sp, maxLines = 2)
            error != null -> LcarsLabel(error!!, color = s.hint, size = 14.sp, maxLines = 5)
            !loading && reels.isEmpty() -> LcarsLabel("No timelapses recorded yet.", color = s.hint, size = 14.sp)
            else -> LcarsLabel("${reels.size} recordings", color = s.accent2, size = 14.sp)
        }
        (busy as? ReelState.Busy)?.let { b ->
            Spacer(Modifier.height(6.dp))
            LcarsLabel("${b.verb} ${b.name}", color = s.accent, size = 13.sp)
            LcarsProgress(b.progress.toDouble())
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(reels, key = { it.path }) { f -> ReelTile(f, vm) { Chirp.tap(); selected = f } }
        }
    }

    selected?.let { f -> ReelDialog(vm, f) { selected = null } }
}

private val dateFormat = SimpleDateFormat("d MMM HH:mm", Locale.UK)

private fun reelTitle(f: TimelapseFile): String =
    f.name.substringBeforeLast('.').removePrefix("timelapse_").replace('_', ' ')

@Composable
private fun ReelTile(f: TimelapseFile, vm: AppViewModel, onClick: () -> Unit) {
    val s = LocalLcars.current
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, s.frameAlt, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(s.dim.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = vm.reelThumbUrl(f)
            if (thumb != null) {
                AsyncImage(
                    model = thumb,
                    imageLoader = vm.imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LcarsLabel("▶", color = s.accent, size = 26.sp)
        }
        Spacer(Modifier.height(4.dp))
        LcarsLabel(reelTitle(f), color = s.accent, size = 13.sp)
        LcarsLabel("${dateFormat.format(Date((f.modified * 1000).toLong()))} · ${formatSize(f.size)}", color = s.hint, size = 11.sp)
    }
}

@Composable
private fun ReelDialog(vm: AppViewModel, f: TimelapseFile, onDismiss: () -> Unit) {
    val s = LocalLcars.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        uri?.let { vm.saveReel(f, it) }
    }
    val url = vm.reelUrl(f)
    val key = vm.prefs.apiKey.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel(reelTitle(f), color = s.nav[1], size = 18.sp, bold = true, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (url != null) {
                    var view by remember { mutableStateOf<VideoView?>(null) }
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controls = MediaController(ctx)
                                controls.setAnchorView(this)
                                setMediaController(controls)
                                val headers = if (key.isNotEmpty()) mapOf("X-Api-Key" to key) else emptyMap()
                                setVideoURI(Uri.parse(url), headers)
                                setOnPreparedListener { mp -> mp.isLooping = true; start() }
                                setOnErrorListener { _, _, _ -> true }
                                view = this
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
                }
                Spacer(Modifier.height(6.dp))
                LcarsLabel(
                    "${dateFormat.format(Date((f.modified * 1000).toLong()))} · ${formatSize(f.size)} · tap the video for controls",
                    color = s.hint, size = 12.sp, maxLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Save", color = s.nav[0], height = 38.dp) { saveTo.launch(f.name) }
                    LcarsButton("Share", color = s.nav[2], height = 38.dp) { vm.shareReel(f) { context.startActivity(it) } }
                    LcarsButton("Delete", color = s.alert, height = 38.dp) { confirmDelete = true }
                }
            }
        },
        confirmButton = { LcarsButton("Close", color = s.frameAlt) { onDismiss() } },
    )

    if (confirmDelete) {
        LcarsConfirm(
            title = "Delete timelapse?",
            text = "${f.name} will be removed from the printer. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; vm.deleteReel(f); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
}
