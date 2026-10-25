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

    @Test fun `a close match hits only above the bar and with a margin over other moments`() {
        assertEquals("你打算在这儿待多久？", find("你打算在这待多久")?.text) // one character dropped of nine
        assertNull(find("你是哪国人")) // a near miss below the bar goes to the embedding match
        assertNull(find("我想喝一杯咖啡")) // unrelated
        assertNull(find(""))
        val twins = listOf(L("你今天累不累啊", "a"), L("你今天累不累呀", "b")) // two moments one character apart
        assertNull(find("你今天累不累", twins))
    }

    @Test fun `a wearer line as close or closer never fast-paths`() {
        assertNull(find("我是美国人"))
        val tie = listOf(L("你是美国人吗", "ask"), L("你是美国人吗", "mine", local = false))
        assertNull(find("你是美国人吗", tie))
    }
}
