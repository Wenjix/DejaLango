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
 *
 * `{{#name}}…{{/name}}` keeps its body only when `name` renders non-blank, so an empty voice card, context or
 * exemplar list costs no prefill tokens. A section tag alone on its line takes its line break with it, and runs
 * of blank lines collapse to one.
 */
class PromptSet(private val templates: Map<String, String>) {

    fun render(name: String, vars: Map<String, String>): String {
        val t = templates[name] ?: error("No prompt template named '$name'")
        var out = SECTION.replace(STANDALONE_TAG.replace(t, "$1")) { m ->
            val key = m.groupValues[1]
            val body = m.groupValues[2]
            val v = vars[key] ?: error("Unknown section '{{#$key}}' in '$name'")
            // Checked whether or not the section renders, so a bad override fails on its first use, not mid-session.
            check(!SECTION_TAG.containsMatchIn(body)) { "Sections can't nest in '$name': ${SECTION_TAG.find(body)?.value} inside {{#$key}}" }
            if (v.isBlank()) "" else body
        }
        check(!SECTION_TAG.containsMatchIn(out)) { "Unbalanced section in '$name': ${SECTION_TAG.find(out)?.value}" }
        for ((k, v) in vars) out = out.replace("{{$k}}", v)
        check(!PLACEHOLDER.containsMatchIn(out)) { "Unfilled placeholder in '$name': ${PLACEHOLDER.find(out)?.value}" }
        return out.replace(BLANK_RUN, "\n\n").trim()
    }

    fun withOverrides(overrides: Map<String, String>): PromptSet = PromptSet(templates + overrides)

    val names: Set<String> get() = templates.keys

