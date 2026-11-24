package dev.vantara.replicatorops.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.security.MessageDigest

/**
 * Preview picture and details of each file the app sliced and sent, kept on the phone, so
 * Files can show them even when the printer can't read them from the G-code itself.
 * Keyed by the file's path on the printer.
 */
class SlicedMetaStore(private val dir: File) {

    private fun key(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** Saves [meta] for [path], copying a local preview picture into the store. Returns what was saved. */
    fun save(path: String, meta: FileMeta): FileMeta? = runCatching {
        dir.mkdirs()
        val k = key(path)
        val png = File(dir, "$k.png")
        val thumb = meta.thumbnailUrl?.takeIf { it.startsWith("file://") }?.let { File(it.removePrefix("file://")) }
        val saved = if (thumb != null && thumb.exists()) {
            thumb.copyTo(png, overwrite = true)
            meta.copy(thumbnailUrl = "file://" + png.absolutePath)
        } else {
            png.delete()
            meta
        }
        File(dir, "$k.json").writeText(buildJsonObject {
            put("path", path)
            saved.thumbnailUrl?.let { put("thumbnail", it) }
            saved.estimatedSeconds?.let { put("seconds", it) }
            saved.filamentMm?.let { put("filamentMm", it) }
            saved.layerHeight?.let { put("layerHeight", it) }
            saved.objectHeight?.let { put("objectHeight", it) }
            saved.slicer?.let { put("slicer", it) }
            putJsonArray("filaments") { saved.filamentTypes.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("colours") { saved.filamentColors.forEach { add(JsonPrimitive(it ?: -1L)) } }
            putJsonArray("tools") { saved.tools.forEach { add(JsonPrimitive(it)) } }
        }.toString())
        saved
    }.getOrNull()

    fun read(path: String): FileMeta? = runCatching {
        val f = File(dir, key(path) + ".json")
        if (!f.exists()) return null
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        if (o["path"]?.jsonPrimitive?.contentOrNull != path) return null
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        FileMeta(
            thumbnailUrl = (o["thumbnail"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { File(it.removePrefix("file://")).exists() },
            filamentTypes = (o["filaments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            filamentColors = (o["colours"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { c -> c >= 0 } }.orEmpty(),
            tools = (o["tools"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty(),
            estimatedSeconds = d("seconds"),
            filamentMm = d("filamentMm"),
            filamentGrams = null,
            layerHeight = d("layerHeight"),
            objectHeight = d("objectHeight"),
            slicer = (o["slicer"] as? JsonPrimitive)?.contentOrNull,
        )
    }.getOrNull()
}
