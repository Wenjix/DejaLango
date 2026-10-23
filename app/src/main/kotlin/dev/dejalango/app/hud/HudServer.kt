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
 * Why a web HUD: the Meta Ray-Ban Display simulator at hud.xyz is a browser page that loads a URL into a
 * 600×600 additive viewport with D-pad input, and the Android XR glasses emulator pairs with a phone AVD
 * rather than a real phone — both want a thin client. `adb forward tcp:PORT tcp:PORT` makes this server
 * reachable as http://localhost:PORT/hud on the laptop (localhost is a secure context, so https pages may
 * embed it). The server binds to 127.0.0.1 unless hudOnLan is set in settings.
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

    override fun serveHttp(session: IHTTPSession): Response {
        val r = when (session.uri) {
            "/", "/hud", "/hud.html", "/index.html" -> asset("hud/hud.html", "text/html; charset=utf-8")
            "/state" -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", HudJson.encode(hud.value))
            "/action" -> {
                session.parameters["a"]?.firstOrNull()?.let { HudJson.decodeAction(it) }?.let(onAction)
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            }
            else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        r.addHeader("Access-Control-Allow-Origin", "*")
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

    private companion object {
        const val TAG = "HudServer"
        const val PING_MS = 3_000L
    }
}
