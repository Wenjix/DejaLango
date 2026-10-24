package dev.dejalango.engine.voice

import dev.dejalango.engine.config.Language
import dev.dejalango.engine.conversation.ReplyMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {

    @Test
    fun `retriever ranks the near sentence first and MMR avoids exact duplicates`() = runBlocking {
        val store = InMemoryExemplarStore()
        val emb = HashingEmbedder()
        suspend fun add(s: String) = store.add(s, "en", Source.TRANSCRIPT, emb.embed(s))
        add("I think we should just ship it and see")
        add("I think we should just ship it and see")
        add("we should ship it tomorrow I think")
        add("the weather is really nice today")
        add("no worries if it breaks, we'll fix it")
        val r = Retriever(store, emb)
        val top = r.forQuery("should we ship it", "en", k = 2)
        assertEquals(2, top.size)
        assertTrue(top[0].exemplar.text.contains("ship it"))
        assertNotEquals("MMR should not pick both duplicates", top[0].exemplar.text, top[1].exemplar.text)
        assertTrue(top[0].score >= top[1].score - 1e-6f || top[1].exemplar.text != top[0].exemplar.text)
        val none = r.forQuery("anything", "zh", k = 3)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `retriever embeds what was said as a query, not as a stored sentence`() = runBlocking {
        // EmbeddingGemma gives a search query and a stored sentence different task prompts.
        val hashing = HashingEmbedder()
        val roles = ArrayList<EmbedRole>()
        val recording = object : TextEmbedder {
            override val dims = hashing.dims
            override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray> {
                roles += role
                return hashing.embed(texts, role)
            }
        }
        val store = InMemoryExemplarStore()
        store.add("So are you from around here originally?", "en", Source.TRANSCRIPT, hashing.embed("So are you from around here originally?"))
        Retriever(store, recording).forQuery("where are you from", "en", k = 1)
        assertEquals(listOf(EmbedRole.QUERY), roles)
    }

    @Test
    fun `cosine and normalize`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, Vectors.cosine(a, b), 1e-6f)
        assertEquals(1f, Vectors.cosine(a, a), 1e-6f)
        val n = Vectors.normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
    }

    @Test
    fun `reply matcher works for english and chinese`() {
        val suggested = listOf("对啊，第一次来，还在到处转悠呢。", "不是不是，之前来过两次了。")
        assertEquals(0, ReplyMatcher.match("对啊 第一次来 还在到处转悠呢", suggested))
        assertEquals(1, ReplyMatcher.match("不是不是，之前来过两次了", suggested))
        assertNull(ReplyMatcher.match("今天天气真好", suggested))
        val en = listOf("Yeah, first time, still wandering around.", "No no, I've been twice.")
        assertEquals(0, ReplyMatcher.match("yeah first time still wandering around", en))
        assertNull(ReplyMatcher.match("what time is it", en))
    }

    @Test
    fun `reply matcher ignores fragments and their own lines but keeps partial readings`() {
        val suggested = listOf("对啊，第一次来，还在到处转悠呢。", "不是不是，之前来过两次了。", "是的，这是我第一次来北京。", "不是，我以前来过。")
        // Close to a phrasebook line is not me reading it (the old 0.45 threshold matched both).
        assertNull(ReplyMatcher.match("你是第一次来北京吗？", suggested))
        assertNull(ReplyMatcher.match("我以前没来过", suggested))
        // Short fragments that happen to sit inside a reply are not a reading of it.
        assertNull(ReplyMatcher.match("第一次来", suggested))
        assertNull(ReplyMatcher.match("两次了", suggested))
        // Reading the start of a reply still counts.
        assertEquals(0, ReplyMatcher.match("对啊，第一次来，还在", suggested))
        assertEquals(1, ReplyMatcher.match("不是不是，之前来过", suggested))
    }

    @Test
    fun `their lines that echo a short card don't match it`() {
        // Cards from the device run. Mandarin answers repeat the question's frame, so THEM's next line often sits
        // within 0.35 of a short card; short cards allow about one slip, and only my reading's start may be partial.
        val first = listOf("是啊，第一次来北京。", "你呢？你来北京是第一次吗？")
        assertNull("THEM repeating the question", ReplyMatcher.match("你是第一次来北京吗？", first))
        assertNull("THEM answering my ask-back", ReplyMatcher.match("我不是第一次来北京", first))
        val drinks = listOf("我喜欢喝茶。", "茶好不好？")
        assertNull(ReplyMatcher.match("我喜欢喝咖啡", drinks))
        assertNull("a card inside a longer line", ReplyMatcher.match("我喜欢喝茶，你呢？", drinks))
        assertNull(ReplyMatcher.match("Claro.", listOf("Sí, claro.")))
        assertEquals(0, ReplyMatcher.match("是啊，第一次来北京", first))
        assertEquals(0, ReplyMatcher.match("好啊去哪", listOf("好啊，去哪儿？")))
    }

    @Test
    fun `an exact reading wins over a reply that contains it`() {
        val cards = listOf("还没定呢。", "还没定呢，你呢？")
        assertEquals(1, ReplyMatcher.match("还没定呢，你呢？", cards))
        assertEquals(0, ReplyMatcher.match("还没定呢", cards))
    }

    @Test
    fun `quick card keeps sentences in my language's script`() {
        assertTrue(VoiceCardBuilder.inScriptOf(Language.EN, "Honestly, the food here is amazing."))
        assertFalse(VoiceCardBuilder.inScriptOf(Language.EN, "你是第一次来北京吗？"))
        assertTrue(VoiceCardBuilder.inScriptOf(Language.ZH, "你是第一次来北京吗？"))
        assertFalse(VoiceCardBuilder.inScriptOf(Language.ZH, "Sounds good to me."))
        assertTrue("two Latin-script languages can't be told apart", VoiceCardBuilder.inScriptOf(Language.ES, "Sounds good to me."))
    }

    @Test
    fun `voice card json round trip and describe`() {
        val c = VoiceCard(
            l1 = "en", l2 = "zh", register = 2, directness = 4, warmth = 4, humor = 3, avgSentenceWords = 9,
            fillers = listOf("basically"), openers = listOf("So basically"), closers = listOf("no worries"),
            signaturePhrases = listOf("just ship it and see"), avoids = listOf("kindly"), notes = "fast and casual", confidence = Confidence.FULL,
        )
        val back = VoiceCard.fromJson(c.toJson())
        assertEquals(c, back)
        val d = c.describe()
        assertTrue(d.contains("just ship it and see"))
        assertTrue(d.contains("casual"))
        assertTrue("the bench shows what I never say", d.contains("never says: kindly"))
        assertTrue(VoiceCard.none("en", "zh").describe().contains("no voice card"))
    }

    @Test
    fun `prompt text is compact, capped and blank without a card`() {
        val c = VoiceCard(
            l1 = "en", l2 = "zh", register = 2, directness = 4, warmth = 4, humor = 3, avgSentenceWords = 9,
            fillers = listOf("honestly", "like"), openers = listOf("Oh nice,", "Wait really?"), closers = listOf("sounds good to me"),
            signaturePhrases = listOf("I'm down for that", "sounds good to me", "wait, really?"), avoids = listOf("kindly"),
            notes = "Warm and casual-direct; asks quick follow-up questions.", confidence = Confidence.FULL,
        )
        assertEquals(
            "casual, direct, warm, short sentences\n" +
                "fillers: honestly, like\n" +
                "signature moves: I'm down for that | sounds good to me | wait, really? | Oh nice\n" +
                "Warm and casual-direct; asks quick follow-up questions.",
            c.promptText(),
        )
        assertFalse("avoids stay out of the prompt", c.promptText().contains("kindly"))
        assertTrue(c.copy(confidence = Confidence.QUICK).promptText().startsWith("(low confidence) casual"))
        assertEquals("", VoiceCard.none("en", "zh").promptText())
        assertEquals("neutral tone", VoiceCard(l1 = "en", l2 = "zh", confidence = Confidence.FULL).promptText())

        // CJK phrases dedupe by their own characters, full-width separators are trimmed, notes are capped.
        val zh = VoiceCard(
            l1 = "zh", l2 = "en", fillers = listOf("那个", "就是"), openers = listOf("哎呀，", "好吧，"), signaturePhrases = listOf("没问题", "好吧"),
            notes = "word ".repeat(40), confidence = Confidence.FULL,
        ).promptText()
        assertTrue(zh, zh.contains("signature moves: 没问题 | 好吧 | 哎呀\n"))
        val notes = zh.lines().last()
        assertTrue(notes, notes.endsWith("…") && notes.length <= 121)
    }

    @Test
    fun `whole sentences a model copied into the card never reach the prompt`() {
        // What Gemma 4 E2B returned on device for the seed corpus: sentences as openers, clauses as fillers.
        val c = VoiceCard(
            l1 = "en", l2 = "zh", warmth = 4, avgSentenceWords = 14, confidence = Confidence.FULL,
            fillers = listOf("so", "like", "like, mild or super hot?", "like, walking distance", "like, should I grab a taxi?"),
            openers = listOf("Hey, so nice to meet you, how's your day going?", "Honestly, I'll have whatever you're having.", "Oh nice,", "Ha, that's a bit personal"),
            signaturePhrases = listOf("你是第一次来北京吗你呢我们一起去吧", "I'm down for that"),
        )
        assertEquals("warm\nfillers: so, like\nsignature moves: I'm down for that | Oh nice | Ha, that's a bit personal", c.promptText())

        // Unspaced Han/kana count by characters and Hangul by spaces, so short CJK and Korean habits still fit.
        val cjk = VoiceCard(
            l1 = "ko", l2 = "en", fillers = listOf("就是说", "えっと"), signaturePhrases = listOf("그러니까 말이야"), confidence = Confidence.FULL,
        ).promptText()
        assertTrue(cjk, cjk.contains("fillers: 就是说, えっと") && cjk.contains("signature moves: 그러니까 말이야"))
    }

    @Test
    fun `sentence splitting handles latin and cjk punctuation`() {
        val s = VoiceCardBuilder.splitSentences("So basically we ship it. No worries if it breaks! 今天天气真好。你呢？")
        assertEquals(listOf("So basically we ship it.", "No worries if it breaks!", "今天天气真好。", "你呢？"), s)
    }
}
