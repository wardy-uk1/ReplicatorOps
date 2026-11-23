package dev.vantara.replicatorops.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLEncoder

/**
 * Where the printer's Moonraker lives.
 *
 * The user types a host such as `192.168.1.50`, `qidi-q2.tail985254.ts.net`
 * or `192.168.1.50:7125`, and IPv6 such as `fd7a:115c:a1e0::1` or `[fd7a:115c:a1e0::1]:7125`.
 * With no port we use port 80, where QIDI's nginx
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
    fun gcodeFileUrl(relativePath: String): String = fileUrl("gcodes", relativePath)

    /** URL for a file under any Moonraker root (gcodes, timelapse, …). */
    fun fileUrl(root: String, relativePath: String): String =
        baseUrl + "/server/files/" + root + "/" + encodePath(relativePath)

    companion object {
        /**
         * Accepts host names, IPv4 and IPv6, with or without a scheme or port:
         * `192.168.1.50`, `printer.local:7125`, `fd7a:115c:a1e0::1`, `[fd7a:115c:a1e0::1]:7125`,
         * `http://[2a00:23c7::5]`. A bare IPv6 address (no brackets) is taken as having no port.
         */
        fun parse(input: String, apiKey: String?): Endpoint? {
            val url = normalise(input) ?: return null
            return Endpoint(url, apiKey?.trim()?.takeIf { it.isNotEmpty() })
        }

        /** Why an address can't be used, for the Config screen; null if it's fine. */
        fun problem(input: String): String? {
            val t = input.trim()
            if (t.isEmpty()) return "Enter the printer's address"
            if (t.contains('%') || t.lowercase().removePrefix("[").startsWith("fe80:")) {
                return "Link-local IPv6 (fe80::) can't be used. Use the printer's IPv4, its Tailscale or home IPv6 address, or its name."
            }
            return if (normalise(t) == null) "That doesn't look like a valid address" else null
        }

        private fun normalise(input: String): String? {
            var rest = input.trim().trimEnd('/')
            if (rest.isEmpty()) return null
            var scheme = "http"
            if (rest.contains("://")) {
                scheme = rest.substringBefore("://").lowercase()
                rest = rest.substringAfter("://")
                if (scheme != "http" && scheme != "https") return null
            }
            val authority = rest.substringBefore('/')
            val path = rest.substring(authority.length).trimEnd('/')
            val hostPort = when {
                authority.startsWith("[") -> authority.takeIf { "]" in it }
                authority.count { it == ':' } >= 2 -> "[$authority]" // bare IPv6, no port
                else -> authority
            } ?: return null
            if ('%' in hostPort) return null // zone IDs (link-local) aren't supported by OkHttp
            val url = "$scheme://$hostPort$path"
            return url.takeIf { it.toHttpUrlOrNull() != null }
        }

        fun encodePath(path: String): String =
            path.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
    }
}
