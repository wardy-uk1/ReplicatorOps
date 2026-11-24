package dev.vantara.replicatorops.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MoonrakerException(message: String) : IOException(message)

/**
 * Minimal Moonraker client: JSON-RPC 2.0 over the `/websocket` endpoint.
 * Docs: https://moonraker.readthedocs.io/en/latest/web_api/
 */
class MoonrakerClient(
    private val scope: CoroutineScope,
    val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val objects = HashMap<String, JsonObject>()

    private var socket: WebSocket? = null
    private var generation = 0
    private var wanted = false
    private var reconnectJob: Job? = null

    var endpoint: Endpoint? = null
        private set

    private val _state = MutableStateFlow(PrinterState())
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val _alerts = MutableSharedFlow<PrinterAlert>(extraBufferCapacity = 8)
    /** Pop-up messages from the printer (QIDI "Code:QDE_…" errors). */
    val alerts: SharedFlow<PrinterAlert> = _alerts

    private val _fileListChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val fileListChanged: SharedFlow<Unit> = _fileListChanged

    /** Screenshot tests: show a made-up printer without connecting. */
    internal fun showForTest(s: PrinterState) { _state.value = s }

    fun connect(ep: Endpoint) {
        disconnect()
        endpoint = ep
        wanted = true
        openSocket()
    }

    fun disconnect() {
        wanted = false
        reconnectJob?.cancel()
        generation++
        socket?.close(1000, "bye")
        socket = null
        failPending("Disconnected")
        synchronized(objects) { objects.clear() }
        _state.value = PrinterState()
    }

    private fun openSocket() {
        val ep = endpoint ?: return
        val gen = ++generation
        _state.update { it.copy(connection = Connection.CONNECTING, connectionError = null) }
        val request = try {
            Request.Builder().url(ep.wsUrl).apply {
                ep.apiKey?.let { header("X-Api-Key", it) }
            }.build()
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(connection = Connection.DISCONNECTED, connectionError = "Bad address") }
            return
        }
        socket = http.newWebSocket(request, Listener(gen))
    }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation) return
            _state.update { it.copy(connection = Connection.CONNECTED, connectionError = null) }
            scope.launch {
                runCatching { initialise() }.onFailure { e ->
                    _state.update { it.copy(klippyMessage = e.message) }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            runCatching { handle(text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen == generation) lost(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation) return
            val why = when (response?.code) {
                401, 403 -> "Unauthorised: add an API key or trust this network in moonraker.conf"
                404 -> "No Moonraker at this address (try adding :7125)"
                else -> t.message ?: t.javaClass.simpleName
            }
            lost(why)
        }
    }

    private fun lost(error: String?) {
        socket = null
        failPending("Connection lost")
        _state.update { it.copy(connection = Connection.DISCONNECTED, connectionError = error) }
        if (wanted) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(3000)
                if (wanted && socket == null) openSocket()
            }
        }
    }

    private fun failPending(reason: String) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(IOException(reason)) }
    }

    // ---- JSON-RPC ---------------------------------------------------------

    suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = 15_000): JsonElement {
        val ws = socket ?: throw IOException("Not connected")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
            put("id", id)
        }
        if (!ws.send(payload.toString())) {
            pending.remove(id)
            throw IOException("Send failed")
        }
        try {
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun gcode(script: String): JsonElement =
        call("printer.gcode.script", buildJsonObject { put("script", script) }, timeoutMs = 10 * 60_000L)

    private fun handle(text: String) {
        val msg = json.parseToJsonElement(text).jsonObject
        val id = (msg["id"] as? JsonPrimitive)?.intOrNull
        if (id != null) {
            val deferred = pending.remove(id) ?: return
            val error = msg["error"]
            if (error is JsonObject) {
                val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Moonraker error"
                deferred.completeExceptionally(MoonrakerException(cleanError(message)))
            } else {
                deferred.complete(msg["result"] ?: JsonNull)
            }
            return
        }
        when (msg["method"]?.jsonPrimitive?.contentOrNull) {
            "notify_status_update" -> {
                val upd = (msg["params"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
                merge(upd)
            }
            "notify_klippy_ready" -> scope.launch { runCatching { initialise() } }
            "notify_klippy_shutdown" -> _state.update { it.copy(klippy = "shutdown") }
            "notify_klippy_disconnected" -> {
                _state.update { it.copy(klippy = "disconnected") }
                scope.launch { delay(2000); runCatching { initialise() } }
            }
            "notify_filelist_changed" -> _fileListChanged.tryEmit(Unit)
            "notify_gcode_response" -> (msg["params"] as? JsonArray)?.forEach { el ->
                (el as? JsonPrimitive)?.contentOrNull?.let { line -> PrinterAlerts.parse(line)?.let { _alerts.tryEmit(it) } }
            }
        }
    }

    /** Klipper errors arrive as Python-style dict strings; keep only the message. */
    private fun cleanError(message: String): String {
        val m = Regex("'message':\\s*'([^']*)'").find(message)
        return m?.groupValues?.get(1) ?: message
    }

    // ---- Start-up and subscription ---------------------------------------

    private suspend fun initialise() {
        val info = call("server.info").jsonObject
        val klippy = info["klippy_state"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        _state.update { it.copy(klippy = klippy) }
        if (klippy == "ready") {
            subscribe()
        } else {
            // Moonraker sends notify_klippy_ready later; meanwhile show why.
            val printerInfo = runCatching { call("printer.info").jsonObject }.getOrNull()
            val message = printerInfo?.get("state_message")?.jsonPrimitive?.contentOrNull
            _state.update { it.copy(klippyMessage = message) }
            if (klippy == "startup") {
                delay(2000)
                if (_state.value.online) initialise()
            }
        }
    }

    private suspend fun subscribe() {
        val available = call("printer.objects.list").jsonObject["objects"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val wantedObjects = available.filter { name ->
            name in BASE_OBJECTS ||
                name.startsWith("heater_generic ") ||
                name.startsWith("temperature_sensor ") ||
                name.startsWith("temperature_fan ") ||
                name.startsWith("fan_generic ") ||
                name.startsWith("box") ||
                name.startsWith("output_pin ") ||
                name.startsWith("aht20") ||
                name == "save_variables"
        }
        val macros = available.filter { it.startsWith("gcode_macro ") }
            .map { it.removePrefix("gcode_macro ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("_") }
            .sortedBy { it.uppercase() }
        val params = buildJsonObject {
            putJsonObject("objects") { wantedObjects.forEach { put(it, JsonNull) } }
        }
        val result = call("printer.objects.subscribe", params).jsonObject
        synchronized(objects) { objects.clear() }
        (result["status"] as? JsonObject)?.let { merge(it) }
        _state.update { it.copy(klippy = "ready", klippyMessage = null, macros = macros) }
    }

    private fun merge(update: JsonObject) {
        val snapshot: Map<String, JsonObject>
        synchronized(objects) {
            for ((name, value) in update) {
                val incoming = value as? JsonObject ?: continue
                val old = objects[name]
                objects[name] = if (old == null) incoming else deepMerge(old, incoming)
            }
            snapshot = HashMap(objects)
        }
        _state.update { derive(it, snapshot) }
    }

    private fun deepMerge(old: JsonObject, new: JsonObject): JsonObject {
        val out = old.toMutableMap()
        for ((k, v) in new) {
            val o = out[k]
            out[k] = if (o is JsonObject && v is JsonObject) deepMerge(o, v) else v
        }
        return JsonObject(out)
    }

    private fun derive(prev: PrinterState, o: Map<String, JsonObject>): PrinterState {
        val stats = o["print_stats"]
        val display = o["display_status"]
        val sd = o["virtual_sdcard"]
        val info = stats?.get("info") as? JsonObject
        val gm = o["gcode_move"]
        val th = o["toolhead"]

        val heaters = buildList {
            o["extruder"]?.let { add(heater("extruder", "extruder", "Nozzle", it)) }
            o["heater_bed"]?.let { add(heater("heater_bed", "heater_bed", "Bed", it)) }
            o.keys.filter { it.startsWith("heater_generic ") }.sorted().forEach { key ->
                val short = key.removePrefix("heater_generic ")
                val label = if ("box" in short) "Box dryer" else pretty(short)
                add(heater(key, short, label, o.getValue(key)))
            }
            o.keys.filter { it.startsWith("temperature_sensor ") || it.startsWith("temperature_fan ") }
                .filterNot { "box" in it.lowercase() || "protection" in it.lowercase() }
                .sorted().forEach { key ->
                    val short = key.substringAfter(' ')
                    add(heater(key, null, pretty(short), o.getValue(key)))
                }
        }

        val fans = buildList {
            o["fan"]?.let { add(Fan("fan", "Part", it.d("speed") ?: 0.0)) }
            o.keys.filter { it.startsWith("fan_generic ") }.sorted().forEach { key ->
                val short = key.removePrefix("fan_generic ")
                val label = when {
                    "cooling" in short -> "Part"
                    "aux" in short -> "Aux"
                    "chamber" in short || "circulation" in short -> "Chamber"
                    "exhaust" in short -> "Exhaust"
                    else -> pretty(short)
                }
                add(Fan(key, label, o.getValue(key).d("speed") ?: 0.0))
            }
        }
        val (box, boxRaw) = parseBox(o)

        val displayProgress = display.d("progress")
        val sdProgress = sd.d("progress")
        return prev.copy(
            printState = stats.s("state") ?: prev.printState,
            filename = stats.s("filename")?.takeIf { it.isNotBlank() },
            progress = displayProgress?.takeIf { it > 0 } ?: sdProgress ?: 0.0,
            filePosition = (sd?.get("file_position") as? JsonPrimitive)?.longOrNull ?: prev.filePosition,
            printDuration = stats.d("print_duration") ?: 0.0,
            totalDuration = stats.d("total_duration") ?: 0.0,
            filamentUsedMm = stats.d("filament_used") ?: 0.0,
            currentLayer = info.i("current_layer"),
            totalLayers = info.i("total_layer"),
            message = display.s("message")?.takeIf { it.isNotBlank() }
                ?: stats.s("message")?.takeIf { it.isNotBlank() },
            heaters = heaters,
            partFan = o["fan"].d("speed") ?: o["fan_generic cooling_fan"].d("speed"),
            speedFactor = gm.d("speed_factor") ?: 1.0,
            extrudeFactor = gm.d("extrude_factor") ?: 1.0,
            position = (th?.get("position") as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList(),
            homedAxes = th.s("homed_axes") ?: "",
            fans = fans,
            box = box,
            boxRaw = boxRaw,
            objects = parseObjects(o["exclude_object"]),
            excludedObjects = (o["exclude_object"]?.get("excluded_objects") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
            currentObject = o["exclude_object"].s("current_object")?.takeIf { it.isNotBlank() },
            bedMin = xy(th?.get("axis_minimum")),
            bedMax = xy(th?.get("axis_maximum")),
            systems = parseSystems(o),
            bedMesh = parseMesh(o["bed_mesh"]),
            meshProfiles = parseMeshProfiles(o["bed_mesh"]),
        )
    }

    private fun xy(el: JsonElement?): Pair<Double, Double>? {
        val a = (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
        return if (a.size >= 2) a[0] to a[1] else null
    }

    private fun parseMesh(m: JsonObject?): BedMesh? {
        m ?: return null
        fun matrix(key: String) = (m[key] as? JsonArray).orEmpty().mapNotNull { row ->
            (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isNotEmpty() }
        }
        // probed_matrix is what was measured; mesh_matrix is the smoothed version Klipper uses.
        val heights = matrix("mesh_matrix").takeIf { it.size >= 2 } ?: matrix("probed_matrix").takeIf { it.size >= 2 } ?: return null
        return BedMesh(
            profile = m.s("profile_name")?.takeIf { it.isNotBlank() } ?: "default",
            min = xy(m["mesh_min"]) ?: (0.0 to 0.0),
            max = xy(m["mesh_max"]) ?: (1.0 to 1.0),
            heights = heights,
        )
    }

    /** bed_mesh.profiles: name -> { points, mesh_params { min_x, max_x, min_y, max_y } }. */
    private fun parseMeshProfiles(m: JsonObject?): Map<String, BedMesh> {
        val profiles = m?.get("profiles") as? JsonObject ?: return emptyMap()
        return profiles.mapNotNull { (name, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            val pts = (o["points"] as? JsonArray).orEmpty().mapNotNull { row ->
                (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isNotEmpty() }
            }
            if (pts.size < 2) return@mapNotNull null
            val p = o["mesh_params"] as? JsonObject
            fun d(k: String) = (p?.get(k) as? JsonPrimitive)?.doubleOrNull
            name to BedMesh(name, (d("min_x") ?: 0.0) to (d("min_y") ?: 0.0), (d("max_x") ?: 1.0) to (d("max_y") ?: 1.0), pts)
        }.toMap().toSortedMap()
    }

    private fun parseObjects(eo: JsonObject?): List<PrintObject> =
        (eo?.get("objects") as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj.s("name") ?: return@mapNotNull null
            val poly = (obj["polygon"] as? JsonArray).orEmpty().mapNotNull { xy(it) }
            PrintObject(name, xy(obj["center"]), poly)
        }

    private fun parseSystems(o: Map<String, JsonObject>): ShipSystems {
        val vars = o["save_variables"]?.get("variables") as? JsonObject
        fun prim(k: String) = vars?.get(k) as? JsonPrimitive
        fun bool(k: String): Boolean? = prim(k)?.let { p ->
            p.booleanOrNull ?: p.intOrNull?.let { it != 0 } ?: p.doubleOrNull?.let { it != 0.0 }
                ?: p.contentOrNull?.lowercase()?.let { it == "true" || it == "1" }
        }
        val boolVars = vars?.filter { (_, v) -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null }?.keys.orEmpty()
        val light = o.entries.firstOrNull { it.key.equals("output_pin caselight", true) }?.value
            ?: o.entries.firstOrNull { it.key.startsWith("output_pin ") && "light" in it.key.lowercase() }?.value
        return ShipSystems(
            caseLight = light.d("value"),
            noodleDetection = bool("enable_noodle_detection"),
            noodleSensitivity = prim("noodle_sensitivity_level")?.contentOrNull?.trim('\'', '"', ' ')?.takeIf { it.isNotBlank() },
            prePrintCheck = bool("enable_pre_print_model_check"),
            boolVars = boolVars,
        )
    }

    /**
     * QIDI Box, as the Q2C reports it: box_extras.boxes[0] holds the AHT20 sensor,
     * box_inserted_N slot flags and filament_info[N]; box_extras.loaded_slot is the
     * slot feeding the nozzle; save_variables keeps slotN and color_slotN.
     * Everything Box-related is also returned raw for the diagnostics screen.
     */
    private fun parseBox(o: Map<String, JsonObject>): Pair<BoxState?, Map<String, String>> {
        val boxObjects = o.filterKeys { it.startsWith("box") || it.startsWith("aht20") }
        val vars = o["save_variables"]?.get("variables") as? JsonObject
        val slotVars = vars?.filterKeys { k -> SLOT_KEY.containsMatchIn(k) || "box" in k }.orEmpty()
        if (boxObjects.isEmpty() && slotVars.isEmpty()) return null to emptyMap()

        val raw = sortedMapOf<String, String>()
        for ((name, obj) in boxObjects) for ((k, v) in obj) {
            if (v is JsonPrimitive) raw["$name.$k"] = v.content
        }
        val firstBox = ((o["box_extras"]?.get("boxes") as? JsonArray)?.firstOrNull() as? JsonObject)
        firstBox?.forEach { (k, v) -> if (v is JsonPrimitive) raw["boxes[0].$k"] = v.content }
        val filamentInfo = (firstBox?.get("filament_info") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        filamentInfo.forEachIndexed { i, fi ->
            raw["boxes[0].filament_info[$i]"] = listOfNotNull(fi.s("filament_type"), fi.s("filament_str"), firstColour(fi)).joinToString(" · ")
        }
        for ((k, v) in slotVars) if (v is JsonPrimitive) raw["variables.$k"] = v.content

        fun flag(p: JsonPrimitive?): Boolean? = p?.let {
            it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } ?: it.contentOrNull?.equals("true", true)
        }

        val slots = (0 until 4).map { i ->
            val info = filamentInfo.firstOrNull { it.i("index") == i } ?: filamentInfo.getOrNull(i)
            val material = filamentName(info)
            val colour = info?.let { firstColour(it) } ?: (vars?.get("color_slot$i") as? JsonPrimitive)?.contentOrNull
            val inserted = flag(firstBox?.get("box_inserted_$i") as? JsonPrimitive) ?: flag(vars?.get("slot$i") as? JsonPrimitive)
            fun num(k: String) = (vars?.get(k) as? JsonPrimitive)?.contentOrNull?.trim('\'', '"', ' ')?.toIntOrNull()
            val filamentNo = num("filament_slot$i")
            val id = filamentNo?.takeIf { it > 0 }?.let { "QD_2_${num("vendor_slot$i") ?: 1}_$it" }
            BoxSlot(i, inserted ?: (material != null), material, parseColor(colour), id)
        }
        val active = o["box_extras"].s("loaded_slot")?.let { LOADED_SLOT.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        // The Box dryer is a heater_generic (heater_box1 on the Q2C) with an AHT20 sensor.
        val boxHeater = o.entries.firstOrNull { it.key.startsWith("heater_generic ") && "box" in it.key }?.value
        val box = BoxState(
            slots = slots,
            temperature = firstBox.d("aht20_temp") ?: boxHeater.d("temperature"),
            humidity = firstBox.d("aht20_humidity") ?: boxHeater.d("humidity"),
            target = firstBox.d("box_target_temp") ?: boxHeater.d("target"),
            activeSlot = active?.takeIf { it in 0..3 },
            toolMap = vars.orEmpty().mapNotNull { (k, v) ->
                val tool = TOOL_VAR.find(k)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val slot = (v as? JsonPrimitive)?.contentOrNull?.let { LOADED_SLOT.find(it.trim('\'', '"'))?.groupValues?.get(1)?.toIntOrNull() }
                    ?: return@mapNotNull null
                tool to slot
            }.toMap(),
        )
        return box to raw
    }

    /**
     * Best name for a slot's filament: filament_str when it's a fuller name that includes
     * the type (e.g. "PLA Silk"), otherwise filament_type ("PLA").
     */
    private fun filamentName(info: JsonObject?): String? {
        val type = info?.s("filament_type")?.trim()?.takeIf { it.isNotBlank() }
        val str = info?.s("filament_str")?.trim()?.takeIf { it.isNotBlank() && !FILAMENT_CODE.matches(it) }
        return when {
            str != null && (type == null || str.contains(type, ignoreCase = true)) -> str
            else -> type ?: str
        }
    }

    /** filament_info colour is an array like ["#FF362D"]; take the first entry. */
    private fun firstColour(info: JsonObject): String? = when (val c = info["color_rgb_str"]) {
        is JsonArray -> (c.firstOrNull() as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> c.contentOrNull
        else -> null
    }

    private fun parseColor(text: String?): Long? {
        // Q2C stores colours as ['FF362D'] in save_variables.
        val hex = text?.trim()?.trim('[', ']', '\'', '"', ' ')?.removePrefix("#")
            ?.takeIf { it.length == 6 || it.length == 8 } ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) 0xFF000000L or v else v
    }

    private fun heater(objectName: String, heaterName: String?, label: String, obj: JsonObject) = Heater(
        objectName = objectName,
        heaterName = heaterName,
        label = label,
        temperature = obj.d("temperature") ?: 0.0,
        target = if (heaterName != null) obj.d("target") ?: 0.0 else null,
    )

    private fun pretty(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    // ---- Higher-level helpers --------------------------------------------

    suspend fun listFiles(): List<GcodeFile> {
        // The printer can be slow to answer while it reads a file that has just arrived.
        val result = call("server.files.list", buildJsonObject { put("root", "gcodes") }, timeoutMs = 30_000)
        return (result as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val path = f.s("path") ?: f.s("filename") ?: return@mapNotNull null
            if (path.startsWith(".")) return@mapNotNull null
            GcodeFile(path, f.d("modified") ?: 0.0, f["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.sortedByDescending { it.modified }
    }

    suspend fun metadata(path: String): FileMeta =
        parseMeta(path, call("server.files.metadata", buildJsonObject { put("filename", path) }).jsonObject)

    /** Asks Moonraker to read the file's metadata again now (e.g. if it was busy when the file arrived). */
    suspend fun metascan(path: String): FileMeta =
        parseMeta(path, call("server.files.metascan", buildJsonObject { put("filename", path) }, timeoutMs = 30_000).jsonObject)

    private fun parseMeta(path: String, m: JsonObject): FileMeta {
        val ep = endpoint ?: throw IOException("Not connected")
        val thumb = (m["thumbnails"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.maxByOrNull { it.d("width") ?: 0.0 }
            ?.s("relative_path")
        val dir = path.substringBeforeLast('/', "")
        val thumbUrl = thumb?.let { ep.gcodeFileUrl(if (dir.isEmpty()) it else "$dir/$it") }
        val types = m.s("filament_type")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val colours = when (val c = m["filament_colors"]) {
            is JsonArray -> c.map { (it as? JsonPrimitive)?.contentOrNull?.let(::parseColor) }
            is JsonPrimitive -> c.contentOrNull?.split(';')?.map { parseColor(it) }.orEmpty()
            else -> emptyList()
        }
        val tools = (m["referenced_tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        return FileMeta(
            thumbnailUrl = thumbUrl,
            filamentTypes = types,
            filamentColors = colours,
            tools = tools,
            estimatedSeconds = m.d("estimated_time"),
            filamentMm = m.d("filament_total"),
            filamentGrams = m.d("filament_weight_total"),
            layerHeight = m.d("layer_height"),
            objectHeight = m.d("object_height"),
            slicer = m.s("slicer"),
        )
    }

    /** Timelapse videos (moonraker-timelapse saves them in the "timelapse" root), newest first. */
    suspend fun listTimelapses(): List<TimelapseFile> {
        val result = call("server.files.list", buildJsonObject { put("root", "timelapse") })
        val all = (result as? JsonArray ?: JsonArray(emptyList())).mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val path = f.s("path") ?: f.s("filename") ?: return@mapNotNull null
            Triple(path, f.d("modified") ?: 0.0, f["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
        val images = all.map { it.first }.filter { p -> IMAGE_EXT.any { p.lowercase().endsWith(it) } }.toSet()
        return all.filter { (p, _, _) -> VIDEO_EXT.any { p.lowercase().endsWith(it) } }
            .map { (p, mod, size) ->
                val base = p.substringBeforeLast('.')
                val thumb = IMAGE_EXT.map { base + it }.firstOrNull { it in images }
                TimelapseFile(p, mod, size, thumb)
            }
            .sortedByDescending { it.modified }
    }

    suspend fun deleteTimelapse(path: String) {
        call("server.files.delete_file", buildJsonObject { put("path", "timelapse/$path") })
    }

    /** Print history, newest first. [since] is a Unix time in seconds, or null for all. */
    suspend fun history(since: Double?, pageSize: Int = 500, maxJobs: Int = 20_000): List<HistoryJob> {
        // Moonraker returns at most `limit` jobs per call, so page through with `start`.
        val all = ArrayList<JsonElement>()
        while (all.size < maxJobs) {
            val params = buildJsonObject {
                put("limit", pageSize)
                put("start", all.size)
                put("order", "desc")
                if (since != null) put("since", since)
            }
            val page = call("server.history.list", params).jsonObject["jobs"] as? JsonArray ?: break
            all.addAll(page)
            if (page.size < pageSize) break
        }
        return all.mapNotNull { el ->
            val j = el as? JsonObject ?: return@mapNotNull null
            val meta = j["metadata"] as? JsonObject
            HistoryJob(
                filename = j.s("filename") ?: "?",
                status = j.s("status") ?: "unknown",
                startTime = j.d("start_time") ?: 0.0,
                printDuration = j.d("print_duration") ?: 0.0,
                filamentUsedMm = j.d("filament_used") ?: 0.0,
                filamentType = meta.s("filament_type")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Moonraker keeps ~20 minutes of 1 Hz temperatures; returns (nozzle, bed) pairs. */
    suspend fun temperatureStore(): List<TempSample> {
        val store = call("server.temperature_store").jsonObject
        fun series(name: String) = ((store[name] as? JsonObject)?.get("temperatures") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.doubleOrNull?.toFloat() }.orEmpty()
        val noz = series("extruder")
        val bed = series("heater_bed")
        val n = maxOf(noz.size, bed.size)
        return (0 until n).map { i ->
            TempSample(noz.getOrElse(i - (n - noz.size)) { 0f }, bed.getOrElse(i - (n - bed.size)) { 0f })
        }
    }

    /** First enabled webcam's snapshot URL, or the usual QIDI/Fluidd path. */
    suspend fun webcamSnapshotUrl(): String? = webcam()?.snapshot

    /** First enabled webcam's snapshot and MJPEG stream URLs (QIDI/Fluidd defaults if none set). */
    suspend fun webcam(): Webcam? {
        val ep = endpoint ?: return null
        val cams = runCatching {
            (call("server.webcams.list").jsonObject["webcams"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        }.getOrNull().orEmpty()
        val cam = cams.firstOrNull { (it["enabled"] as? JsonPrimitive)?.contentOrNull != "false" }
        val snap = cam?.s("snapshot_url")?.takeIf { it.isNotBlank() }
        val stream = cam?.s("stream_url")?.takeIf { it.isNotBlank() }
        return Webcam(
            snapshot = ep.resolve(snap ?: "/webcam/?action=snapshot"),
            stream = ep.resolve(stream ?: "/webcam/?action=stream"),
        )
    }

    /** Klipper's own help text for each command, used as macro descriptions. */
    suspend fun gcodeHelp(): Map<String, String> = runCatching {
        call("printer.gcode.help").jsonObject.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { k.uppercase() to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        private val SLOT_KEY = Regex("slot_?[0-3]")
        private val LOADED_SLOT = Regex("""^slot(\d+)$""")
        private val TOOL_VAR = Regex("""^value_t(\d+)$""")
        private val VIDEO_EXT = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov")
        private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png")
        /** QIDI filament codes like QFA00 aren't display names. */
        private val FILAMENT_CODE = Regex("^Q?[A-Z]{1,3}\\d{2,}$")
        val BASE_OBJECTS = setOf(
            "print_stats", "display_status", "virtual_sdcard", "extruder", "heater_bed",
            "fan", "gcode_move", "toolhead", "webhooks", "idle_timeout", "exclude_object", "bed_mesh",
        )
    }
}

private fun JsonObject?.d(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
private fun JsonObject?.i(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject?.s(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
