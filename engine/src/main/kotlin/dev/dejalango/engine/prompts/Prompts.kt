package dev.dejalango.engine.prompts

import dev.dejalango.engine.config.Language
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.conversation.Speaker
import dev.dejalango.engine.conversation.Turn
import dev.dejalango.engine.voice.Exemplar
import dev.dejalango.engine.voice.VoiceCard

/**
 * Named prompt templates with `{{placeholder}}` substitution. The app may override any template from
 * `assets/prompts/<name>.txt` so the prompt owner can iterate at the bench without a rebuild;
 * these Kotlin strings are the fallback and the reference.
 */
class PromptSet(private val templates: Map<String, String>) {

    fun render(name: String, vars: Map<String, String>): String {
        val t = templates[name] ?: error("No prompt template named '$name'")
        var out = t
        for ((k, v) in vars) out = out.replace("{{$k}}", v)
        check(!PLACEHOLDER.containsMatchIn(out)) { "Unfilled placeholder in '$name': ${PLACEHOLDER.find(out)?.value}" }
        return out.trim()
    }

    fun withOverrides(overrides: Map<String, String>): PromptSet = PromptSet(templates + overrides)

    val names: Set<String> get() = templates.keys

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{[a-z_0-9]+\\}\\}")

        const val SYSTEM = "system"
        const val STAGE_A = "stage_a"
        const val STAGE_B = "stage_b"
        const val SINGLE = "single"
        const val SAY_IT_MY_WAY = "say_it_my_way"
        const val WORD = "word"
        const val TRANSCRIBE = "transcribe"
        const val VOICE_CARD = "voice_card"

        val DEFAULT: PromptSet = PromptSet(
            mapOf(
                SYSTEM to """
You are DejaLango, an interpreter running on-device inside a pair of glasses. You help ME (the wearer; native language: {{l1_name}}) talk with THEM (native language: {{l2_name}}). You receive short audio turns and text. You always answer with exactly one JSON object and nothing else: no prose, no markdown, no code fences, no explanations.

MY VOICE CARD (how I actually talk; mirror it in every line written for me):
{{voice_card}}

RULES FOR LINES WRITTEN FOR ME TO SAY
- Spoken {{l2_name}}, the way a native speaker says it out loud. Not written register, unless my voice card is formal.
- At most {{max_units}} per line.
- "plain": the neutral phrasebook version of the line.
- "yours": how I would say it: my register, my directness, my warmth; at most one of my signature moves per line; use the natural {{l2_name}} equivalents of my fillers, never literal translations of them.
- "roman": {{roman_rule}}
- "back": a literal {{l1_name}} back-translation of "yours", so I can see exactly what I am about to say.
- Never invent facts about me or them. If a reply needs information I don't have, make the line ask for it.

EXAMPLE OF THE REPLY FORMAT (format only; do not reuse the content)
{{example}}
""",

                STAGE_A to """
Listen to the audio turn. Transcribe it exactly as spoken, then translate it.
Return: {"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript in the language spoken>","heard_l1":"<{{l1_name}} translation; if the turn is already in {{l1_name}}, repeat the transcript>"}
"speaker" is "me" if the turn is mostly {{l1_name}}, "them" if it is mostly {{l2_name}}. If unclear, "them".
If the audio contains no speech, return {"speaker":"them","lang":"","heard":"","heard_l1":""}.

Conversation so far (oldest first):
{{context}}
""",

                STAGE_B to """
THEY just said ({{l2_name}}): {{heard}}
In {{l1_name}}: {{heard_l1}}

Conversation so far (oldest first):
{{context}}

Sentences I have actually said before, in {{l1_name}}. Use them only to copy my register and habits; never translate them:
{{exemplars}}

Write {{n}} different short replies I could say next, in {{l2_name}}, each a different move (answer, ask back, deflect warmly...).
Return: {"replies":[{"plain":"...","yours":"...","roman":"...","back":"..."}]}
""",

                SINGLE to """
Listen to the audio turn. Transcribe it exactly as spoken and translate it. Then, only if the speaker is THEM, write {{n}} different short replies I could say next, in {{l2_name}}.
"speaker" is "me" if the turn is mostly {{l1_name}}, "them" if it is mostly {{l2_name}}. If unclear, "them".
Return: {"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript>","heard_l1":"<{{l1_name}} translation>","replies":[{"plain":"...","yours":"...","roman":"...","back":"..."}]}
When speaker is "me", "replies" is an empty list.

Conversation so far (oldest first):
{{context}}

Sentences I have actually said before, in {{l1_name}}. Use them only to copy my register and habits; never translate them:
{{exemplars}}
""",

                SAY_IT_MY_WAY to """
I just said ({{l1_name}}): {{heard_l1}}

Say the same thing in {{l2_name}}, as I would say it. Give {{n}} variants: the first closest to what I said, the next more natural if mine was awkward.

Sentences I have actually said before, in {{l1_name}}. Use them only to copy my register and habits; never translate them:
{{exemplars}}

Return: {"replies":[{"plain":"...","yours":"...","roman":"...","back":"..."}]}
""",

                WORD to """
Listen to the audio. It is ME, speaking {{l1_name}}, and I paused because I am reaching for a word or a short phrase.
Using my voice card and the sentences below, give the word or phrase I most likely wanted next.

Sentences I have actually said before:
{{exemplars}}

Return: {"word":"<the word or short phrase>","alternatives":["<alt 1>","<alt 2>"],"confidence":<0.0 to 1.0>}
If I did not pause mid-sentence, or nothing is missing, return {"word":"","alternatives":[],"confidence":0}.
""",

                TRANSCRIBE to """
Transcribe the audio exactly as spoken, keeping fillers and false starts. Do not translate.
Return: {"lang":"<language code>","text":"<verbatim transcript>"}
""",

                VOICE_CARD to """
Below are sentences one person actually said, in {{l1_name}}. Infer how they talk. Only include phrases that really appear in the sentences.
Return: {"register":<1-5; 1 very casual, 5 very formal>,"directness":<1-5; 1 hedged, 5 blunt>,"warmth":<1-5>,"humor":<1-5>,"avgSentenceWords":<integer>,"fillers":["..."],"openers":["..."],"closers":["..."],"signaturePhrases":["<up to 6 verbatim phrases they reuse>"],"avoids":["<things they would clearly never say>"],"notes":"<one or two sentences on their rhythm and attitude>"}

SENTENCES:
{{sentences}}
""",
            ),
        )

        /** Format examples per L2. Content is illustrative only; the system prompt says so. */
        internal fun example(l1: Language, l2: Language): String = when (l2.code) {
            "zh" -> """THEY said (zh): 你是第一次来北京吗？  In en: Is this your first time in Beijing?
{"replies":[{"plain":"是的，这是我第一次来北京。","yours":"对啊，第一次来，还在到处转悠呢。","roman":"Duì a, dì yī cì lái, hái zài dàochù zhuànyou ne.","back":"Yeah, first time, still wandering around everywhere."},{"plain":"不是，我以前来过。","yours":"不是不是，之前来过两次了。","roman":"Bú shì bú shì, zhīqián lái guò liǎng cì le.","back":"No no, I've been here twice before."}]}"""

            "es" -> """THEY said (es): ¿Es tu primera vez en Madrid?  In en: Is this your first time in Madrid?
{"replies":[{"plain":"Sí, es mi primera vez en Madrid.","yours":"Sí, la primera, todavía ando perdido.","roman":null,"back":"Yeah, the first, I'm still wandering around lost."},{"plain":"No, ya he venido antes.","yours":"Qué va, ya he venido un par de veces.","roman":null,"back":"No way, I've already come a couple of times."}]}"""

            else -> """THEY said (${l2.code}): <their line>  In ${l1.code}: <translation>
{"replies":[{"plain":"<neutral ${l2.name} line>","yours":"<the same line, in my voice>","roman":${if (l2.romanization == null) "null" else "\"<romanization>\""},"back":"<literal ${l1.name} back-translation of yours>"},{"plain":"...","yours":"...","roman":${if (l2.romanization == null) "null" else "\"...\""},"back":"..."}]}"""
        }
    }
}

