package dev.vantara.replicatorops.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.vantara.replicatorops.MainActivity
import dev.vantara.replicatorops.R
import dev.vantara.replicatorops.data.MoonrakerClient
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.data.NotificationIcon
import dev.vantara.replicatorops.data.Prefs
import dev.vantara.replicatorops.data.ReplicationQueue
import dev.vantara.replicatorops.widget.ReplicatorWidget
import dev.vantara.replicatorops.ui.screens.cameraSnapshot
import kotlinx.coroutines.isActive
import dev.vantara.replicatorops.widget.WidgetData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Red Alert: a foreground service that stays linked to the printer and posts
 * notifications when a print finishes, fails, is cancelled or pauses, plus an
 * ongoing "Replicating 42% · 51m left" notification.
 */
class RedAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val client = MoonrakerClient(scope, http)
    private var host = ""
    private val queue by lazy { ReplicationQueue.get(this) }
    private var lastWatch: Watch? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val ep = prefs.endpoint()
        // Must call startForeground promptly, even if we're about to stop.
        startInForeground(watchNotification(lastWatch ?: Watch("Replicator standing by", "Opening subspace link", null, false, false)))
        if (!prefs.redAlert || ep == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> { scope.launch { runCatching { client.call("printer.print.pause") } }; return START_STICKY }
            ACTION_RESUME -> { scope.launch { runCatching { client.call("printer.print.resume") } }; return START_STICKY }
            ACTION_NEXT -> { scope.launch { startNext() }; return START_STICKY }
        }
        if (client.endpoint != ep) {
            host = ep.host
            lastWatch = null
            client.connect(ep)
        }
        if (!watching) {
            scope.launch {
                var last = ""
                var lastAt = 0L
                client.alerts.collect { a ->
                    val now = System.currentTimeMillis()
                    if (a.raw == last && now - lastAt < 30_000) return@collect
                    last = a.raw; lastAt = now
                    postAlert(Alert("Red Alert: printer message", "${a.message} (${a.code})", severe = true))
                }
            }
            watching = true
            scope.launch { spotFailures() }
            scope.launch {
                var prev: PrinterState? = null
                var layerShotTaken = false
                client.state.collect { now ->
                    RedAlerts.transition(prev, now)?.let { alert ->
                        val next = queue.items.value.firstOrNull()
                        if (now.printState == "complete" && next != null) {
                            val label = next.substringAfterLast('/').substringBeforeLast('.')
                            postAlert(alert.copy(text = "${alert.text}. Clear the build plate, then tap Next to start $label."), withNext = true)
                        } else {
                            postAlert(alert)
                        }
                    }
                    // First-layer watch: one camera snapshot per print as layer 2 begins.
                    if (now.ready && !now.isBusy) layerShotTaken = false
                    if (!layerShotTaken && RedAlerts.secondLayerStarted(now) && Prefs(this@RedAlertService).firstLayerWatch) {
                        layerShotTaken = true
                        scope.launch { postFirstLayer(now) }
                    }
                    // Yellow and double red alerts (red comes from the transitions above).
                    val status = monitor.update(now, System.currentTimeMillis())
                    when (status.condition) {
                        Condition.YELLOW, Condition.DOUBLE_RED -> if (status.reason != lastCondition) {
                            lastCondition = status.reason
                            postCondition(status)
                        }
                        Condition.GREEN -> lastCondition = null
                        else -> Unit
                    }
                    // Keep the last ready state so a short link drop doesn't hide a transition.
                    if (now.ready || prev == null) prev = now
                    updateWatch(now)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watching = false
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, WATCH_ID, n, type)
    }

    private fun updateWatch(p: PrinterState) {
        ReplicatorWidget.push(this, WidgetData.of(p))
        val w = RedAlerts.watch(p, host)
        if (w == lastWatch) return
        lastWatch = w
        if (canNotify()) NotificationManagerCompat.from(this).notify(WATCH_ID, watchNotification(w))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun selfAction(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RedAlertService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun watchNotification(w: Watch): Notification =
        NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle(w.title)
            .setContentText(w.text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                w.percent?.let { setProgress(100, it, false) }
                if (w.printing) addAction(0, "Pause", selfAction(ACTION_PAUSE, 1))
                if (w.paused) addAction(0, "Resume", selfAction(ACTION_RESUME, 2))
            }
            .build()

    /** Replication queue: start the next file (from the notification's Next button). */
    private suspend fun startNext() {
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        val next = queue.pop() ?: return
        runCatching {
            client.state.value.box?.let { box ->
                val needs = dev.vantara.replicatorops.data.FilamentMapping.needs(runCatching { client.metadata(next) }.getOrNull())
                val map = dev.vantara.replicatorops.data.FilamentMapping.suggest(needs, box.slots, box.toolMap)
                if (map.isNotEmpty()) client.gcode(dev.vantara.replicatorops.data.FilamentMapping.gcode(map))
            }
            client.call("printer.print.start", kotlinx.serialization.json.buildJsonObject {
                put("filename", kotlinx.serialization.json.JsonPrimitive(next))
            })
        }.onFailure {
            queue.pushFront(next)
            postAlert(Alert("Red Alert: couldn't start the next print", it.message ?: "The printer refused the job", severe = true))
        }
    }

    private fun postAlert(a: Alert, withNext: Boolean = false) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, if (a.severe) CHANNEL_RED else CHANNEL_DONE)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(if (a.severe) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (a.severe) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setColor(if (a.severe) 0xFFDD4444.toInt() else 0xFFFFCC66.toInt())
            .apply { if (withNext) addAction(0, "Next", selfAction(ACTION_NEXT, 4)) }
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, n)
    }

    /** Failure spotter: a camera snapshot every 20 s while printing, checked on the phone. */
    private suspend fun spotFailures() {
        val spotter = FailureSpotter()
        var file: String? = null
        var alarmed = false
        while (scope.isActive) {
            kotlinx.coroutines.delay(20_000)
            val prefs = Prefs(this)
            val p = client.state.value
            if (!prefs.failureSpotter || !p.ready || !p.isPrinting) { if (!p.isPaused) { spotter.reset(); alarmed = false }; continue }
            if (p.filename != file) { file = p.filename; spotter.reset(); alarmed = false }
            // Wait for a few layers: the first ones are thin and the nozzle is busy close to the camera.
            val layer = p.currentLayer ?: (if ((p.position.getOrNull(2) ?: 0.0) > 1.0) 4 else 0)
            if (layer < 3 || alarmed) continue
            spotter.sensitivity = FailureSpotter.Sensitivity.entries.firstOrNull { it.name == prefs.failureSensitivity } ?: FailureSpotter.Sensitivity.MEDIUM
            val key = prefs.apiKey.ifBlank { null }
            val url = runCatching { client.webcamSnapshotUrl() }.getOrNull() ?: continue
            val image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cameraSnapshot(http, url, key) } ?: continue
            val small = android.graphics.Bitmap.createScaledBitmap(image, 96, 72, true)
            val px = IntArray(96 * 72).also { small.getPixels(it, 0, 96, 0, 0, 96, 72) }
            val lum = IntArray(px.size) { i -> val c = px[i]; ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 }
            if (spotter.look(lum, 96, 72).alarm) {
                alarmed = true
                postSpotted(p, image)
            }
        }
    }

    private fun postSpotted(p: PrinterState, image: android.graphics.Bitmap) {
        if (!canNotify()) return
        val name = p.filename?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Print"
        val text = "The camera picture of $name suddenly looks messy: possible spaghetti or a part come loose. Check it and pause if needed."
        val n = NotificationCompat.Builder(this, CHANNEL_RED)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle("Red Alert: possible print failure")
            .setContentText(text)
            .setLargeIcon(image)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as android.graphics.Bitmap?).setSummaryText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFDD4444.toInt())
            .addAction(0, "Pause", selfAction(ACTION_PAUSE, 5))
            .build()
        NotificationManagerCompat.from(this).notify(SPOTTER_ID, n)
    }

    private val monitor = AlertMonitor()
    private var lastCondition: String? = null

    /** Yellow alert (quiet heads-up) or double red alert (loud, can sound through Do Not Disturb). */
    private fun postCondition(st: AlertStatus) {
        val double = st.condition == Condition.DOUBLE_RED
        if (double) AlertSound.play(this, throughDnd = Prefs(this).alertThroughDnd)
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, if (double) CHANNEL_DOUBLE_RED else CHANNEL_YELLOW)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle(if (double) "DOUBLE RED ALERT" else "Yellow alert")
            .setContentText(st.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(st.reason))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(if (double) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (double) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setColor(if (double) 0xFFFF2222.toInt() else 0xFFFFCC00.toInt())
            .build()
        NotificationManagerCompat.from(this).notify(if (double) DOUBLE_ID else YELLOW_ID, n)
    }

    private suspend fun postFirstLayer(p: PrinterState) {
        if (!canNotify()) return
        val key = Prefs(this).apiKey.ifBlank { null }
        val url = runCatching { client.webcamSnapshotUrl() }.getOrNull()
        val image = url?.let { cameraSnapshot(http, it, key) }
        val name = p.filename?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Print"
        val text = if (image != null) "Layer 1 is down on $name. Check it looks right." else "Layer 1 is down on $name (no camera image)."
        val n = NotificationCompat.Builder(this, CHANNEL_LAYER)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle("First layer complete")
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setColor(0xFFFFCC66.toInt())
            .apply {
                if (image != null) {
                    setLargeIcon(image)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as android.graphics.Bitmap?))
                } else {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
                if (p.isPrinting) addAction(0, "Pause", selfAction(ACTION_PAUSE, 3))
            }
            .build()
        NotificationManagerCompat.from(this).notify(LAYER_ID, n)
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_RED = "red_alert"
        const val CHANNEL_DONE = "replication"
        const val CHANNEL_LAYER = "first_layer"
        private const val LAYER_ID = 1703
        private const val WATCH_ID = 1701
        private const val ALERT_ID = 1702
        private const val YELLOW_ID = 1704
        private const val DOUBLE_ID = 1705
        private const val SPOTTER_ID = 1706
        const val CHANNEL_YELLOW = "yellow_alert"
        const val CHANNEL_DOUBLE_RED = "double_red_alert"
        private const val ACTION_PAUSE = "dev.vantara.replicatorops.PAUSE"
        private const val ACTION_RESUME = "dev.vantara.replicatorops.RESUME"
        private const val ACTION_NEXT = "dev.vantara.replicatorops.NEXT"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_WATCH, "Printer watch", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Ongoing progress while Red Alert keeps the link open"
                        setShowBadge(false)
                    },
                    NotificationChannel(CHANNEL_RED, "Red Alert", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Print failed, or paused (for example filament runout)"
                    },
                    NotificationChannel(CHANNEL_DONE, "Replication complete", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Print finished or cancelled"
                    },
                    NotificationChannel(CHANNEL_LAYER, "First-layer watch", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Camera snapshot when the second layer starts"
                    },
                    NotificationChannel(CHANNEL_YELLOW, "Yellow alert", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Worth a look: you paused a print, a heater is slow, the Box is damp"
                    },
                    NotificationChannel(CHANNEL_DOUBLE_RED, "Double red alert", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Critical: Klipper shut down (e.g. heater fault) or the printer was lost mid-print"
                        enableVibration(true)
                        setSound(null, null)  // the app plays its own klaxon (see Config for Do Not Disturb)
                    },
                )
            )
        }

        /** Start (or refresh) the watch if Red Alert is on and a printer is set. */
        fun sync(context: Context) {
            val prefs = Prefs(context)
            val intent = Intent(context, RedAlertService::class.java)
            if (prefs.redAlert && prefs.endpoint() != null) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