    companion object {
        private val PLACEHOLDER = Regex("\\{\\{[a-z_0-9]+\\}\\}")
        private val SECTION = Regex("\\{\\{#([a-z_0-9]+)\\}\\}(.*?)\\{\\{/\\1\\}\\}", RegexOption.DOT_MATCHES_ALL)
        private val SECTION_TAG = Regex("\\{\\{[#/][a-z_0-9]+\\}\\}")
        private val STANDALONE_TAG = Regex("(?m)^[ \\t]*(\\{\\{[#/][a-z_0-9]+\\}\\})[ \\t]*\\r?\\n")
        private val BLANK_RUN = Regex("\\n{3,}")

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
                // Every Stage B prefills this whole prompt (no session cloning), so each token here costs latency on
                // every turn. The voice card line drops out until there is a card.
                SYSTEM to """
You write replies I (ME) say aloud to THEM: spoken {{l2_name}}, not written style, max {{max_units}} each.
"yours" rewords "plain" in my voice (register, directness, warmth; max one signature move; my {{l1_name}} fillers and phrases as natural {{l2_name}}, never literal).
{{#voice_card}}
My voice: {{voice_card}}
{{/voice_card}}
"roman" is {{roman_rule}}
Example (format only):
{{example}}
""",

                // No context and no suggestions: a 2B model copies whatever text it is shown into "heard".
                STAGE_A to """
Transcribe this audio exactly as spoken, in its own language, then translate it into {{l1_name}}.
"speaker" is "me" if it is mostly {{l1_name}}, "them" if mostly {{l2_name}}; if unclear, "them".
If there is no speech, output {"speaker":"them","lang":"","heard":"","heard_l1":""}.
Otherwise output only: {"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript in the language spoken ({{l2_name}} stays {{l2_name}}), never translated>","heard_l1":"<{{l1_name}} translation, or the transcript if already {{l1_name}}>"}
""",

                // Oldest first, the line to answer last, then the task and the output shape it ends on.
                STAGE_B to """
{{#exemplars}}
My style samples:
{{exemplars}}

{{/exemplars}}
{{#context}}
Earlier:
{{context}}
{{/context}}
THEM now: {{heard}} ({{heard_l1}})

Write {{l2_name}} replies, each a different move (answer, ask back, deflect warmly...). Invent no facts about ME or THEM; ask for missing info. Never translate or reuse {{#exemplars}}my style samples or {{/exemplars}}the example.
Output only one JSON object with {{n}} replies, no markdown or code fences:
{"replies":[{"plain":"<neutral phrasebook line>","yours":"<plain in my voice>","roman":"...","back":"<literal {{l1_name}} translation of yours>"}]}
""",

                SINGLE to """
Listen to the audio turn. Transcribe it exactly as spoken and translate it. Then, only if the speaker is THEM, write {{n}} different short replies I could say next, in {{l2_name}}.
"speaker" is "me" if the turn is mostly {{l1_name}}, "them" if it is mostly {{l2_name}}. If unclear, "them".
Invent no facts about ME or THEM; ask for missing info.
Return: {"speaker":"me"|"them","lang":"<language code>","heard":"<verbatim transcript>","heard_l1":"<{{l1_name}} translation>","replies":[{"plain":"<neutral phrasebook line>","yours":"<plain in my voice>","roman":"...","back":"<literal {{l1_name}} translation of yours>"}]}
When speaker is "me", "replies" is an empty list.
{{#context}}

Conversation so far (oldest first):
{{context}}
{{/context}}
{{#exemplars}}

Sentences I have actually said before, in {{l1_name}}. Use them only to copy my register and habits; never translate them:
{{exemplars}}
{{/exemplars}}
""",

                SAY_IT_MY_WAY to """
I just said ({{l1_name}}): {{heard_l1}}

Say the same thing in {{l2_name}}, as I would say it. Give {{n}} variants: the first closest to what I said, the next more natural if mine was awkward.
{{#exemplars}}

Sentences I have actually said before, in {{l1_name}}. Use them only to copy my register and habits; never translate them:
{{exemplars}}
{{/exemplars}}

Return: {"replies":[{"plain":"<neutral phrasebook line>","yours":"<plain in my voice>","roman":"...","back":"<literal {{l1_name}} translation of yours>"}]}
""",

                WORD to """
Listen to the audio. It is ME, speaking {{l1_name}}, and I paused because I am reaching for a word or a short phrase.
Using my voice{{#exemplars}} and the sentences below{{/exemplars}}, give the word or phrase I most likely wanted next.
{{#exemplars}}

Sentences I have actually said before:
{{exemplars}}
{{/exemplars}}

Return: {"word":"<the word or short phrase>","alternatives":["<alt 1>","<alt 2>"],"confidence":<0.0 to 1.0>}
If I did not pause mid-sentence, or nothing is missing, return {"word":"","alternatives":[],"confidence":0}.
""",

                TRANSCRIBE to """
Transcribe the audio exactly as spoken, keeping fillers and false starts. Do not translate.
Return: {"lang":"<language code>","text":"<verbatim transcript>"}
If there is no speech, return {"lang":"","text":""}.
""",

                // Bounded lists, short-phrase fields first: on device a 2B model otherwise copies whole sentences into
                // "openers" until it runs out of tokens and never reaches the rest.
                VOICE_CARD to """
Below are sentences one person actually said, in {{l1_name}}. Describe how they talk. Quote only short phrases that really appear in the sentences, never whole sentences.
Output only one JSON object:
{"register":<1-5; 1 very casual, 5 very formal>,"directness":<1-5; 1 hedged, 5 blunt>,"warmth":<1-5>,"humor":<1-5>,"avgSentenceWords":<integer>,"signaturePhrases":["<up to 6 phrases of 2-5 words they reuse>"],"fillers":["<up to 3 filler words>"],"openers":["<up to 3 short sentence starters>"],"closers":["<up to 3 short sentence endings>"],"avoids":["<up to 3 things they would never say>"],"notes":"<one sentence on their rhythm and attitude>"}

SENTENCES:
{{sentences}}
""",
            ),
        )

        /**
         * One-reply format examples per L2. Content is illustrative only; the prompts say so. Each shows plain ≠ yours,
         * roman and back following yours, and asking instead of inventing.
         */
        internal fun example(l1: Language, l2: Language): String = when {
            l2.traditional -> """THEM: 可以幫忙嗎？
{"replies":[{"plain":"可以，有什麼事？","yours":"好啊，什麼事？","roman":"Hǎo a, shénme shì?","back":"Sure, what is it?"}]}"""
            l2.base == "zh" -> """THEM: 帮个忙？
{"replies":[{"plain":"可以，有什么事？","yours":"行啊，啥事？","roman":"Xíng a, shá shì?","back":"OK, what thing?"}]}"""

            l2.base == "es" -> """THEM: ¿Me ayudas?
{"replies":[{"plain":"Sí, ¿qué necesitas?","yours":"¡Claro! ¿Qué pasa?","roman":null,"back":"Of course! What happens?"}]}"""

            else -> """THEM: <${l2.name} line>
{"replies":[{"plain":"<neutral ${l2.name} line>","yours":"<plain in my voice>","roman":${if (l2.romanization == null) "null" else "\"<romanization>\""},"back":"<literal ${l1.name} translation of yours>"}]}"""
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
    const val VOICE_CARD = """{"type":"object","properties":{"register":{"type":"integer"},"directness":{"type":"integer"},"warmth":{"type":"integer"},"humor":{"type":"integer"},"avgSentenceWords":{"type":"integer"},"signaturePhrases":{"type":"array","items":{"type":"string"}},"fillers":{"type":"array","items":{"type":"string"}},"openers":{"type":"array","items":{"type":"string"}},"closers":{"type":"array","items":{"type":"string"}},"avoids":{"type":"array","items":{"type":"string"}},"notes":{"type":"string"}},"required":["register","directness","warmth","humor","avgSentenceWords","signaturePhrases","fillers","openers","closers","avoids","notes"]}"""
}

