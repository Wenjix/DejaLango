package dev.dejalango.engine.prompts

import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Turn
import dev.dejalango.engine.voice.Exemplar
import dev.dejalango.engine.voice.VoiceCard

/** Capture supplies the role; transcription never assigns a speaker. */
class CompanionPrompts(private val legacy: PromptBuilder = PromptBuilder()) {
    fun system(cfg: PipelineConfig, card: VoiceCard?): String {
        val voice = card?.takeIf { it.l1 == cfg.l1.code }?.promptText()?.take(400).orEmpty()
        // PromptSet.SYSTEM's wording, which wrote full cards on the phone. "Write short", a second "Invent no facts"
        // (Stage B already says it) and an example without "format only" made a 2B model's cards terse and evasive.
        return buildString {
            append("You write replies I (ME) say aloud to THEM: spoken ${cfg.l2.promptName}, not written style, max ${cfg.maxUnitsText} each.\n")
            append("\"yours\" rewords \"plain\" in my voice (register, directness, warmth; max one signature move; my ${cfg.l1.promptName} fillers and phrases as natural ${cfg.l2.promptName}, never literal).\n")
            if (voice.isNotBlank()) append("My voice: $voice\n")
            append("Roman is ${cfg.l2.romanization ?: "null for Latin script"}; back is the literal ${cfg.l1.promptName} meaning.\n")
            append("Example (format only):\n")
            append(PromptSet.example(cfg.l1, cfg.l2))
        }.take(1200)
    }

    fun transcribe(cfg: PipelineConfig, mine: Boolean, retry: Boolean = false): String = buildString {
        append("Transcribe only this audio exactly as spoken. Never copy prior replies, infer a speaker, or translate heard. ")
        if (mine) append("The language is either ${cfg.l1.promptName} or ${cfg.l2.promptName}. ")
        else append("Expect ${cfg.l2.promptName}. ")
        if (retry) append("Keep the actual spoken script. Use Simplified/Traditional characters as selected. ")
        append("Translate heard into ${cfg.l1.promptName}. Roman is pinyin with tone marks for Mandarin, otherwise null. ")
        // A choice written as the value ("en|zh") is copied as is by a 2B model, so the codes go in a sentence.
        val language = if (mine || cfg.catchForgottenTaps) "\"lang\":\"<language code>\"," else ""
        if (language.isNotEmpty()) append("Set lang to ${cfg.l1.base} for ${cfg.l1.promptName} or ${cfg.l2.base} for ${cfg.l2.promptName}. ")
        append("Return only {$language\"heard\":\"<verbatim transcript>\",\"heard_l1\":\"<meaning>\",\"roman\":null}. No speech means empty heard.")
    }

    fun replies(cfg: PipelineConfig, heard: String, meaning: String, earlier: List<Turn>, examples: List<Exemplar>): String =
        legacy.stageB(regional(cfg), heard, meaning, earlier, examples)

    fun sayIt(cfg: PipelineConfig, text: String, theirLine: String?, examples: List<Exemplar>): String =
        (theirLine?.let { "They last said: $it\n" } ?: "") + legacy.sayItMyWay(regional(cfg), text, examples)

    fun hint(cfg: PipelineConfig): String = """
        This is the wearer pausing mid-sentence in ${cfg.l2.promptName}. If a word is missing, suggest one short completion in that language.
        If they are speaking ${cfg.l1.promptName}, finished the sentence, or need no word, return empty word and zero confidence.
        Return only {"lang":"${cfg.l1.base}|${cfg.l2.base}","word":"<word or empty>","roman":"<pinyin for Mandarin, otherwise empty>","meaning":"<${cfg.l1.promptName} meaning>","confidence":0.0}.
    """.trimIndent()

    fun single(cfg: PipelineConfig, earlier: List<Turn>, examples: List<Exemplar>): String =
        transcribe(cfg, mine = false) + "\nThen write ${cfg.replies} short replies in ${cfg.l2.promptName}, unless no speech or an unexpected language was heard. Add replies with plain, yours, roman and back fields.\n" +
            "Earlier: ${legacy.renderContext(earlier)}\nMy style examples: ${legacy.renderExemplars(examples)}"

    private fun regional(cfg: PipelineConfig) = cfg.copy(l1 = cfg.l1.copy(name = cfg.l1.promptName), l2 = cfg.l2.copy(name = cfg.l2.promptName))
}
