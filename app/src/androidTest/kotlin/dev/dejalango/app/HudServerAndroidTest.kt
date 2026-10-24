package dev.dejalango.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.app.hud.HudServer
import dev.dejalango.engine.hud.HudState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket

class HudServerAndroidTest {
    @Test fun capabilityIsRequiredBeforeStateActionsAndWebsocketUpgrade() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = HudServer(InstrumentationRegistry.getInstrumentation().targetContext.assets, "127.0.0.1", 0,
            MutableStateFlow(HudState(sessionId = "session")), {}, scope)
        try {
            server.startServer()
            val base = server.url.substringBefore("/hud")
            val token = server.url.substringAfter("token=")
            fun status(path: String): Int = (URL(base + path).openConnection() as HttpURLConnection).let { c -> try { c.responseCode } finally { c.disconnect() } }
            assertEquals(403, status("/state"))
            assertEquals(403, status("/action"))
            assertEquals(403, status("/ws"))
            assertEquals(200, status("/state?token=$token"))
            assertEquals(405, status("/action?token=$token"))
            for (query in listOf("", "?token=$token")) {
                Socket("127.0.0.1", server.listeningPort).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write(("GET /ws$query HTTP/1.1\r\nHost: localhost:${server.listeningPort}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray())
                    val response = socket.getInputStream().bufferedReader().readLine()
                    assertTrue(response, response.contains(if (query.isEmpty()) "403" else "101"))
                }
            }
        } finally { server.stop(); scope.cancel() }
    }
}
