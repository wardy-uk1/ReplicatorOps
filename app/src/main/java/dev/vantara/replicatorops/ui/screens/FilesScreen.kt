package dev.vantara.replicatorops.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.vantara.replicatorops.data.FileMeta
import dev.vantara.replicatorops.data.GcodeFile
import dev.vantara.replicatorops.ui.AppViewModel
import dev.vantara.replicatorops.ui.Screen
import dev.vantara.replicatorops.ui.lcars.Chirp
import dev.vantara.replicatorops.ui.lcars.LcarsButton
import dev.vantara.replicatorops.ui.lcars.LcarsConfirm
import dev.vantara.replicatorops.ui.lcars.LcarsLabel
import dev.vantara.replicatorops.ui.lcars.LcarsReadout
import dev.vantara.replicatorops.ui.lcars.LcarsSectionHeader
import dev.vantara.replicatorops.ui.lcars.LocalLcars
import dev.vantara.replicatorops.ui.lcars.formatDuration
import dev.vantara.replicatorops.ui.lcars.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesScreen(vm: AppViewModel) {
    val s = LocalLcars.current
    val p by vm.printer.collectAsState()
    val files by vm.files.collectAsState()
    val loading by vm.filesLoading.collectAsState()
    val meta by vm.meta.collectAsState()
    var selectedFile by remember { mutableStateOf<GcodeFile?>(null) }
    var deleting by remember { mutableStateOf<GcodeFile?>(null) }
    var showQueue by remember { mutableStateOf(false) }

    // Load pattern: pick a file on the phone (sharing from other apps arrives the same way).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.receivePattern(it) }
    }
    // A pattern was just beamed up: open its details so it can be replicated.
    val justLoaded by vm.openFile.collectAsState()
    LaunchedEffect(justLoaded) {
        justLoaded?.let { selectedFile = it; vm.openFileHandled() }
    }

    Column(Modifier.fillMaxSize()) {
        LcarsSectionHeader("Pattern buffer", color = s.nav[1])
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton("Load pattern", color = s.accent, height = 36.dp, enabled = p.online) {
                picker.launch(arrayOf("*/*"))
            }
            LcarsButton(if (loading) "Scanning" else "Refresh", color = s.nav[1], enabled = p.online && !loading, height = 36.dp) {
                vm.refreshFiles()
            }
        }
        Spacer(Modifier.height(6.dp))
        val queue by vm.queue.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            LcarsLabel("${files.size} patterns stored", color = s.accent2, size = 14.sp, modifier = Modifier.weight(1f))
            LcarsButton("Queue (${queue.size})", color = s.nav[2], height = 34.dp) { showQueue = true }
        }
        Spacer(Modifier.height(6.dp))

        if (!p.online) {
            LcarsLabel("Printer offline", color = s.alert)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(files, key = { it.path }) { f ->
                LaunchedEffect(f.path) { vm.loadMeta(f.path) }
                FileTile(f, meta[f.path], vm) { Chirp.tap(); selectedFile = f }
            }
            item(span = { GridItemSpan(2) }) {
                LcarsButton("Mission log", color = s.nav[0], modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    vm.select(Screen.LOG)
                }
            }
        }
    }

    selectedFile?.let { f ->
        FileDialog(
            file = f,
            meta = meta[f.path],
            vm = vm,
            canPrint = p.ready && !p.isBusy,
            onPrint = { selectedFile = null; vm.startPrint(f.path) },
            onDelete = { selectedFile = null; deleting = f },
            onDismiss = { selectedFile = null },
        )
    }
    if (showQueue) QueueDialog(vm) { showQueue = false }
    deleting?.let { f ->
        LcarsConfirm(
            title = "Purge pattern?",
            text = "Delete ${f.name} from the printer?",
            confirmLabel = "Delete",
            onConfirm = { deleting = null; vm.deleteFile(f.path) },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun FileTile(f: GcodeFile, m: FileMeta?, vm: AppViewModel, onClick: () -> Unit) {
    val s = LocalLcars.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, s.dim, RoundedCornerShape(10.dp))
            .background(Color(0xFF111111))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            if (m?.thumbnailUrl != null) {
                AsyncImage(
                    model = m.thumbnailUrl,
                    imageLoader = vm.imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            } else {
                LcarsLabel("No preview", color = s.hint, size = 12.sp)
            }
        }
        Column(Modifier.padding(horizontal = 7.dp, vertical = 5.dp)) {
            LcarsLabel(f.name.removeSuffix(".gcode"), color = s.text, size = 13.sp)
            LcarsLabel(
                listOfNotNull(m?.estimatedSeconds?.let { formatDuration(it) }, formatDate(f.modified)).joinToString(" · "),
                color = s.accent2,
                size = 11.sp,
            )
        }
    }
}

@Composable
private fun FileDialog(
    file: GcodeFile,
    meta: FileMeta?,
    vm: AppViewModel,
    canPrint: Boolean,
    onPrint: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalLcars.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Black,
        modifier = Modifier.border(2.dp, s.nav[1], RoundedCornerShape(24.dp)),
        title = { LcarsLabel(file.name.removeSuffix(".gcode"), color = s.nav[1], size = 20.sp, bold = true, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (meta?.thumbnailUrl != null) {
                    ZoomableBox(Modifier.fillMaxWidth().aspectRatio(1.3f).background(Color(0xFF111111))) {
                        AsyncImage(
                            model = meta.thumbnailUrl,
                            imageLoader = vm.imageLoader,
                            contentDescription = "Model thumbnail. Pinch to zoom, double-tap to reset.",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    LcarsLabel("Pinch to zoom · double-tap to reset", color = s.hint, size = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(6.dp))
                }
                LcarsReadout("Est. time", formatDuration(meta?.estimatedSeconds))
                meta?.filamentMm?.let { LcarsReadout("Filament", "%.2f m".format(it / 1000)) }
                meta?.filamentGrams?.let { LcarsReadout("Weight", "%.1f g".format(it)) }
                meta?.layerHeight?.let { LcarsReadout("Layer height", "%.2f mm".format(it)) }
                meta?.objectHeight?.let { LcarsReadout("Height", "%.1f mm".format(it)) }
                meta?.slicer?.let { LcarsReadout("Slicer", it) }
                LcarsReadout("Size", formatSize(file.size))
                Spacer(Modifier.height(10.dp))
                // Retrieve pattern: Android's save screen picks the folder and name.
                val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { dest ->
                    dest?.let { vm.retrievePattern(file, it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("Retrieve pattern", color = s.nav[1], height = 38.dp) { saver.launch(file.name) }
                    LcarsButton("Add to queue", color = s.nav[2], height = 38.dp) { vm.addToQueue(file.path); onDismiss() }
                }
                if (!canPrint) {
                    Spacer(Modifier.height(6.dp))
                    LcarsLabel("Replicator busy or not ready", color = s.alert, size = 14.sp)
                }
            }
        },
        confirmButton = {
            LcarsButton("Replicate", color = s.accent, enabled = canPrint, sound = Chirp::confirm) { onPrint() }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcarsButton("Delete", color = s.alert) { onDelete() }
                LcarsButton("Close", color = s.frameAlt) { onDismiss() }
            }
        },
    )
}

private fun formatDate(epochSeconds: Double): String =
    SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date((epochSeconds * 1000).toLong()))