/**
 * Assembles prompts from engine state. The only place that knows what the model is told. The voice card reaches
 * the model through [system] alone; the per-turn prompts carry the conversation and my sentences.
 */
class PromptBuilder(private val set: PromptSet = PromptSet.DEFAULT) {

    fun system(config: PipelineConfig, card: VoiceCard?): String = set.render(
        PromptSet.SYSTEM,
        mapOf(
            "l1_name" to config.l1.name,
            "l2_name" to config.l2.name,
            "voice_card" to (card?.promptText() ?: ""),
            "max_units" to config.maxUnitsText,
            "roman_rule" to (config.l2.romanization?.let { "\"yours\" in $it" } ?: "null (${config.l2.name} uses Latin script)"),
            "example" to PromptSet.example(config.l1, config.l2),
        ),
    )

    /**
     * Stage A transcribes one audio turn and nothing else: shown earlier lines, a 2B model copies them into `heard`.
     * [context] is accepted for compatibility and ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun stageA(config: PipelineConfig, context: List<Turn> = emptyList()): String = set.render(
        PromptSet.STAGE_A,
        mapOf("l1_name" to config.l1.name, "l2_name" to config.l2.name),
    )

    /** [context] is the turns before [heard], which the prompt states on its own. */
    fun stageB(config: PipelineConfig, heard: String, heardL1: String, context: List<Turn>, exemplars: List<Exemplar>): String =
        set.render(
            PromptSet.STAGE_B,
            mapOf(
                "l1_name" to config.l1.name, "l2_name" to config.l2.name,
                "heard" to heard, "heard_l1" to heardL1,
                "context" to renderContext(context),
                "exemplars" to renderExemplars(exemplars),
                "n" to config.replies.toString(),
            ),
        )

    fun single(config: PipelineConfig, context: List<Turn>, exemplars: List<Exemplar>): String = set.render(
        PromptSet.SINGLE,
        mapOf(
            "l1_name" to config.l1.name, "l2_name" to config.l2.name,
            "context" to renderContext(context),
            "exemplars" to renderExemplars(exemplars),
            "n" to config.replies.toString(),
        ),
    )

    fun sayItMyWay(config: PipelineConfig, heardL1: String, exemplars: List<Exemplar>): String = set.render(
        PromptSet.SAY_IT_MY_WAY,
        mapOf(
            "l1_name" to config.l1.name, "l2_name" to config.l2.name,
            "heard_l1" to heardL1,
            "exemplars" to renderExemplars(exemplars),
            "n" to config.replies.toString(),
        ),
    )

    fun word(config: PipelineConfig, exemplars: List<Exemplar>): String = set.render(
        PromptSet.WORD,
        mapOf("l1_name" to config.l1.name, "exemplars" to renderExemplars(exemplars)),
    )

    fun transcribe(): String = set.render(PromptSet.TRANSCRIBE, emptyMap())

    fun voiceCard(config: PipelineConfig, sentences: List<String>): String = set.render(
        PromptSet.VOICE_CARD,
        mapOf("l1_name" to config.l1.name, "sentences" to sentences.joinToString("\n") { "- " + it.trim() }),
    )

    /** "THEM: …" / "ME: …" lines, oldest first, as spoken; blank when empty so the template's section drops out. */
    fun renderContext(context: List<Turn>): String = context.joinToString("\n") { t ->
        val who = if (t.speaker == Speaker.ME) "ME" else "THEM"
        "$who: ${t.text.trim().take(MAX_CONTEXT_CHARS)}"
    }

    /** My sentences, one per line; blank when empty so the template's section drops out. */
    fun renderExemplars(exemplars: List<Exemplar>): String = exemplars.joinToString("\n") { it.text.trim() }

    companion object {
        const val MAX_CONTEXT_CHARS = 160
    }
}
