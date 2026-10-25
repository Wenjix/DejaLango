package dev.dejalango.engine.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastMatchTest {
    @Test fun `heard is read only once its closing quote has arrived`() {
        assertNull(FastMatch.closedHeard("""{"lang":"zh","heard":"你好，你是"""))
        assertNull(FastMatch.closedHeard("""{"lang":"zh","heard":"""))
        assertNull(FastMatch.closedHeard("""{"lang":"z"""))
        assertEquals("你好，你是哪国人啊？", FastMatch.closedHeard("""{"lang":"zh","heard":"你好，你是哪国人啊？""""))
        assertEquals("你好，你是哪国人啊？", FastMatch.closedHeard("""{"lang":"zh", "heard" : "你好，你是哪国人啊？","heard_l1":"Hi, where"""))
    }

    @Test fun `heard_l1 is never taken for heard, and escapes are decoded`() {
        assertNull(FastMatch.closedHeard("""{"heard_l1":"Where are you from?""""))
        assertEquals("a\"b你", FastMatch.closedHeard("""{"heard_l1":"x","heard":"a\"b你""""))
        assertNull(FastMatch.closedHeard("""{"heard":"a\"""))
        assertNull(FastMatch.closedHeard("""{"heard":"a\u4f"""))
    }

    @Test fun `only the first top-level object's own heard counts, as parseStageA reads it`() {
        assertEquals("今天天气真好", FastMatch.closedHeard("""{"debug":{"heard":"您好，几位啊？"},"heard":"今天天气真好""""))
        assertNull(FastMatch.closedHeard("""{"debug":{"heard":"您好，几位啊？"},"heard":"今天"""))
        assertEquals("你好", FastMatch.closedHeard("""Sure: "heard": "x" {"lang":"zh","heard":"你好""""))
        assertNull(FastMatch.closedHeard("""{"lang":"zh"} {"heard":"你好""""))
    }

    private data class L(val text: String, val moment: String, val local: Boolean = true)

    private val lines = listOf(
        L("你好，你是哪国人啊？", "from"), L("你是从哪儿来的？", "from"),
        L("你是第一次来北京吗？", "first"), L("你打算在这儿待多久？", "stay"),
        L("我是美国人。", "from.reply", local = false),
    )

    private fun find(heard: String, among: List<L> = lines) = FastMatch.find(heard, among, { it.text }, { it.local }, { it.moment })

    @Test fun `an exact match after normalizing hits`() {
        assertEquals("你好，你是哪国人啊？", find("你好 你是哪国人啊")?.text) // punctuation and spaces ignored
        assertEquals("你是第一次来北京吗？", find("你是第一次来北京吗")?.text)
    }

    @Test fun `only an exact match hits, never a near one`() {
        assertNull(find("你打算在这待多久")) // one character dropped of nine: the embedding match decides, with its own translation
        val ride = listOf(L("您好，手机尾号三八二七是吧？", "ride.pickup"))
        assertNull(find("您好，手机尾号三八二八是吧？", ride)) // one digit off a seed whose translation names 3827
        assertEquals("您好，手机尾号三八二七是吧？", find("您好 手机尾号三八二七是吧", ride)?.text)
        assertNull(find("你是哪国人"))
        assertNull(find("我想喝一杯咖啡")) // unrelated
        assertNull(find(""))
    }

    @Test fun `an exact match with lines of two moments hits neither, and one moment's phrasings share it`() {
        assertNull(find("你好", listOf(L("你好！", "a"), L("你好。", "b"))))
        assertEquals("你好！", find("你好", listOf(L("你好！", "a"), L("你好。", "a")))?.text)
    }

    @Test fun `closedFields reads the top-level lang along with heard`() {
        assertEquals(mapOf("speaker" to "them", "lang" to "en", "heard" to "您好"), FastMatch.closedFields("""{"speaker":"them","lang":"en","heard":"您好","heard_l1":"He"""))
        assertEquals(mapOf("lang" to "zh"), FastMatch.closedFields("""{"x":{"lang":"en"},"lang":"zh","heard":"您"""))
    }

    @Test fun `a wearer line as close or closer never fast-paths`() {
        assertNull(find("我是美国人"))
        val tie = listOf(L("你是美国人吗", "ask"), L("你是美国人吗", "mine", local = false))
        assertNull(find("你是美国人吗", tie))
    }
}
