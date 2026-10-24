package dev.dejalango.engine.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs the whole pipeline without a GPU. Keys canned answers on [GenerateRequest.tag], streams them
 * in a few chunks with configurable delays, and can be scripted: each stage-A call pops the next
 * [ScriptedTurn], so a demo or a test can play "they say X, then Y".
 *
 * Failure injection: [failNext] makes the next call return garbage (parser fallback path);
 * [hangNext] makes it exceed the processor's timeout.
 */
class FakeSpeechModel(
    private val stageADelayMs: Long = 400,
    private val stageBDelayMs: Long = 900,
    private val chunks: Int = 3,
    l1: String = "en",
    l2: String = "zh",
) : SpeechLanguageModel {

    data class ScriptedTurn(
        val speaker: String,
        val lang: String,
        val heard: String,
        val heardL1: String,
        val repliesJson: String? = null,
    )

    private val script = ArrayDeque<ScriptedTurn>()
    private var fallbackIndex = 0
    private var lastTurn: ScriptedTurn? = null

    @Volatile var failNext: Boolean = false
    @Volatile var hangNext: Boolean = false

    var systemPrompt: String? = null
        private set
    val requests = ArrayList<GenerateRequest>()

    @Volatile var prepareCalls: Int = 0
        private set

    override suspend fun prepare(upcoming: List<String>, structured: Boolean): Boolean {
        prepareCalls++
        return false
    }

    override val info: ModelInfo = ModelInfo(name = "fake-gemma", backend = "fake", file = null, lastDecodeTokensPerSec = 999.0)

    private val cannedReplies: String = if (l2 == "zh")
        """{"replies":[{"plain":"是的，这是我第一次来北京。","yours":"对啊，第一次来，还在到处转悠呢。","roman":"Duì a, dì yī cì lái, hái zài dàochù zhuànyou ne.","back":"Yeah, first time, still wandering around everywhere."},{"plain":"不是，我以前来过。","yours":"不是不是，之前来过两次了。","roman":"Bú shì bú shì, zhīqián lái guò liǎng cì le.","back":"No no, I've been here twice before."}]}"""
    else
        """{"replies":[{"plain":"<neutral line>","yours":"<line in my voice>","roman":null,"back":"<back-translation>"},{"plain":"<neutral line 2>","yours":"<line 2 in my voice>","roman":null,"back":"<back-translation 2>"}]}"""

    private val foodRepliesZh: String =
        """{"replies":[{"plain":"这里的菜很好吃，我很喜欢。","yours":"太香了，尤其是那个烤鸭，绝了。","roman":"Tài xiāng le, yóuqí shì nàge kǎoyā, jué le.","back":"So good, especially that roast duck, amazing."},{"plain":"味道不错，就是有点辣。","yours":"挺好吃的，就是稍微有点辣。","roman":"Tǐng hǎochī de, jiùshì shāowēi yǒudiǎn là.","back":"Pretty tasty, just a little bit spicy."}]}"""

    private val weekendRepliesZh: String =
        """{"replies":[{"plain":"我还没有决定，你有什么建议吗？","yours":"还没定呢，打算随便逛逛，有啥推荐吗？","roman":"Hái méi dìng ne, dǎsuàn suíbiàn guàngguang, yǒu shá tuījiàn ma?","back":"Nothing set yet, planning to just stroll around, any recs?"},{"plain":"我想找个地方休息一下。","yours":"想找个安静的咖啡馆坐坐，歇一会儿。","roman":"Xiǎng zhǎo gè ānjìng de kāfēiguǎn zuòzuo, xiē yīhuǐr.","back":"Thinking of finding a quiet cafe to sit and chill for a bit."}]}"""

    private val defaultTurns: List<ScriptedTurn> = if (l2 == "zh") listOf(
        ScriptedTurn("them", "zh", "你是第一次来北京吗？", "Is this your first time in Beijing?", cannedReplies),
        ScriptedTurn("them", "zh", "你觉得这里的菜怎么样？", "What do you think of the food here?", foodRepliesZh),
        ScriptedTurn("them", "zh", "周末有什么打算？", "Any plans for the weekend?", weekendRepliesZh),
    ) else listOf(
        ScriptedTurn("them", l2, "<their line in $l2>", "Is this your first time here?"),
        ScriptedTurn("me", l1, "Yeah, first time.", "Yeah, first time."),
    )

    fun enqueue(vararg turns: ScriptedTurn) { script.addAll(turns) }

    override suspend fun warm(systemPrompt: String) {
        this.systemPrompt = systemPrompt
        delay(10)
    }

    override fun generate(req: GenerateRequest): Flow<TokenChunk> = flow {
        requests += req
        if (hangNext) { hangNext = false; delay(60_000) }
        val (text, delayMs) = answer(req)
        if (failNext) {
            failNext = false
            emit(TokenChunk("Sure! I heard them ask whether this is your first time in Beijing, but I forgot to answer in JSON."))
            emit(TokenChunk("", done = true, stats = DecodeStats(outputTokens = 12, decodeTokensPerSec = 999.0)))
            return@flow
        }
        val n = maxOf(1, chunks)
        val step = (text.length + n - 1) / n
        var i = 0
        while (i < text.length) {
            delay(delayMs / n)
            val end = minOf(text.length, i + step)
            emit(TokenChunk(text.substring(i, end)))
            i = end
        }
        emit(TokenChunk("", done = true, stats = DecodeStats(outputTokens = text.length / 3, decodeTokensPerSec = 999.0)))
    }

    private fun nextTurn(): ScriptedTurn =
        (script.removeFirstOrNull() ?: defaultTurns[fallbackIndex++ % defaultTurns.size]).also { lastTurn = it }

    private fun answer(req: GenerateRequest): Pair<String, Long> = when (req.tag) {
        RequestTag.STAGE_A -> {
            val t = nextTurn()
            """{"speaker":"${t.speaker}","lang":"${t.lang}","heard":"${t.heard}","heard_l1":"${t.heardL1}"}""" to stageADelayMs
        }
        RequestTag.SINGLE -> {
            val t = nextTurn()
            val repliesBody = (t.repliesJson ?: cannedReplies).removePrefix("{").removeSuffix("}")
            val replies = if (t.speaker == "them") repliesBody else "\"replies\":[]"
            """{"speaker":"${t.speaker}","lang":"${t.lang}","heard":"${t.heard}","heard_l1":"${t.heardL1}",$replies}""" to (stageADelayMs + stageBDelayMs)
        }
        RequestTag.STAGE_B -> (lastTurn?.repliesJson ?: cannedReplies) to stageBDelayMs
        RequestTag.WORD -> """{"word":"multimeter","alternatives":["voltmeter","the meter thing"],"confidence":0.8}""" to stageADelayMs
        RequestTag.TRANSCRIBE -> """{"lang":"en","text":"So basically I think, honestly, we should just ship it and see. No worries if it breaks."}""" to stageADelayMs
        RequestTag.VOICE_CARD ->
            """{"register":2,"directness":4,"warmth":4,"humor":3,"avgSentenceWords":9,"fillers":["basically","honestly"],"openers":["So basically"],"closers":["no worries"],"signaturePhrases":["just ship it and see","no worries if it breaks"],"avoids":["kindly","per my last email"],"notes":"Fast, casual, decisive; softens blunt calls with warmth."}""" to stageBDelayMs
        else -> """{"ok":true}""" to stageADelayMs
    }
}
