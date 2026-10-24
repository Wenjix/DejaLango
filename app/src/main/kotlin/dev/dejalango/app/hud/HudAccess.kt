package dev.dejalango.app.hud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A fresh capability for each runtime; it is never persisted, included in state, or logged. */
class HudAccess {
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun accepts(candidate: String?): Boolean = candidate != null && candidate.length == token.length &&
        MessageDigest.isEqual(token.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))

    fun acceptsOrigin(origin: String?, host: String?): Boolean = origin == null ||
        host != null && (origin == "http://$host" || origin == "https://$host")
}
