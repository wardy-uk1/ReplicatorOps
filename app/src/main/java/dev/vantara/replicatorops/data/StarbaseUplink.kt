package dev.vantara.replicatorops.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A newer build published on GitHub Releases. */
data class Upgrade(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val pageUrl: String,
)

/**
 * Starbase Uplink: checks GitHub Releases for a newer Replicator Ops build,
 * downloads the APK and hands it to Android's package installer.
 */
class StarbaseUplink(private val context: Context, private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the newer release, or null if this build is current. */
    suspend fun check(currentVersion: String): Upgrade? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ReplicatorOps/$currentVersion")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Starbase returned ${r.code}")
            r.body?.string() ?: throw IOException("Empty reply from Starbase")
        }
        val rel = json.parseToJsonElement(body).jsonObject
        val tag = rel.str("tag_name") ?: return@withContext null
        val version = tag.removePrefix("v")
        if (compareVersions(version, currentVersion) <= 0) return@withContext null
        val asset = (rel["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("name")?.endsWith(".apk") == true }
            ?: return@withContext null
        Upgrade(
            version = version,
            notes = rel.str("body").orEmpty().trim(),
            apkUrl = asset.str("browser_download_url") ?: return@withContext null,
            sizeBytes = (asset["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            pageUrl = rel.str("html_url") ?: RELEASES_PAGE,
        )
    }

    /** Downloads the APK into the app cache, reporting progress 0..1. */
    suspend fun download(upgrade: Upgrade, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "ReplicatorOps-${upgrade.version}.apk")
        val req = Request.Builder().url(upgrade.apkUrl).header("User-Agent", "ReplicatorOps").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
            val src = r.body ?: throw IOException("Empty download")
            val total = src.contentLength().takeIf { it > 0 } ?: upgrade.sizeBytes
            src.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        out
    }

    /** True if Android lets this app open the installer (the "install unknown apps" switch). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows installs from this app. */
    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the downloaded APK to Android's package installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openReleasePage(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/wardy-uk1/replicatorops/releases/latest"
        const val RELEASES_PAGE = "https://github.com/wardy-uk1/replicatorops/releases"

        /** Compares dotted versions like 0.10.2 vs 0.9.0; anything after '-' is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
