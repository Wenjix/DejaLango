package dev.dejalango.app.hud

import dev.dejalango.engine.hud.HudAction
import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the HUD server answers request bodies and /action's query string. Header names are lower case, as NanoHTTPD stores them. */
class HudServerTest {

    private fun bodyRefusal(vararg headers: Pair<String, String>): Response? =
        HudServer.bodyRefusal(mapOf("host" to "localhost:8765", *headers))

    private val fired = mutableListOf<HudAction>()

    private fun action(vararg parameters: Pair<String, String>): Response =
        HudServer.serveAction(parameters.associate { (k, v) -> k to listOf(v) }) { fired += it }

    @Test
    fun `a request with a body gets 400 and closes its connection, since NanoHTTPD would read the body as the next request`() {
        // curl -d x=1, a chunked upload, and a length that can't show where the next request starts.
        for (body in listOf("content-length" to "3", "transfer-encoding" to "chunked", "content-length" to "x")) {
            val r = bodyRefusal(body)
            assertEquals(body.toString(), Response.Status.BAD_REQUEST, r?.status)
            assertTrue(body.toString(), r!!.isCloseConnection)
        }
    }

    @Test
    fun `a request without a body goes on to the guard`() {
        assertNull(bodyRefusal()) // curl -X POST
        assertNull(bodyRefusal("content-length" to "0"))
    }

    @Test
    fun `an action in the query string fires`() {
        // scripts/hud_forward.sh's hint, ?a=%7B%22action%22%3A%22next%22%7D, as NanoHTTPD decodes it.
        assertEquals(Response.Status.OK, action("a" to """{"action":"next"}""").status)
        assertEquals(listOf(HudAction.Next), fired)
    }

    @Test
    fun `a missing or unreadable action gets 400 and fires nothing`() {
        assertEquals(Response.Status.BAD_REQUEST, action().status)
        assertEquals(Response.Status.BAD_REQUEST, action("x" to "1").status)
        assertEquals(Response.Status.BAD_REQUEST, action("a" to "next").status)
        assertEquals(Response.Status.BAD_REQUEST, action("a" to """{"action":"dance"}""").status)
        assertEquals(emptyList<HudAction>(), fired)
    }
}
