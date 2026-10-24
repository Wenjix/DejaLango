package dev.dejalango.app

import dev.dejalango.app.hud.HudAccess
import dev.dejalango.app.hud.HudJson
import dev.dejalango.engine.hud.HudAction
import org.junit.Assert.*
import org.junit.Test

class HudAccessTest {
    @Test fun `fresh runtime capability rejects empty and old tokens`() {
        val first = HudAccess(); val second = HudAccess()
        assertEquals(43, first.token.length)
        assertTrue(first.accepts(first.token))
        assertFalse(first.accepts(null)); assertFalse(first.accepts(""))
        assertFalse(second.accepts(first.token))
        assertFalse(first.accepts(first.token.dropLast(1) + if (first.token.last() == 'a') "b" else "a"))
        assertTrue(first.acceptsOrigin("http://localhost:8765", "localhost:8765"))
        assertFalse(first.acceptsOrigin("https://elsewhere.test", "localhost:8765"))
    }

    @Test fun `actions require current session and cannot invoke legacy Use mode or operator Cut`() {
        assertNull(HudJson.decodeAction("""{"action":"talk"}""", "now"))
        assertNull(HudJson.decodeAction("""{"action":"talk","session":"old"}""", "now"))
        assertEquals(HudAction.Talk, HudJson.decodeAction("""{"action":"talk","session":"now"}""", "now"))
        assertEquals(HudAction.Done, HudJson.decodeAction("""{"action":"cut","session":"now"}""", "now"))
        for (action in listOf("confirm", "mode", "operatorCut", "clear")) assertNull(HudJson.decodeAction("""{"action":"$action","session":"now"}""", "now"))
    }
}
