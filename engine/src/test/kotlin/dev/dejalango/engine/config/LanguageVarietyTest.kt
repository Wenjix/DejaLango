package dev.dejalango.engine.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageVarietyTest {
    @Test fun `Traditional Mandarin keeps a distinct script key`() {
        assertEquals("zh-Hant", Language.byCode("zh-TW").code)
    }

    @Test fun `regional selections are not collapsed to the same language object`() {
        assertFalse(Language.byCode("en-US") == Language.byCode("en-GB"))
        assertFalse(Language.byCode("es-CO") == Language.byCode("es-MX"))
    }

    @Test fun `eight varieties form exactly 42 valid ordered pairs and legacy pairs restore safely`() {
        assertEquals(8, Language.VARIETIES.size)
        assertEquals(42, Language.VARIETIES.sumOf { a -> Language.VARIETIES.count { b -> LanguagePair.isValid(a, b) } })
        assertFalse(LanguagePair.isValid(Language.EN, Language.EN_AU))
        assertFalse(LanguagePair.isValid(Language.ZH, Language.ZH_TW))
        assertEquals(LanguagePair(Language.EN, Language.ZH), LanguagePair.restored("en", "zh"))
        assertEquals(LanguagePair(Language.ZH_TW, Language.EN), LanguagePair.restored("zh-Hant", "zh-CN"))
        assertEquals("es-MX", LanguagePair.restored("es-MX", "unknown").me.tag)
        assertTrue(LanguagePair.restored("ja", "ko").me in Language.VARIETIES)
    }
}
