package dev.dejalango.engine.voice

import dev.dejalango.engine.audio.AudioTurn
import dev.dejalango.engine.audio.Clock
import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.llm.GenerateRequest
import dev.dejalango.engine.llm.RequestTag
import dev.dejalango.engine.llm.SpeechLanguageModel
import dev.dejalango.engine.llm.generateText
import dev.dejalango.engine.pipeline.TurnResultParser
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.prompts.Schemas

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
            GenerateRequest(text = prompts.voiceCard(config, sample), maxTokens = 400, tag = RequestTag.VOICE_CARD, jsonSchema = if (config.structuredOutput) Schemas.VOICE_CARD else null),
            clock,
        ).text
        val card = parser.parseVoiceCard(text, config.l1.code, config.l2.code, confidence)
            ?: VoiceCard.none(config.l1.code, config.l2.code)
        return Result(card, clean, text)
    }

    /**
     * Transcribe each turn (stage-A style, no translation), keep the sentences in my L1, build a QUICK card.
     * The audio is whatever the mic heard, THEM included; their L2 lines say nothing about how I talk.
     */
    suspend fun quickFromAudio(config: PipelineConfig, turns: List<AudioTurn>): Result {
        val sentences = ArrayList<String>()
        for (t in turns) {
            val out = model.generateText(
                GenerateRequest(audio = t, text = prompts.transcribe(), maxTokens = 240, tag = RequestTag.TRANSCRIBE, jsonSchema = if (config.structuredOutput) Schemas.TRANSCRIBE else null),
                clock,
            ).text
            // Only a transcript that parsed: a raw fallback (a JSON fragment, a refusal) is not something I said, and
            // these sentences are indexed as my exemplars.
            val tr = parser.parseTranscript(out)?.text?.takeIf { it.isNotEmpty() } ?: continue
            sentences += splitSentences(tr).filter { inScriptOf(config.l1, it) }
        }
        if (sentences.isEmpty()) return Result(VoiceCard.none(config.l1.code, config.l2.code), emptyList(), "")
        return fromSentences(config, sentences, Confidence.QUICK)
    }

    companion object {
        const val MAX_SENTENCES = 120

        /** Han, kana and Hangul: the scripts that tell a zh/ja/ko sentence from a Latin-script one. */
        private val CJK_SCRIPTS = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        private val CJK_LANGS = setOf("zh", "ja", "ko")

        /** Splits on sentence punctuation in Latin and CJK scripts; keeps fragments ≥ 3 chars. */
        fun splitSentences(text: String): List<String> =
            text.split(Regex("(?<=[.!?。！？])\\s*")).map { it.trim() }.filter { it.length >= 3 }

        /** Is [sentence] in [lang]'s script family? Two Latin-script languages can't be told apart this way, so both pass. */
        fun inScriptOf(lang: Language, sentence: String): Boolean {
            val cjk = sentence.any { Character.UnicodeScript.of(it.code) in CJK_SCRIPTS }
            return cjk == (lang.code in CJK_LANGS)
        }
    }
}
