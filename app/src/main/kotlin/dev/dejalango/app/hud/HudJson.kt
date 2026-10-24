package dev.dejalango.app.hud

import dev.dejalango.engine.config.Mode
import dev.dejalango.engine.hud.HudAction
import dev.dejalango.engine.hud.HudState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wire format between the pipeline and any web renderer (/sim and /hud in a laptop browser; later, an
 * Android XR thin client). One JSON object per state change; actions come back as small objects.
 */
object HudJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(s: HudState): String = buildJsonObject {
        put("phase", s.phase.name)
        put("mode", s.mode.name)
        put("level", s.level)
        put("heard", s.heard?.let { h -> buildJsonObject { put("text", h.text); put("lang", h.lang); put("translation", h.translation ?: "") } } ?: JsonNull)
        put("replies", buildJsonArray {
            s.replies.forEach { r ->
                add(buildJsonObject { put("plain", r.plain); put("yours", r.yours); put("roman", r.roman ?: ""); put("back", r.back ?: "") })
            }
        })
        put("selected", s.selectedReply?.let { JsonPrimitive(it) } ?: JsonNull)
        put("showPlain", s.showPlain)
        put("wordHint", s.wordHint ?: "")
        put("wordAlternatives", JsonArray(s.wordAlternatives.map { JsonPrimitive(it) }))
        put("learnedToday", s.learnedToday)
        put("status", s.statusLine ?: "")
        put("error", s.error ?: "")
        put("trace", s.trace?.summary() ?: "")
        put("exemplars", buildJsonArray { s.exemplarsUsed.forEach { e -> add(buildJsonObject { put("text", e.text); put("score", e.score) }) } })
        put("model", s.model?.let { m -> buildJsonObject { put("name", m.name); put("backend", m.backend); put("tps", m.lastDecodeTokensPerSec ?: 0.0) } } ?: JsonNull)
        put("card", s.voiceCard?.describe() ?: "")
    }.toString()

    fun decodeAction(text: String): HudAction? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val action = (o["action"] as? JsonPrimitive)?.content ?: return null
        return when (action) {
            "next" -> HudAction.Next
            "prev" -> HudAction.Prev
            "confirm" -> HudAction.Confirm
            "select" -> (o["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { HudAction.Select(it) }
            "togglePlain" -> HudAction.TogglePlain
            "clear" -> HudAction.Clear
            "cut" -> HudAction.CutNow
            "mode" -> (o["mode"] as? JsonPrimitive)?.content?.let { m -> runCatching { Mode.valueOf(m) }.getOrNull() }?.let { HudAction.SetMode(it) }
            else -> null
        }
    }
}
