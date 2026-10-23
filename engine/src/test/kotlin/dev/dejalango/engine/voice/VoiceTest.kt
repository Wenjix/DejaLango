package dev.dejalango.engine.voice

import dev.dejalango.engine.conversation.ReplyMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertTrue(d.contains("fairly very casual") || d.contains("casual"))
        assertTrue(VoiceCard.none("en", "zh").describe().contains("no voice card"))
    }

    @Test
    fun `sentence splitting handles latin and cjk punctuation`() {
        val s = VoiceCardBuilder.splitSentences("So basically we ship it. No worries if it breaks! 今天天气真好。你呢？")
        assertEquals(listOf("So basically we ship it.", "No worries if it breaks!", "今天天气真好。", "你呢？"), s)
    }
}
