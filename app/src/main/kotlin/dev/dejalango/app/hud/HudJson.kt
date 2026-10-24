package dev.dejalango.app.hud

import dev.dejalango.engine.hud.*
import dev.dejalango.engine.conversation.Speaker
import kotlinx.serialization.json.*

/** Small public wire format. No voice profile, model path, trace, access token, or private debug data. */
object HudJson {
    fun encode(s: HudState): String = buildJsonObject {
        fun line(value: Line?): JsonElement = value?.let { buildJsonObject {
            put("id", it.id); put("text", it.text); put("lang", it.lang)
            put("translation", it.translation.orEmpty()); put("roman", it.roman.orEmpty())
        } } ?: JsonNull
        put("session", s.sessionId); put("phase", s.phase.name)
        put("capture", buildJsonObject {
            put("phase", s.capture.phase.name); put("mine", s.capture.myTurn)
            put("ready", s.capture.ready); put("closed", s.capture.closedTurnId?.let(::JsonPrimitive) ?: JsonNull)
        })
        put("pair", buildJsonObject { put("me", s.pair.me.label); put("them", s.pair.them.label); put("roman", s.pair.them.romanization != null) })
        put("pendingPair", s.pendingPair != null); put("paused", s.displayPaused)
        put("listening", s.listening); put("modelReady", s.modelReady); put("processing", s.processing)
        put("heard", line(s.heard)); put("wearer", line(s.wearer)); put("aside", line(s.aside))
        val latest = if (s.correction?.let { it.to == Speaker.THEM && !it.pending && it.text.isNotBlank() && it.text == s.heard?.text } == true) s.heard
            else s.aside ?: s.wearer ?: s.heard
        put("latestLineId", latest?.id?.let(::JsonPrimitive) ?: JsonNull)
        put("replies", buildJsonArray { s.replies.forEach { r -> add(buildJsonObject {
            put("yours", r.yours); put("roman", r.roman.takeIf { s.pair.them.romanization != null }.orEmpty()); put("back", r.back.orEmpty())
        }) } })
        put("selected", s.selectedReply?.let(::JsonPrimitive) ?: JsonNull)
        put("kind", s.replyKind.name); put("source", s.replySource.name); put("matched", s.matched)
        put("hint", buildJsonObject { put("phase", s.hint.phase.name); put("word", s.hint.word); put("roman", s.hint.roman.orEmpty()); put("meaning", s.hint.meaning.orEmpty()) })
        put("correction", s.correction?.let { r -> buildJsonObject {
            put("id", r.id); put("lineId", r.lineId); put("text", r.text); put("roman", r.roman.orEmpty())
            put("to", r.to.name); put("pending", r.pending); put("whole", r.canMoveWhole); put("message", r.message.orEmpty())
        } } ?: JsonNull)
        put("error", s.error.orEmpty()); put("status", s.statusLine.orEmpty()); put("synthetic", s.synthetic)
    }.toString()

    /** IDs protect Undo/flip from stale clients; session binding protects controls after restart. */
    fun decodeAction(text: String, currentSession: String): HudAction? {
        if (text.length > 2048) return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun value(k: String) = (o[k] as? JsonPrimitive)?.content
        if (currentSession.isBlank() || value("session") != currentSession) return null
        return when (value("action")) {
            "talk" -> HudAction.Talk
            "done", "cut" -> HudAction.Done
            "next" -> HudAction.Next
            "prev" -> HudAction.Prev
            "pause" -> HudAction.ToggleDisplay
            "select" -> value("index")?.toIntOrNull()?.takeIf { it in 0..9 }?.let(HudAction::Select)
            "flip" -> value("lineId")?.toLongOrNull()?.let { HudAction.Repair(it, value("whole") == "true") }
            "undo" -> value("correctionId")?.toLongOrNull()?.let(HudAction::Undo)
            else -> null
        }
    }
}
