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
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the local glasses simulator and its transparent 600 × 600 HUD. The request guard checks
 * Host, Origin, method and Fetch Metadata before HTTP or WebSocket handling. State, actions and
 * upgrades also require the current runtime's capability; actions bind to its session ID.
 * POST /action takes a JSON action in query parameter `a`, with no request body.
 */
class HudServer(
    private val assets: AssetManager,
    hostname: String?,
    port: Int,
    private val hud: StateFlow<HudState>,
    private val onAction: (HudAction) -> Unit,
    private val scope: CoroutineScope,
) : NanoWSD(hostname, port) {
    private val access = HudAccess()
    private val sockets = ConcurrentHashMap.newKeySet<WebSocket>()

    val url: String get() = "http://localhost:$listeningPort/hud?token=${access.token}"

    fun startServer() {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "HUD server started on port $listeningPort")
    }

    override fun serve(session: IHTTPSession): Response {
        bodyRefusal(session.headers)?.let { return it }
        HudRequestGuard.refusal(session.method.name, session.uri, session.headers)?.let { refusal ->
            Log.w(TAG, "HUD request refused: $refusal")
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden")
        }
        val protected = session.uri in setOf("/state", "/action", "/ws") || isWebsocketRequested(session)
        if (protected && (!access.accepts(session.parameters["token"]?.firstOrNull()) ||
                !access.acceptsOrigin(session.headers["origin"], session.headers["host"]))) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Open the current HUD link from the phone.")
        }
        if (isWebsocketRequested(session) && session.uri != "/ws") return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        return super.serve(session).also {
            it.addHeader("Cache-Control", "no-store")
            it.addHeader("Referrer-Policy", "no-referrer")
            it.addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val r = when (session.uri) {
            "/", "/hud", "/hud.html", "/index.html" -> asset("hud/hud.html", "text/html; charset=utf-8")
            "/sim", "/sim.html" -> asset("hud/sim.html", "text/html; charset=utf-8")
            "/hud.js" -> asset("hud/hud.js", "text/javascript; charset=utf-8")
            "/cafe.webp" -> asset("hud/cafe.webp", "image/webp")
            "/state" -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", HudJson.encode(hud.value))
            "/action" -> serveAction(session.parameters, hud.value.sessionId, onAction)
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
            sockets.add(this)
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
            sockets.remove(this)
            job = null
        }

        override fun onMessage(message: WebSocketFrame) {
            HudJson.decodeAction(message.textPayload, hud.value.sessionId)?.let(onAction)
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.d(TAG, "ws exception: ${exception?.message}")
            job?.cancel()
        }
    }

    override fun stop() {
        sockets.toList().forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "Session ended", false) } }
        sockets.clear()
        super.stop()
    }

    companion object {
        private const val TAG = "HudServer"
        private const val PING_MS = 3_000L

        internal fun bodyRefusal(headers: Map<String, String>): Response? {
            val body = "transfer-encoding" in headers || (headers["content-length"] ?: "0").toLongOrNull() != 0L
            if (!body) return null
            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "no body expected")
                .apply { closeConnection(true) }
        }

        internal fun serveAction(parameters: Map<String, List<String>>, currentSession: String, onAction: (HudAction) -> Unit): Response {
            val action = parameters["a"]?.firstOrNull()?.let { HudJson.decodeAction(it, currentSession) }
                ?: return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid or stale action")
            onAction(action)
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        }
    }
}
