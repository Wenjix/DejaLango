package dev.dejalango.app

import dev.dejalango.app.hud.HudJson
import dev.dejalango.engine.hud.*
import dev.dejalango.engine.config.*
import dev.dejalango.engine.conversation.Speaker
import kotlinx.serialization.json.*
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Exports actual wire payloads for the dependency-free JS renderer contract check. */
class HudRendererFixturesTest {
    @Test fun exportCorrectionWireFixturesWithoutPrivateState() {
        val base = HudState(phase = Phase.SHOWING, modelReady = true, listening = true, sessionId = "renderer-test",
            pair = LanguagePair(Language.EN, Language.ZH_TW), heard = Line("要加糖嗎？", "zh-TW", "With sugar?", 2, Speaker.THEM, "Yào jiā táng ma?"))
        val receipt = CorrectionReceipt(1, 1, "要加糖嗎？", "zh-TW", Speaker.THEM, "Yào jiā táng ma?", canMoveWhole = true)
        val variants = mapOf(
            "split" to base.copy(correction = receipt),
            "unavailable" to base.copy(correction = receipt.copy(text = "", roman = null, message = "That audio has expired. Move the whole line or Undo.")),
            "whole" to base.copy(correction = receipt.copy(text = "一杯咖啡。要加糖嗎？", canMoveWhole = false)),
            "undo" to base.copy(correction = null),
            "paused" to base.copy(displayPaused = true),
        )
        val json = buildJsonObject { variants.forEach { (name, state) -> put(name, Json.parseToJsonElement(HudJson.encode(state))) } }.toString()
        assertFalse(json.contains("voiceCard"))
        File("build/hud-wire-fixtures.json").apply { parentFile.mkdirs(); writeText(json) }
    }
}
