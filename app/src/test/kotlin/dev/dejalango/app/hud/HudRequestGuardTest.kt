package dev.dejalango.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which requests the HUD server answers. Header names are lower case, as NanoHTTPD stores them. */
class HudRequestGuardTest {

    private fun refusal(vararg headers: Pair<String, String>, path: String = "/state", method: String = "GET"): String? =
        HudRequestGuard.refusal(method, path, mapOf(*headers))

    /** A WebSocket handshake's headers other than Host, Origin and Sec-Fetch-Site. */
    private val upgrade = arrayOf(
        "upgrade" to "websocket", "connection" to "Upgrade", "sec-websocket-version" to "13", "sec-websocket-key" to "dGhlIHNhbXBsZSBub25jZQ==",
    )

    @Test
    fun `localhost and IP literal hosts pass on any port`() {
        assertNull(refusal("host" to "localhost:8765", "origin" to "http://localhost:8765"))
        assertNull(refusal("host" to "127.0.0.1:9000", "origin" to "http://127.0.0.1:9000")) // scripts/hud_forward.sh 9000
        assertNull(refusal("host" to "[::1]:8765"))
        assertNull(refusal("host" to "192.168.1.5:8765")) // the LAN setting
    }

    @Test
    fun `a client that sends only Host passes, as curl does`() {
        assertNull(refusal("host" to "localhost:8765"))
        assertNull(refusal("host" to "localhost:8765", path = "/action", method = "POST"))
        assertNull(refusal(*upgrade, "host" to "localhost:8765", path = "/ws"))
    }

    @Test
    fun `GET action from localhost is refused`() {
        assertEquals("/action not POST", refusal("host" to "localhost:8765", path = "/action"))
    }

    @Test
    fun `our pages pass, in a tab and when opened from another site`() {
        // hud.html and sim.html open ws://<location.host>/ws.
        val ownPage = arrayOf("host" to "localhost:8765", "origin" to "http://localhost:8765", "sec-fetch-site" to "same-origin")
        assertNull(refusal(*upgrade, *ownPage, path = "/ws"))
        // A link on another site opens /hud: a cross-site navigation, without an Origin, and not an upgrade.
        assertNull(refusal("host" to "localhost:8765", "sec-fetch-site" to "cross-site", "upgrade-insecure-requests" to "1", path = "/hud"))
        // A POST to /action marked same-origin or none passes.
        assertNull(refusal("host" to "localhost:8765", "sec-fetch-site" to "same-origin", path = "/action", method = "POST"))
        assertNull(refusal("host" to "localhost:8765", "sec-fetch-site" to "none", path = "/action", method = "POST"))
    }

    @Test
    fun `a DNS name in Host is refused, since DNS rebinding needs one`() {
        for (host in listOf("evil.example:8765", "localhost.evil.example:8765", "127.0.0.1.nip.io:8765", "app.localhost:8765")) {
            assertEquals(host, "Host not localhost or an IP", refusal("host" to host))
        }
        // Nor does anything else that is not localhost or an IP literal with an optional port.
        for (host in listOf("", "256.0.0.1:8765", "::1:8765", "[::1", "[evil.example]:8765", "[1::2::3]:8765", "localhost:http")) {
            assertEquals(host, "Host not localhost or an IP", refusal("host" to host))
        }
        assertEquals("no Host", refusal())
    }

    @Test
    fun `an Origin other than the server itself is refused`() {
        // Another site, a sandboxed or file page, another port, https, and another name for this host.
        for (origin in listOf("https://evil.example", "null", "http://localhost:8765", "https://localhost:9000", "http://127.0.0.1:9000")) {
            assertEquals(origin, "Origin mismatch", refusal("host" to "localhost:9000", "origin" to origin))
        }
        // Browsers apply no CORS to WebSockets, but another site's handshake carries that site's Origin.
        val otherSite = arrayOf("host" to "localhost:8765", "origin" to "https://evil.example", "sec-fetch-site" to "cross-site")
        assertEquals("Origin mismatch", refusal(*upgrade, *otherSite, path = "/ws"))
    }

    @Test
    fun `a cross-site POST to action is refused by Origin`() {
        assertEquals(
            "Origin mismatch",
            refusal("host" to "localhost:8765", "origin" to "https://evil.example", path = "/action", method = "POST"),
        )
    }

    @Test
    fun `an omitted default port and letter case still match`() {
        assertNull(refusal("host" to "localhost", "origin" to "http://localhost"))
        assertNull(refusal("host" to "localhost:80", "origin" to "http://localhost"))
        assertNull(refusal("host" to "localhost", "origin" to "http://localhost:80"))
        assertNull(refusal("host" to "LocalHost:8765", "origin" to "HTTP://localhost:8765"))
        assertNull(refusal("host" to "[FE80::1]:8765", "origin" to "http://[fe80::1]:8765"))
    }

    @Test
    fun `another site can neither fire an action nor open the socket`() {
        for (site in listOf("cross-site", "same-site")) {
            // A request without Origin still fails Fetch Metadata.
            val fromSite = arrayOf("host" to "localhost:8765", "sec-fetch-site" to site)
            assertEquals(site, "Sec-Fetch-Site not same-origin", refusal(*fromSite, path = "/action", method = "POST"))
            assertEquals(site, "Sec-Fetch-Site not same-origin", refusal(*upgrade, *fromSite, path = "/ws"))
            // NanoWSD upgrades a handshake on any path.
            assertEquals(site, "Sec-Fetch-Site not same-origin", refusal(*upgrade, *fromSite, path = "/hud"))
        }
    }

    @Test
    fun `an action needs a loopback Host, since browsers send a LAN address no Fetch Metadata`() {
        // A POST with a LAN Host is refused even without an Origin.
        for (host in listOf("192.168.1.5:8765", "[fe80::1]:8765")) {
            assertEquals(host, "Host not loopback", refusal("host" to host, path = "/action", method = "POST"))
        }
        assertEquals(
            "Host not loopback",
            refusal("host" to "192.168.1.5:8765", "origin" to "http://192.168.1.5:8765", path = "/action", method = "POST"),
        )
        // Curl through the forwarded port, or on the phone.
        for (host in listOf("localhost:8765", "127.0.0.1:9000", "[::1]:8765")) {
            assertNull(host, refusal("host" to host, path = "/action", method = "POST"))
        }
        // Our pages send actions over the socket, whose handshake always carries an Origin.
        assertNull(refusal(*upgrade, "host" to "192.168.1.5:8765", "origin" to "http://192.168.1.5:8765", path = "/ws"))
    }

    @Test
    fun `another site's GET of state passes only with a local Host`() {
        // The reply carries no CORS header, so the browser keeps it from that site.
        assertNull(refusal("host" to "localhost:8765", "sec-fetch-site" to "cross-site"))
        assertEquals("Host not localhost or an IP", refusal("host" to "evil.example:8765", "sec-fetch-site" to "cross-site"))
    }
}
