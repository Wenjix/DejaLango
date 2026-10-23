package dev.dejalango.engine.voice

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.generateText
import dev.dejalango.engine.pipeline.TurnResultParser
import dev.dejalango.engine.prompts.PromptBuilder

/**
 * Builds a [VoiceCard] from sentences (the hour-of-transcript path) or from a minute of audio
 * (the judge path). Both end in one text-only model call. Also returns the sentences so the
 * caller can embed them into the exemplar store.
 */
class VoiceCardBuilder(
    private val model: SpeechLanguageModel,
    private val prompts: PromptBuilder,
    private val parser: TurnResultParser = TurnResultParser(),
    private val clock: Clock = Clock.system(),
) {
    class Result(val card: VoiceCard, val sentences: List<String>, val raw: String)

    suspend fun fromSentences(config: PipelineConfig, sentences: List<String>, confidence: Confidence): Result {
        val clean = sentences.map { it.trim() }.filter { it.length >= 3 }
        require(clean.isNotEmpty()) { "Need at least one sentence" }
        val sample = if (clean.size > MAX_SENTENCES) clean.shuffled().take(MAX_SENTENCES) else clean
        val text = model.generateText(
            GenerateRequest(text = prompts.voiceCard(config, sample), maxTokens = 400, tag = RequestTag.VOICE_CARD),
            clock,
        ).text
        val card = parser.parseVoiceCard(text, config.l1.code, config.l2.code, confidence)
            ?: VoiceCard.none(config.l1.code, config.l2.code)
        return Result(card, clean, text)
    }

    /** Transcribe each turn (stage-A style, no translation), split into sentences, build a QUICK card. */
    suspend fun quickFromAudio(config: PipelineConfig, turns: List<AudioTurn>): Result {
        val sentences = ArrayList<String>()
        for (t in turns) {
            val out = model.generateText(
                GenerateRequest(audio = t, text = prompts.transcribe(), maxTokens = 240, tag = RequestTag.TRANSCRIBE),
                clock,
            ).text
            val tr = parser.parseTranscript(out)?.text ?: parser.rawFallback(out)
            sentences += splitSentences(tr)
        }
        if (sentences.isEmpty()) return Result(VoiceCard.none(config.l1.code, config.l2.code), emptyList(), "")
        return fromSentences(config, sentences, Confidence.QUICK)
    }

    companion object {
        const val MAX_SENTENCES = 120

        /** Splits on sentence punctuation in Latin and CJK scripts; keeps fragments ≥ 3 chars. */
        fun splitSentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.length >= 3 }
    }
}
