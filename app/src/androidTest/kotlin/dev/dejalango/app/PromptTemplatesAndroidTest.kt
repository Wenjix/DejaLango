package dev.dejalango.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.prompts.PromptBuilder
import dev.dejalango.engine.prompts.PromptSet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptTemplatesAndroidTest {
    @Test
    fun templatesRenderAndRejectUnfilledVariablesOnAndroid() {
        val system = PromptBuilder().system(PipelineConfig(), null)
        assertTrue(system.contains("Mandarin Chinese"))
        assertTrue(system.contains("Hanyu Pinyin"))
        assertFalse(system.contains("{{"))

        val custom = PromptSet.DEFAULT.withOverrides(mapOf(PromptSet.TRANSCRIBE to "Missing {{value_0}}"))
        assertThrows(IllegalStateException::class.java) {
            custom.render(PromptSet.TRANSCRIBE, emptyMap())
        }
    }
}