/**
 * JSON Schemas matching the prompts' return formats, for runtimes with constrained decoding
 * (LiteRT-LM `ResponseFormat.json`). Nullable fields are plain strings ("" means none) because
 * constrained decoders rarely support union types; the parser maps "" to null.
 */
object Schemas {
    private const val REPLY = """{"type":"object","properties":{"plain":{"type":"string"},"yours":{"type":"string"},"roman":{"type":"string"},"back":{"type":"string"}},"required":["plain","yours","roman","back"]}"""

    const val STAGE_A = """{"type":"object","properties":{"speaker":{"type":"string","enum":["me","them"]},"lang":{"type":"string"},"heard":{"type":"string"},"heard_l1":{"type":"string"}},"required":["speaker","lang","heard","heard_l1"]}"""
    val STAGE_B = """{"type":"object","properties":{"replies":{"type":"array","items":$REPLY}},"required":["replies"]}"""
    val SINGLE = """{"type":"object","properties":{"speaker":{"type":"string","enum":["me","them"]},"lang":{"type":"string"},"heard":{"type":"string"},"heard_l1":{"type":"string"},"replies":{"type":"array","items":$REPLY}},"required":["speaker","lang","heard","heard_l1","replies"]}"""
    const val WORD = """{"type":"object","properties":{"word":{"type":"string"},"alternatives":{"type":"array","items":{"type":"string"}},"confidence":{"type":"number"}},"required":["word","alternatives","confidence"]}"""
    const val TRANSCRIBE = """{"type":"object","properties":{"lang":{"type":"string"},"text":{"type":"string"}},"required":["lang","text"]}"""
    const val VOICE_CARD = """{"type":"object","properties":{"register":{"type":"integer"},"directness":{"type":"integer"},"warmth":{"type":"integer"},"humor":{"type":"integer"},"avgSentenceWords":{"type":"integer"},"fillers":{"type":"array","items":{"type":"string"}},"openers":{"type":"array","items":{"type":"string"}},"closers":{"type":"array","items":{"type":"string"}},"signaturePhrases":{"type":"array","items":{"type":"string"}},"avoids":{"type":"array","items":{"type":"string"}},"notes":{"type":"string"}},"required":["register","directness","warmth","humor","avgSentenceWords","fillers","openers","closers","signaturePhrases","avoids","notes"]}"""
}

