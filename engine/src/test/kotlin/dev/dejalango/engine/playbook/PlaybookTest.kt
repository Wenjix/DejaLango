package dev.dejalango.engine.playbook

import dev.dejalango.engine.hud.Reply
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.TextEmbedder
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PlaybookTest {

    /** Fixed vectors for known strings, so every score is exact. Records each embed() call. */
    private class FixedEmbedder : TextEmbedder {
        class Call(val texts: List<String>, val role: EmbedRole)

        override val dims = 5
        override var description = "fixed"
        val calls = ArrayList<Call>()

        /** Runs inside each call, as a model that loads or falls back while embedding would. */
        var during: () -> Unit = {}

        override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
            calls += Call(texts, role)
            during()
            return texts.map { VECTORS[it] ?: error("no vector for \"$it\"") }
        }
    }

    private suspend fun index(embedder: TextEmbedder = FixedEmbedder(), batchSize: Int = 16): PlaybookIndex =
        PlaybookIndex.build(Playbook.parse(VALID), embedder, batchSize)

    private fun PlaybookMatch.hit(): PlaybookMatch.Hit = this as? PlaybookMatch.Hit ?: throw AssertionError("expected a hit, got ${describe()}")

    private fun PlaybookMatch.miss(): PlaybookMatch.Miss = this as? PlaybookMatch.Miss ?: throw AssertionError("expected a miss, got ${describe()}")

    /** [VALID] with [old], which must occur exactly once, replaced by [new]. */
    private fun edit(old: String, new: String): String {
        check(VALID.split(old).size == 2) { "\"$old\" must occur once in VALID" }
        return VALID.replace(old, new)
    }

    private fun assertRejected(json: String, fragment: String) {
        val e = assertThrows(IllegalArgumentException::class.java) { Playbook.parse(json) }
        assertTrue("\"${e.message}\" should contain \"$fragment\"", e.message.orEmpty().contains(fragment))
    }

    @Test
    fun `parses scenes, moments and their seeded lines`() {
        val p = Playbook.parse(VALID)
        assertEquals(1, p.version)
        assertEquals("en-US", p.l1)
        assertEquals("zh-CN", p.l2)
        assertEquals(listOf("restaurant", "cafe"), p.scenes.map { it.id })
        val restaurant = p.scene("restaurant")!!
        assertEquals("server", restaurant.localRole)
        assertEquals(listOf("restaurant.greet", "restaurant.order"), restaurant.moments.map { it.id })
        val greet = restaurant.moment(restaurant.start)!!
        assertEquals("greets, asks party size", greet.intent)
        assertEquals(SeedLine("restaurant.greet.l1", "您好，几位？", "Hello, how many?"), greet.local[0])
        assertEquals(SeedReply("restaurant.greet.r1", "一位。", "Yí wèi.", "Just one.", "restaurant.order"), greet.replies[0])
        assertEquals("就我一个人。", greet.others.single().text)
        assertEquals("the end of the conversation", "", restaurant.moment("restaurant.order")!!.replies.single().next)
        assertNull(p.scene("airport"))
        assertNull("a moment of another scene", restaurant.moment("cafe.order"))
    }

    @Test
    fun `malformed json and missing or wrong-typed fields are rejected`() {
        assertRejected("""{"version": 1, "l1": """, "playbook: malformed JSON")
        assertRejected("[]", "playbook: not a JSON object")
        assertRejected(edit("""title": "Café", "setting""", "setting"), "scene \"cafe\": missing \"title\"")
        assertRejected(edit("""version": 1,""", """version": "1","""), "playbook: \"version\" must be an integer")
        assertRejected(edit("""text": "一杯拿铁。",""", """text": 7,"""), "reply \"cafe.order.r1\": \"text\" must be a string")
    }

    @Test
    fun `only version 1 is read`() {
        assertRejected(edit("""version": 1,""", """version": 2,"""), "playbook: version 2 is not supported")
    }

    @Test
    fun `ids are unique across the whole playbook`() {
        assertRejected(
            edit("""id": "cafe.order.r1""", """id": "restaurant.greet.r1"""),
            "scene \"cafe\" > moment \"cafe.order\" > replies[0]: duplicate id \"restaurant.greet.r1\"",
        )
        // Scenes, moments and lines share one namespace.
        assertRejected(edit("""id": "cafe",""", """id": "restaurant.order","""), "scenes[1]: duplicate id \"restaurant.order\"")
    }

    @Test
    fun `a start or next outside its scene is rejected`() {
        assertRejected(edit("""start": "cafe.order""", """start": "restaurant.greet"""), "scene \"cafe\": start \"restaurant.greet\" is not a moment of this scene")
        assertRejected(
            edit("""Just me.", "next": "restaurant.order""", """Just me.", "next": "cafe.order"""),
            "moment \"restaurant.greet\" > other \"restaurant.greet.o1\": next \"cafe.order\" is neither",
        )
    }

    @Test
    fun `a moment needs a local line and a reply, and no line may be blank`() {
        assertRejected(edit("""{"id": "cafe.order.l1", "text": "您想喝点什么？", "l1": "What would you like to drink?"}""", ""), "moment \"cafe.order\": no local line")
        assertRejected(edit("""{"id": "cafe.order.r1", "text": "一杯拿铁。", "roman": "Yì bēi nátiě.", "back": "A latte.", "next": ""}""", ""), "moment \"cafe.order\": no reply")
        assertRejected(edit("""text": "一杯拿铁。""", """text": " """), "reply \"cafe.order.r1\": blank \"text\"")
    }

    @Test
    fun `reply cards show each seeded reply as both versions, with no exemplars`() {
        val greet = Playbook.parse(VALID).scene("restaurant")!!.moment("restaurant.greet")!!
        assertEquals(
            listOf(Reply("一位。", "一位。", "Yí wèi.", "Just one."), Reply("两位。", "两位。", "Liǎng wèi.", "Two.")),
            greet.replyCards(),
        )
        // A Latin-script L2 needs no romanization: a blank roman shows none.
        val spanish = Moment("m", "asks", listOf(SeedLine("l", "¿Qué desea?", "What would you like?")), listOf(SeedReply("r", "Un café.", "", "A coffee.", "")), emptyList())
        assertEquals(listOf(Reply("Un café.", "Un café.", null, "A coffee.")), spanish.replyCards())
    }

    @Test
    fun `build indexes the local's lines and the wearer's, in batches a turn can go between`() = runBlocking {
        val embedder = FixedEmbedder()
        var batchesBeforeTurn = -1
        launch { batchesBeforeTurn = embedder.calls.size }
        val index = index(embedder, batchSize = 4)
        assertEquals(9, index.size)
        assertEquals(
            listOf("restaurant.greet.l1", "restaurant.greet.l2", "restaurant.order.l1", "cafe.order.l1"),
            index.entries.filter { it.kind == LineKind.LOCAL }.map { it.id },
        )
        assertEquals(
            listOf("restaurant.greet.r1", "restaurant.greet.r2", "restaurant.greet.o1", "restaurant.order.r1", "cafe.order.r1"),
            index.entries.filter { it.kind == LineKind.WEARER }.map { it.id },
        )
        assertEquals(listOf(4, 4, 1), embedder.calls.map { it.texts.size })
        assertTrue(embedder.calls.all { it.role == EmbedRole.DOCUMENT })
        assertEquals("fixed", index.description)
        // runBlocking has one thread, like the app's model thread: the waiting coroutine ran between the first two batches.
        assertEquals(1, batchesBeforeTurn)
    }

    @Test
    fun `each batch runs once inside the caller's batch scope, as the app's model lock`() = runBlocking {
        val embedder = FixedEmbedder()
        var locked = false
        val lockedPerCall = ArrayList<Boolean>()
        embedder.during = { lockedPerCall += locked }
        var scopes = 0
        val index = PlaybookIndex.build(Playbook.parse(VALID), embedder, batchSize = 4) { batch ->
            scopes++
            locked = true
            try { batch() } finally { locked = false }
        }
        // A scope that skips its batch would leave lines out of the index.
        assertThrows(IllegalStateException::class.java) { runBlocking { PlaybookIndex.build(Playbook.parse(VALID), embedder) { } } }
        assertEquals(9, index.size)
        assertEquals(3, scopes)
        assertEquals(listOf(true, true, true), lockedPerCall)
    }

    @Test
    fun `the local's line hits its moment`() = runBlocking {
        val hit = index().match("您好，几位", "restaurant", THRESHOLD, MARGIN).hit()
        assertEquals("restaurant", hit.scene.id)
        assertEquals("restaurant.greet", hit.moment.id)
        assertEquals("您好，几位？", hit.matchedText)
        assertEquals(1f, hit.score, EPS)
        assertEquals("the food question is orthogonal", 0f, hit.runnerUp, EPS)
        assertEquals("hit restaurant.greet 1.000 (next 0.000) 「您好，几位？」", hit.describe())
    }

    @Test
    fun `my reading of a card is a wearer line, never the local's`() = runBlocking {
        val miss = index().match("一位", "restaurant", THRESHOLD, MARGIN).miss()
        assertEquals(MissReason.WEARER_LINE, miss.reason)
        assertEquals("一位。", miss.bestText)
        assertEquals(1f, miss.score, EPS)
        assertEquals("no line of the local's is near", 0f, miss.bestLocal, EPS)
        assertEquals("miss WEARER_LINE 1.000 (next 0.000, local 0.000) 「一位。」", miss.describe())
    }

    @Test
    fun `a wearer line as near as the local's wins the tie`() = runBlocking {
        // The greeting comes first in playbook order and would hit, but this may be my reading of the card.
        val miss = index().match("您好，一位", "restaurant", THRESHOLD, MARGIN).miss()
        assertEquals(MissReason.WEARER_LINE, miss.reason)
        assertEquals("the first of the wearer's equal lines", "一位。", miss.bestText)
        assertEquals(sqrt(0.5f), miss.score, EPS)
        // The greeting is the card's own moment: no runner-up, but the local's best line, as near as the card.
        assertEquals(0f, miss.runnerUp, EPS)
        assertEquals(sqrt(0.5f), miss.bestLocal, EPS)
        assertEquals("miss WEARER_LINE 0.707 (next 0.000, local 0.707) 「一位。」", miss.describe())
    }

    @Test
    fun `a wearer line's local score counts every scene when no scene id is given`() = runBlocking {
        // 0.8 from my answers, 0.48 from the café's drink question: out of the restaurant's scope, in every scene's.
        assertEquals(0f, index().match("一杯拿铁吧", "restaurant", THRESHOLD, MARGIN).miss().bestLocal, EPS)
        val miss = index().match("一杯拿铁吧", null, THRESHOLD, MARGIN).miss()
        assertEquals(MissReason.WEARER_LINE, miss.reason)
        assertEquals(0.8f, miss.score, EPS)
        assertEquals(0.6f * 0.8f, miss.bestLocal, EPS)
    }

    @Test
    fun `a line far from every seeded one is below the threshold`() = runBlocking {
        val miss = index().match("嗯，几位", "restaurant", THRESHOLD, MARGIN).miss()
        assertEquals(MissReason.BELOW_THRESHOLD, miss.reason)
        assertEquals(0.6f, miss.score, EPS)
        assertEquals("the nearest line is the local's best", 0.6f, miss.bestLocal, EPS)
        assertEquals("miss BELOW_THRESHOLD 0.600 (next 0.000) 「您好，几位？」", miss.describe())
    }

    @Test
    fun `a line between two moments is ambiguous unless the margin allows it`() = runBlocking {
        val index = index()
        // 0.8 from the greeting, 0.6 from the food question.
        val miss = index.match("您好，吃点什么", "restaurant", THRESHOLD, margin = 0.25f).miss()
        assertEquals(MissReason.AMBIGUOUS, miss.reason)
        assertEquals(0.8f, miss.score, EPS)
        assertEquals(0.6f, miss.runnerUp, EPS)
        assertEquals("restaurant.greet", index.match("您好，吃点什么", "restaurant", THRESHOLD, margin = 0.1f).hit().moment.id)
    }

    @Test
    fun `a scene id keeps the match in that scene`() = runBlocking {
        val index = index()
        val hit = index.match("您想喝点什么", "cafe", THRESHOLD, MARGIN).hit()
        assertEquals("cafe.order", hit.moment.id)
        assertEquals("the café has no other moment", PlaybookMatch.NONE, hit.runnerUp, 0f)
        // In the restaurant, the nearest line is the food question, at 0.6.
        val miss = index.match("您想喝点什么", "restaurant", THRESHOLD, MARGIN).miss()
        assertEquals(MissReason.BELOW_THRESHOLD, miss.reason)
        assertEquals("您想吃点什么？", miss.bestText)
    }

    @Test
    fun `no scene id searches every scene`() = runBlocking {
        val index = index()
        for (scene in listOf(null, "", " ")) {
            val hit = index.match("您想喝点什么", scene, THRESHOLD, MARGIN).hit()
            assertEquals("cafe", hit.scene.id)
            assertEquals("the runner-up is the restaurant's food question", 0.6f, hit.runnerUp, EPS)
        }
    }

    @Test
    fun `a blank transcript misses without embedding anything`() = runBlocking {
        val embedder = FixedEmbedder()
        val index = index(embedder)
        val calls = embedder.calls.size
        val miss = index.match("  ", "restaurant", THRESHOLD, MARGIN)
        assertEquals(PlaybookMatch.Miss(MissReason.EMPTY_QUERY), miss)
        assertEquals("miss EMPTY_QUERY", miss.describe())
        assertEquals(calls, embedder.calls.size)
    }

    @Test
    fun `an unknown scene misses`() = runBlocking {
        assertEquals(PlaybookMatch.Miss(MissReason.NO_SCENE), index().match("您好，几位", "airport", THRESHOLD, MARGIN))
    }

    @Test
    fun `a changed embedder misses, its vectors don't compare with the index's`() = runBlocking {
        val embedder = FixedEmbedder()
        val index = index(embedder)
        embedder.description = "hashing (not semantic)"
        assertEquals(PlaybookMatch.Miss(MissReason.EMBEDDER_CHANGED), index.match("您好，几位", "restaurant", THRESHOLD, MARGIN))
    }

    @Test
    fun `the description is read after embedding, so a model loading on the first batch is no change`() = runBlocking {
        // The app's EmbeddingGemma says it is not loaded until its first embed() call loads it.
        val embedder = FixedEmbedder().apply { description = "EmbeddingGemma (not loaded)" }
        embedder.during = { embedder.description = "EmbeddingGemma · NPU" }
        val index = index(embedder)
        assertEquals("EmbeddingGemma · NPU", index.description)
        index.match("您好，几位", "restaurant", THRESHOLD, MARGIN).hit()
        // Falling back while embedding the query: that vector is the hashing embedder's.
        embedder.during = { embedder.description = "hashing fallback" }
        assertEquals(MissReason.EMBEDDER_CHANGED, index.match("您好，几位", "restaurant", THRESHOLD, MARGIN).miss().reason)
    }

    @Test
    fun `an index built across a fallback matches nothing`() = runBlocking {
        val embedder = FixedEmbedder()
        embedder.during = { if (embedder.calls.size == 2) embedder.description = "hashing fallback" }
        val index = index(embedder, batchSize = 4)
        assertEquals("mixed: fixed, then hashing fallback", index.description)
        assertEquals(MissReason.EMBEDDER_CHANGED, index.match("您好，几位", "restaurant", THRESHOLD, MARGIN).miss().reason)
    }

    companion object {
        private const val THRESHOLD = 0.7f
        private const val MARGIN = 0.1f
        private const val EPS = 1e-5f

        /** Two scenes, three moments. Each seeded line sits on its own line, so a test can break exactly one thing. */
        private val VALID = """
            {"version": 1, "l1": "en-US", "l2": "zh-CN", "scenes": [
              {"id": "restaurant", "title": "Restaurant", "setting": "A busy Sichuan place at lunch", "localRole": "server", "start": "restaurant.greet", "moments": [
                {"id": "restaurant.greet", "intent": "greets, asks party size",
                 "local": [
                  {"id": "restaurant.greet.l1", "text": "您好，几位？", "l1": "Hello, how many?"},
                  {"id": "restaurant.greet.l2", "text": "欢迎光临，几位？", "l1": "Welcome, how many?"}],
                 "replies": [
                  {"id": "restaurant.greet.r1", "text": "一位。", "roman": "Yí wèi.", "back": "Just one.", "next": "restaurant.order"},
                  {"id": "restaurant.greet.r2", "text": "两位。", "roman": "Liǎng wèi.", "back": "Two.", "next": "restaurant.order"}],
                 "others": [
                  {"id": "restaurant.greet.o1", "text": "就我一个人。", "roman": "Jiù wǒ yí gè rén.", "back": "Just me.", "next": "restaurant.order"}]},
                {"id": "restaurant.order", "intent": "asks what to eat",
                 "local": [
                  {"id": "restaurant.order.l1", "text": "您想吃点什么？", "l1": "What would you like to eat?"}],
                 "replies": [
                  {"id": "restaurant.order.r1", "text": "来一个宫保鸡丁。", "roman": "Lái yí gè gōngbǎo jīdīng.", "back": "A kung pao chicken, please.", "next": ""}],
                 "others": []}]},
              {"id": "cafe", "title": "Café", "setting": "A coffee counter", "localRole": "barista", "start": "cafe.order", "moments": [
                {"id": "cafe.order", "intent": "asks what to drink",
                 "local": [
                  {"id": "cafe.order.l1", "text": "您想喝点什么？", "l1": "What would you like to drink?"}],
                 "replies": [
                  {"id": "cafe.order.r1", "text": "一杯拿铁。", "roman": "Yì bēi nátiě.", "back": "A latte.", "next": ""}],
                 "others": []}]}]}
        """.trimIndent()

        // Axes: greeting, food, drink, the wearer's answers, unrelated. A score is the dot product at unit length.
        private val VECTORS = mapOf(
            "您好，几位？" to floatArrayOf(1f, 0f, 0f, 0f, 0f),
            "欢迎光临，几位？" to floatArrayOf(1f, 0f, 0f, 0f, 0f),
            "您想吃点什么？" to floatArrayOf(0f, 3f, 0f, 0f, 0f), // not unit length: the index normalizes
            "您想喝点什么？" to floatArrayOf(0f, 0.6f, 0.8f, 0f, 0f), // 0.6 from the food question: both ask for an order
            "一位。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "两位。" to floatArrayOf(0f, 0f, 0f, 0f, 0f), // a zero vector stays zero: it scores 0, never NaN
            "就我一个人。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "来一个宫保鸡丁。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "一杯拿铁。" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            // Transcripts.
            "您好，几位" to floatArrayOf(1f, 0f, 0f, 0f, 0f),
            "一位" to floatArrayOf(0f, 0f, 0f, 1f, 0f),
            "您好，一位" to floatArrayOf(1f, 0f, 0f, 1f, 0f), // exactly as near the greeting as my answers, 0.707 each
            "嗯，几位" to floatArrayOf(0.6f, 0f, 0f, 0f, 0.8f), // 0.6 from the greeting, nothing else near
            "您好，吃点什么" to floatArrayOf(4f, 3f, 0f, 0f, 0f), // 0.8 from the greeting, 0.6 from the food question
            "您想喝点什么" to floatArrayOf(0f, 0.6f, 0.8f, 0f, 0f),
            "一杯拿铁吧" to floatArrayOf(0f, 0f, 0.6f, 0.8f, 0f), // 0.8 from my answers, 0.6 × 0.8 from the drink question
        )
    }
}
