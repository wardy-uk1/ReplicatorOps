package dev.vantara.replicatorops.data

import java.net.URI
import java.net.URLEncoder

/**
 * Where the printer's Moonraker lives.
 *
 * The user types a host such as `192.168.1.50`, `qidi-q2.tail985254.ts.net`
 * or `192.168.1.50:7125`. With no port we use port 80, where QIDI's nginx
 * forwards `/websocket` and `/server/...` to Moonraker (same as Fluidd).
 */
data class Endpoint(val baseUrl: String, val apiKey: String?) {

    val wsUrl: String get() = baseUrl.replaceFirst("http", "ws") + "/websocket"

    val host: String get() = runCatching { URI(baseUrl).host }.getOrNull() ?: ""

    /** Absolute URL for a Moonraker/webcam path, which may already be absolute. */
    fun resolve(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            // Webcam configs often say localhost/127.0.0.1, which means "the printer".
            return pathOrUrl
                .replace("://localhost", "://$host")
                .replace("://127.0.0.1", "://$host")
        }
        return baseUrl + (if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl")
    }

    /** URL for a file under the gcodes root, with each path segment encoded. */
    fun gcodeFileUrl(relativePath: String): String =
        baseUrl + "/server/files/gcodes/" + encodePath(relativePath)

    companion object {
        fun parse(input: String, apiKey: String?): Endpoint? {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            if (!(s.startsWith("http://") || s.startsWith("https://"))) return null
            return Endpoint(s, apiKey?.trim()?.takeIf { it.isNotEmpty() })
        }

        fun encodePath(path: String): String =
            path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
    }
}
