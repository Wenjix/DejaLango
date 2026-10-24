package dev.dejalango.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.app.ui.MainActivity
import dev.dejalango.app.service.ListenService
import org.junit.Rule
import org.junit.Test

/** The isolated emulator has no Gemma weights. This checks the real host, binder, and unavailable-model path. */
class ConsumerStartupAndroidTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun freshConsumerShowsEmptyOwnershipAndMissingModelNeverBecomesCannedLiveSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        ui.onNodeWithText("Your voice", substring = false).performClick()
        ui.onNodeWithText("No voice profile yet").assertExists()
        ui.onNodeWithText("Live", substring = false).performClick()
        ui.onNodeWithText("Start listening").performScrollTo().performClick()
        ui.waitForIdle()
        val coach = ui.onAllNodesWithText("Got it · start listening")
        if (coach.fetchSemanticsNodes().isNotEmpty()) coach[0].performClick()
        ui.waitUntil(timeoutMillis = 10_000) {
            ui.onAllNodesWithText("The local speech model isn't available.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("I’m talking").assertIsNotEnabled()
        ui.onNodeWithText("Synthetic model", substring = true).assertDoesNotExist()
        ListenService.stop(ui.activity)
    }
}
