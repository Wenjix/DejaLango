package dev.dejalango.app.hud

/**
 * Which requests [HudServer] answers: its own pages and local clients such as curl, not other websites acting
 * through the viewer's browser. Pure, so the rules run on the JVM. Header names are lower case, as NanoHTTPD
 * stores them.
 *
 * - Host must be `localhost` or an IP literal, with any port. The laptop's forwarded port and the phone's LAN
 *   address pass; a DNS name, which a DNS-rebinding page needs to pose as this server, does not.
 * - Origin, when present, must be `http://` + Host. Browsers send it with every WebSocket handshake, which CORS
 *   does not cover. Curl sends none, and neither does another site's plain GET, whose response that site cannot
 *   read without a CORS header.
 * - `/action` accepts POST only: an `<img>` or link can only GET, and a cross-site POST carries an Origin that
 *   must match the server.
 * - `/action` needs a loopback Host: `localhost`, `127.0.0.1` or `[::1]`. Over plain http, browsers send Fetch
 *   Metadata only to loopback, so another site's GET of the phone's LAN address carries neither Origin nor
 *   Sec-Fetch-Site. Our pages send actions over the WebSocket, whose handshake always carries Origin.
 * - Sec-Fetch-Site, when present on `/action` or a WebSocket upgrade, must be `same-origin` or `none` (a typed
 *   URL). Current browsers send Fetch Metadata to loopback and mark another site's
 *   `<img src=".../action?a=...">` `cross-site`, so it fires no action.
 */
object HudRequestGuard {

    private val loopbackHosts = setOf("localhost", "127.0.0.1", "[::1]")

    /** Null when the request may be served, else a short reason to log. [path] excludes the query string. */
    fun refusal(method: String, path: String, headers: Map<String, String>): String? {
        if (path == "/action" && !method.equals("POST", ignoreCase = true)) return "/action not POST"
        val host = headers["host"] ?: return "no Host"
        val server = authority(host) ?: return "Host not localhost or an IP"
        val origin = headers["origin"]
        if (origin != null && originAuthority(origin) != server) return "Origin mismatch"
        if (path == "/action" && server.host !in loopbackHosts) return "Host not loopback"
        // Any Upgrade header counts, so every handshake NanoWSD would accept is covered.
        if (path == "/action" || "upgrade" in headers) {
            val site = headers["sec-fetch-site"]?.lowercase()
            if (site != null && site != "same-origin" && site != "none") return "Sec-Fetch-Site not same-origin"
        }
        return null
    }

    private data class Authority(val host: String, val port: Int)

    /** An origin as browsers send it, `http://host[:port]`, read like a Host header. */
    private fun originAuthority(origin: String): Authority? =
        if (origin.startsWith("http://", ignoreCase = true)) authority(origin.substring("http://".length)) else null

    /** Host (lower case) and port of `host[:port]` when the host is `localhost` or an IP literal. No port means 80. */
    private fun authority(s: String): Authority? {
        val hostEnd = if (s.startsWith('[')) s.indexOf(']') + 1 else s.indexOf(':').let { if (it < 0) s.length else it }
        val host = s.substring(0, hostEnd).lowercase()
        val rest = s.substring(hostEnd)
        val port = when {
            rest.isEmpty() -> 80
            rest.length in 2..6 && rest[0] == ':' && rest.drop(1).all { it in '0'..'9' } -> rest.drop(1).toInt()
            else -> return null
        }
        return if (isLocalhostOrIp(host)) Authority(host, port) else null
    }

    private fun isLocalhostOrIp(host: String): Boolean =
        host == "localhost" || isIpv4(host) ||
            (host.startsWith('[') && host.endsWith(']') && isIpv6(host.substring(1, host.length - 1)))

    private fun isIpv4(s: String): Boolean = s.split('.').let { parts ->
        parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
    }

    /** Lower-case hex groups as browsers write them: eight, or at most seven around one `::`. */
    private fun isIpv6(s: String): Boolean {
        val halves = s.split("::")
        val groups = halves.filter { it.isNotEmpty() }.flatMap { it.split(':') }
        val hex = groups.all { g -> g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' } }
        return hex && when (halves.size) {
            1 -> groups.size == 8
            2 -> groups.size <= 7
            else -> false
        }
    }
}