/** Assembles prompts from engine state. The only place that knows what the model is told. */
class PromptBuilder(private val set: PromptSet = PromptSet.DEFAULT) {

    fun system(config: PipelineConfig, card: VoiceCard?): String = set.render(
        PromptSet.SYSTEM,
        mapOf(
            "l1_name" to config.l1.name,
            "l2_name" to config.l2.name,
            "voice_card" to (card ?: VoiceCard.none(config.l1.code, config.l2.code)).describe(),
            "max_units" to config.maxUnitsText,
            "roman_rule" to (config.l2.romanization?.let { "the romanization of \"yours\" in $it." }
                ?: "null (${config.l2.name} uses Latin script)."),
            "example" to PromptSet.example(config.l1, config.l2),
        ),
    )

    fun stageA(config: PipelineConfig, context: List<Turn>): String = set.render(
        PromptSet.STAGE_A,
        mapOf("l1_name" to config.l1.name, "l2_name" to config.l2.name, "context" to renderContext(context, config)),
    )

    fun stageB(config: PipelineConfig, heard: String, heardL1: String, context: List<Turn>, exemplars: List<Exemplar>, card: VoiceCard?): String =
        set.render(
            PromptSet.STAGE_B,
            mapOf(
                "l1_name" to config.l1.name, "l2_name" to config.l2.name,
                "heard" to heard, "heard_l1" to heardL1,
                "context" to renderContext(context, config),
                "exemplars" to renderExemplars(exemplars, card),
                "n" to config.replies.toString(),
            ),
        )

    fun single(config: PipelineConfig, context: List<Turn>, exemplars: List<Exemplar>, card: VoiceCard?): String = set.render(
        PromptSet.SINGLE,
        mapOf(
            "l1_name" to config.l1.name, "l2_name" to config.l2.name,
            "context" to renderContext(context, config),
            "exemplars" to renderExemplars(exemplars, card),
            "n" to config.replies.toString(),
        ),
    )

    fun sayItMyWay(config: PipelineConfig, heardL1: String, exemplars: List<Exemplar>, card: VoiceCard?): String = set.render(
        PromptSet.SAY_IT_MY_WAY,
        mapOf(
            "l1_name" to config.l1.name, "l2_name" to config.l2.name,
            "heard_l1" to heardL1,
            "exemplars" to renderExemplars(exemplars, card),
            "n" to config.replies.toString(),
        ),
    )

    fun word(config: PipelineConfig, exemplars: List<Exemplar>, card: VoiceCard?): String = set.render(
        PromptSet.WORD,
        mapOf("l1_name" to config.l1.name, "exemplars" to renderExemplars(exemplars, card)),
    )

    fun transcribe(): String = set.render(PromptSet.TRANSCRIBE, emptyMap())

    fun voiceCard(config: PipelineConfig, sentences: List<String>): String = set.render(
        PromptSet.VOICE_CARD,
        mapOf("l1_name" to config.l1.name, "sentences" to sentences.joinToString("\n") { "- " + it.trim() }),
    )

    fun renderContext(context: List<Turn>, config: PipelineConfig): String {
        if (context.isEmpty()) return "(nothing yet)"
        return context.joinToString("\n") { t ->
            val who = when (t.speaker) {
                Speaker.ME -> "ME"
                Speaker.THEM -> "THEM"
                Speaker.UNKNOWN -> "?"
            }
            val text = t.text.trim().take(MAX_CONTEXT_CHARS)
            val tr = t.textL1?.trim()?.takeIf { it.isNotEmpty() && it != t.text && t.speaker == Speaker.THEM }
            if (tr != null) "$who (${t.lang}): $text  [${config.l1.code}: ${tr.take(MAX_CONTEXT_CHARS)}]" else "$who (${t.lang}): $text"
        }
    }

    fun renderExemplars(exemplars: List<Exemplar>, card: VoiceCard?): String {
        val lines = ArrayList<String>()
        card?.signaturePhrases?.take(2)?.forEach { lines += "- \"$it\" (signature)" }
        exemplars.forEach { lines += "- \"${it.text.trim()}\"" }
        return if (lines.isEmpty()) "(none yet)" else lines.joinToString("\n")
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 160
    }
}
