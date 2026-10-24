package dev.dejalango.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.app.platform.config.AppSettings
import dev.dejalango.app.platform.config.ConfigStore
import dev.dejalango.app.service.PipelineRuntime
import dev.dejalango.engine.config.Backend
import dev.dejalango.engine.config.PipelineConfig
import dev.dejalango.engine.hud.Phase
import dev.dejalango.engine.trace.TurnTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Replays a debug fixture (`demo_zh.wav` unless `-e fixture` names another) through the whole app ([PipelineRuntime]:
 * VAD, segmenter, Gemma, retrieval, HUD) on the real models, and logs each turn's timings. The runtime also appends them
 * to the trace CSV (scripts/pull_traces.sh). The fixtures aren't committed: put the WAV in app/src/debug/assets/fixtures
 * before building, or the run is skipped. The settings are replaced in memory only, so nothing saved changes. It takes
 * about two minutes, so it runs only when asked, with the default backend (AUTO) or a named one:
 *
 *     adb shell am instrument -w -e fixtureRun true [-e backend GPU] [-e fixture playbook_social_zh-CN.wav -e playbookScene social] \
 *       -e class dev.dejalango.app.FixtureRunAndroidTest dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Keep the screen on meanwhile (`adb shell settings put global stay_on_while_plugged_in 7`, restored afterwards): no
 * foreground service holds the phone awake here, and a dozing phone stretches the replay.
 */
@RunWith(AndroidJUnit4::class)
class FixtureRunAndroidTest {

    @Test
    fun replayTheDemoFixtureThroughTheApp() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e fixtureRun true to replay the fixture (about two minutes)", args.getString("fixtureRun") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = ctx.appGraph
        val backend = args.getString("backend")?.let { Backend.valueOf(it) } ?: Backend.AUTO
        val fixture = args.getString("fixture") ?: FIXTURE
        // AppGraph falls back to the microphone when the fixture is missing, and then listening never ends.
        assumeTrue("no fixtures/$fixture in the app's assets: add it to app/src/debug/assets/fixtures and rebuild",
            runCatching { ctx.assets.open("fixtures/$fixture").close() }.isSuccess || File(fixture).canRead())
        val scene = args.getString("playbookScene").orEmpty() // e.g. social, or * for every scene; blank keeps the playbook off
        overrideInMemory(graph.configStore, PipelineConfig(backend = backend, playbookScene = scene)) { it.copy(useFakeModel = false, replayFixture = fixture) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = PipelineRuntime(ctx, graph, scope)
        val traces = Collections.synchronizedList(ArrayList<TurnTrace>())
        scope.launch { runtime.processor.traces.collect { traces += it } }
        try {
            runtime.start()
            Log.i(TAG, "model ${runtime.hud.value.model} · ${runtime.modelStatusLine}")
            withTimeout(10_000) { while (!runtime.isListening) delay(100) }
            withTimeout(MAX_REPLAY_MS) { while (runtime.isListening) delay(500) } // the replay ends a second after the file
            withTimeout(60_000) { // then the last turn finishes
                var calm = 0
                while (calm < 6) {
                    delay(500)
                    calm = if (runtime.hud.value.phase == Phase.THINKING) 0 else calm + 1
                }
            }
        } finally {
            runtime.stop()
            scope.cancel()
        }

        val done = synchronized(traces) { traces.toList() }
        for (t in done) {
            Log.i(TAG, "%s | stage B first token %s, A %s tok/s, B %s tok/s".format(
                t.summary(),
                t.tBFirstToken?.let { "+${it - t.tTurnEnd}ms" } ?: "–",
                t.decodeTpsA?.let { "%.1f".format(it) } ?: "–",
                t.decodeTpsB?.let { "%.1f".format(it) } ?: "–",
            ))
        }
        if (scene.isNotBlank()) Log.i(TAG, "${runtime.playbookStatus.value} · ${done.count { it.shape == "THEM_PLAYBOOK" }} playbook hits · last note ${runtime.hud.value.playbookNote}")
        fun median(xs: List<Long>) = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        Log.i(TAG, "%s: %d turns, %d with replies, %d cancelled · median turn end → transcript %s ms, → replies %s ms".format(
            backend, done.size, done.count { it.tBEnd != null }, done.count { it.cancelled },
            median(done.mapNotNull { it.transcriptLatencyMs() }), median(done.mapNotNull { it.repliesLatencyMs() }),
        ))

        assertTrue("no turn produced replies", done.any { it.tBEnd != null })
        if (backend == Backend.AUTO && graph.modelFiles.gemmaSoc != null) {
            assertTrue("AUTO should run on the accelerator, got ${done.map { it.backend }.toSet()}", done.all { it.backend == "NPU" })
        }
    }

    /** Swaps in [config] and [settings] without writing DataStore, once ConfigStore's own asynchronous load has landed. */
    private suspend fun overrideInMemory(store: ConfigStore, config: PipelineConfig, settings: (AppSettings) -> AppSettings) {
        delay(1_500)
        @Suppress("UNCHECKED_CAST")
        fun <T> flow(name: String) = ConfigStore::class.java.getDeclaredField(name).apply { isAccessible = true }.get(store) as MutableStateFlow<T>
        flow<PipelineConfig>("_config").value = config
        flow<AppSettings>("_settings").update(settings)
    }

    private companion object {
        const val TAG = "FixtureRunAndroidTest"
        const val FIXTURE = "demo_zh.wav"
        const val MAX_REPLAY_MS = 10 * 60_000L // a stalled replay fails here instead of hanging the run
    }
}
