package dev.dejalango.app.hud

import android.content.res.AssetManager
import android.util.Log
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoWSD
import fi.iki.elonen.NanoWSD.WebSocket
import fi.iki.elonen.NanoWSD.WebSocketFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Serves the web HUD (assets/hud/hud.html) and streams [HudState] over a WebSocket.
 *
 * Why a web HUD: the glasses simulator (/sim) is a laptop browser page that shows a 600×600 additive viewport
 * over the camera, and the Android XR glasses emulator pairs with a phone AVD rather than a real phone — both
 * want a thin client. `adb forward tcp:PORT tcp:PORT` makes this server reachable as http://localhost:PORT/sim
 * on the laptop (localhost is a secure context, so getUserMedia works). The server binds to 127.0.0.1 unless
 * hudOnLan is set in settings.
 *
 * It answers only its own pages and local clients: every request, WebSocket handshakes included, must pass
 * [HudRequestGuard], and no response carries a CORS header, so other websites can neither read the
 * conversation nor, in current browsers, send actions.
 *
 * Routes: /sim (the glasses simulator: the HUD screen-blended over the laptop camera inside a glasses bezel),
 * /hud (the raw 600×600 HUD page, which /sim shows in an iframe), /state (JSON snapshot),
 * POST /action?a=<json> (one-shot action), /ws (WebSocket: state out, actions in).
 */
class HudServer(
    private val assets: AssetManager,
    hostname: String?,
    port: Int,
    private val hud: StateFlow<HudState>,
    private val onAction: (HudAction) -> Unit,
    private val scope: CoroutineScope,
) : NanoWSD(hostname, port) {

    val url: String get() = "http://localhost:$listeningPort/hud"

    fun startServer() {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "HUD server at $url")
    }

    /** NanoWSD's serve answers WebSocket handshakes and passes the rest to [serveHttp]; the body check and the guard go first. */
    override fun serve(session: IHTTPSession): Response {
        bodyRefusal(session.headers)?.let { return it }
        val refusal = HudRequestGuard.refusal(session.method.name, session.uri, session.headers)
        if (refusal == null) return super.serve(session)
        // The decoded path can come from a hostile page: keep the warning to one short printable line.
        Log.w(TAG, "refused ${session.uri.filter { it in ' '..'~' }.take(80)}: $refusal")
        return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val r = when (session.uri) {
            "/", "/hud", "/hud.html", "/index.html" -> asset("hud/hud.html", "text/html; charset=utf-8")
            "/sim", "/sim.html" -> asset("hud/sim.html", "text/html; charset=utf-8")
            "/state" -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", HudJson.encode(hud.value))
            "/action" -> serveAction(session.parameters, onAction)
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun asset(path: String, mime: String): Response = try {
        val bytes = assets.open(path).use { it.readBytes() }
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
    } catch (e: IOException) {
        NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing asset $path")
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = HudSocket(handshake)

    private inner class HudSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var job: Job? = null

        override fun onOpen() {
            job = scope.launch {
                launch {
                    while (isActive) {
                        delay(PING_MS)
                        runCatching { ping(ByteArray(0)) }
                    }
                }
                hud.collect { s ->
                    try {
                        send(HudJson.encode(s))
                    } catch (e: IOException) {
                        Log.d(TAG, "send failed: ${e.message}")
                    }
                }
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            job?.cancel()
            job = null
        }

        override fun onMessage(message: WebSocketFrame) {
            HudJson.decodeAction(message.textPayload)?.let(onAction)
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.d(TAG, "ws exception: ${exception?.message}")
            job?.cancel()
        }
    }

    companion object {
        private const val TAG = "HudServer"
        private const val PING_MS = 3_000L

        /**
         * A 400 that closes the connection, for a request that carries a body (any Transfer-Encoding, or a
         * Content-Length other than 0): no route reads a body, and NanoHTTPD would read an unread one as the
         * connection's next request. Null for a request without one. Header names are lower case, as NanoHTTPD
         * stores them.
         */
        internal fun bodyRefusal(headers: Map<String, String>): Response? {
            // A Content-Length that doesn't parse counts as a body: it can't show where the next request starts.
            val body = "transfer-encoding" in headers || (headers["content-length"] ?: "0").toLongOrNull() != 0L
            if (!body) return null
            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no body expected").apply { closeConnection(true) }
        }

        /** POST /action takes its action in the query string, `a=<json>`; a missing or unreadable one gets 400. */
        internal fun serveAction(parameters: Map<String, List<String>>, onAction: (HudAction) -> Unit): Response {
            val action = parameters["a"]?.firstOrNull()?.let { HudJson.decodeAction(it) }
                ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad action")
            onAction(action)
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }
    }
}
