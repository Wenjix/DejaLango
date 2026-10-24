package dev.dejalango.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dejalango.app.ui.CompanionWalkthroughActivity
import dev.dejalango.app.ui.CompanionFixtures
import dev.dejalango.app.ui.partnerMayAnswer
import dev.dejalango.engine.hud.CapturePhase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class CompanionUiAndroidTest {
    @get:Rule val ui = createAndroidComposeRule<CompanionWalkthroughActivity>()
    @Before fun accessibility() { ui.enableAccessibilityChecks() }

    @Test fun everyTurnBeatRendersOnAllFourRegionalFixturesWithSteadyControls() {
        val fixtures = CompanionFixtures(ui.activity)
        repeat(4) { fixture -> repeat(20) { beat ->
            ui.runOnUiThread { ui.activity.fixture = fixture; ui.activity.beat = beat }
            ui.waitForIdle()
            ui.onNodeWithText("I’m talking").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(60.dp)
            val state = fixtures.state(fixture, beat)
            if (beat in setOf(10, 11, 13, 15)) assertTrue(state.replies.isEmpty())
            if (beat == 16) { assertNotNull(state.heard); assertTrue(state.replies.isNotEmpty()) }
            if (beat == 14) assertTrue(state.correction!!.canMoveWhole)
            val done = ui.onNodeWithText("Done").assertIsDisplayed().assertHeightIsAtLeast(60.dp)
            if (state.capture.myTurn && state.capture.phase != CapturePhase.FINISHING) done.assertIsEnabled() else done.assertIsNotEnabled()
            ui.onNodeWithText("I said this one").assertDoesNotExist()
            ui.onRoot().tryPerformAccessibilityChecks()
        } }
    }

    @Test fun talkDoneAndUndoAreDiscretePressesAndDisplayPauseDoesNotEndTheTurn() {
        ui.onNodeWithText("I’m talking").performClick()
        ui.onNodeWithTag("live-title").assertTextEquals("Go ahead. I’m ready.")
        ui.onNodeWithContentDescription("Pause glasses display").performClick()
        ui.onNodeWithContentDescription("Resume glasses display").assertExists()
        ui.onNodeWithText("Done").assertIsEnabled()
        ui.onNodeWithText("Your voice", substring = false).performClick()
        ui.onNodeWithText("Done").assertIsEnabled()
        ui.onNodeWithText("Live", substring = false).performClick()
        ui.onNodeWithText("Done").performClick()
        assertEquals(16, ui.activity.beat)
        ui.runOnUiThread { ui.activity.beat = 13 }
        ui.onNodeWithContentDescription("That was them:", substring = true).performScrollTo().performClick()
        ui.onNodeWithText("Moved to them").assertIsDisplayed()
        ui.onNodeWithText("Move whole line").performClick()
        ui.onNodeWithText("“Un café con leche de avena, por favor. ¿Con azúcar?”").assertExists()
        ui.onNodeWithText("Undo").performClick()
        assertEquals(15, ui.activity.beat)
    }

    @Test fun pairPickerGroupsRegionalVarietiesAndKeepsTheCurrentTurn() {
        ui.onNodeWithText("English (US)").performClick()
        ui.onNodeWithText("English (UK)").performScrollTo().performClick()
        ui.onNodeWithTag("pair-them").performClick()
        ui.onNodeWithText("Mandarin (Taiwan)").performScrollTo().performClick()
        ui.onNodeWithText("Save language pair").performClick()
        ui.onNodeWithText("Pair saved · applies to the next turn").assertExists()
        assertEquals(2, ui.activity.beat)
    }

    @Test fun partnerCueWaitsForRecognitionRatherThanTheSentAcknowledgment() {
        val fixtures = CompanionFixtures(ui.activity)
        assertFalse(partnerMayAnswer(fixtures.state(0, 9)))
        assertTrue(partnerMayAnswer(fixtures.state(0, 10)))
        assertTrue(partnerMayAnswer(fixtures.state(2, 11)))
        assertFalse(partnerMayAnswer(fixtures.state(0, 12)))
        ui.runOnUiThread { ui.activity.beat = 10 }
        ui.onNodeWithText("Show a turn cue to them").performScrollTo().performClick()
        ui.onNodeWithText("Tu turno.").assertIsDisplayed()
        ui.onNode(hasText("I’m talking") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNode(hasText("Done") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("Back to Live").assertIsDisplayed().performClick()
    }

    @Test fun coachingStartRemainsVisibleWhenTheInstructionsNeedScrolling() {
        ui.onNodeWithContentDescription("Settings").performClick()
        ui.onNodeWithText("How Talk and Done work").performScrollTo().performClick()
        ui.onNodeWithText("Got it · start listening").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithText("Your words appear after the turn.", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Got it · start listening").performClick()
        assertEquals(0, ui.activity.beat)
    }

    @Test fun pronunciationAndBrowseActionsStayReachableWithLargeText() {
        ui.runOnUiThread { ui.activity.fixture = 2; ui.activity.beat = 2 }
        ui.onNodeWithText("Yì bēi jiā niúnǎi de kāfēi, xièxie.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Previous reply").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Next reply").performScrollTo().performClick()
        ui.onNodeWithText("Nǐ tuījiàn shénme? Wǒ xiǎng shìshi xīn kǒuwèi.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("Previous reply").performScrollTo().assertIsEnabled()
    }
}
