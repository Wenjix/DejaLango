package dev.dejalango.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dejalango.engine.playbook.IndexedLine
import dev.dejalango.engine.playbook.LineKind
import dev.dejalango.engine.playbook.Playbook
import dev.dejalango.engine.playbook.PlaybookMatch
import dev.dejalango.engine.voice.EmbedRole
import dev.dejalango.engine.voice.Vectors
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How far apart the seeded playbook's moments sit for EmbeddingGemma on this phone's accelerator, through the app's own
 * wiring ([dev.dejalango.app.di.AppGraph.createEmbedder]), to choose the threshold and margin of
 * [dev.dejalango.engine.playbook.PlaybookIndex.match]. Each of the local's phrasings is a query with its own line left
 * out, as a transcript would be: its moment's other phrasings should be nearest, and no other moment's. A measurement:
 * it logs per scene and overall, and asserts nothing. Skipped until the accelerator bundle is pushed
 * (scripts/push_models.sh) and the build carries assets/playbook/zh-CN.json, and whenever another embedder wins or
 * takes over while embedding: these scores are that bundle's only.
 *
 * Run it with `am instrument`, never with connectedAndroidTest: that uninstalls the app afterwards, and the pushed
 * models with it. Logcat has every line under the tag PlaybookCalibration, and `-r` prints each one as a status:
 *
 *     adb shell am instrument -w -r -e class dev.dejalango.app.PlaybookCalibrationAndroidTest \
 *       dev.dejalango.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PlaybookCalibrationAndroidTest {

    /** The nearest of some lines to a query, and its score; [PlaybookMatch.NONE] and no id when the scene has no such line. */
    private class Near(val score: Float, val id: String?)

    /**
     * One of the local's phrasings, embedded as a query, against the other lines of its scene: [pos] is the nearest other
     * phrasing of its moment, [neg] the nearest phrasing of another moment, [wear] the nearest of the wearer's lines.
     */
    private class Probe(val line: IndexedLine, val pos: Near, val neg: Near, val wear: Near) {
        /** The match would find its moment: a phrasing of it is nearest, at [threshold] or more, and ahead of other moments' by [margin]. */
        fun right(threshold: Float, margin: Float): Boolean = pos.score >= threshold && pos.score - neg.score >= margin && pos.score > wear.score

        /** The match would find another moment. Only this moment's phrasings count as the runner-up, so this is an upper bound. */
        fun wrong(threshold: Float, margin: Float): Boolean = neg.score >= threshold && neg.score - pos.score >= margin && neg.score > wear.score
    }

    /** A threshold and margin, and how many probes it lands on their own moment and on a wrong one. */
    private class Setting(val threshold: Float, val margin: Float, val right: Int, val wrong: Int)

    @Test
    fun measureThresholdAndMarginOnThisPhonesAccelerator() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = ctx.appGraph
        val files = graph.modelFiles
        val soc = files.embedderSoc
        assumeTrue("no EmbeddingGemma accelerator bundle in ${files.dir}", soc != null)
        val json = ctx.assetText(ASSET)
        assumeTrue("no $ASSET in the app's assets", json != null)
        val playbook = Playbook.parse(json!!)
        val embedder = graph.createEmbedder(useFake = false)
        try {
            embedder.warm()
            val description = embedder.description
            assumeTrue("expected ${soc!!.name} to win, got: $description", description.contains(soc.nameWithoutExtension))

            // The lines PlaybookIndex.build indexes, in its order: each moment's local lines, then its replies and others.
            val lines = playbook.scenes.flatMap { scene ->
                scene.moments.flatMap { m ->
                    m.local.map { IndexedLine(scene, m, it.id, it.text, LineKind.LOCAL) } +
                        (m.replies + m.others).map { IndexedLine(scene, m, it.id, it.text, LineKind.WEARER) }
                }
            }
            val locals = lines.filter { it.kind == LineKind.LOCAL }
            val documents = embedder.embed(lines.map { it.text }, EmbedRole.DOCUMENT)
            val queries = embedder.embed(locals.map { it.text }, EmbedRole.QUERY)
            // The embedder falls back to hashing inside embed(): its vectors don't compare with EmbeddingGemma's.
            assumeTrue("the embedder fell back while embedding: ${embedder.description}", embedder.description == description)
            report("embedder: $description · $ASSET: ${playbook.scenes.size} scenes, ${locals.size} local lines, ${lines.size - locals.size} wearer lines")

            val probes = locals.mapIndexed { i, q ->
                val scores = FloatArray(lines.size) { Vectors.cosine(queries[i], documents[it]) }
                fun near(include: (IndexedLine) -> Boolean): Near {
                    val best = lines.indices.filter { lines[it].scene.id == q.scene.id && include(lines[it]) }.maxByOrNull { scores[it] }
                    return if (best == null) Near(PlaybookMatch.NONE, null) else Near(scores[best], lines[best].id)
                }
                Probe(
                    q,
                    pos = near { it.kind == LineKind.LOCAL && it.moment.id == q.moment.id && it.id != q.id },
                    neg = near { it.kind == LineKind.LOCAL && it.moment.id != q.moment.id },
                    wear = near { it.kind == LineKind.WEARER },
                )
            }
            for (scene in playbook.scenes) summarize("scene ${scene.id}", probes.filter { it.line.scene.id == scene.id })
            summarize("overall", probes)
        } finally {
            embedder.close()
        }
    }

    /** [probes]' scores, the share of them each threshold and margin lands on their own moment and on a wrong one, and the best of those. */
    private fun summarize(scope: String, probes: List<Probe>) {
        val pos = probes.filter { it.pos.id != null }.sortedBy { it.pos.score }
        val neg = probes.filter { it.neg.id != null }.sortedBy { it.neg.score }
        val wear = probes.sortedBy { it.wear.score }
        fun List<Probe>.score(p: Double, near: (Probe) -> Near) = f3(quantile(p)?.let(near)?.score)
        fun List<Probe>.joins(p: Double, near: (Probe) -> Near) = quantile(p)?.let { "${it.line.id} ~ ${near(it).id}" } ?: "–"
        val alone = probes.size - pos.size
        report(
            "$scope: ${probes.size} local lines" + (if (alone > 0) ", $alone with no other phrasing" else "") +
                " · pos min ${pos.score(0.0) { it.pos }} p10 ${pos.score(0.1) { it.pos }} median ${pos.score(0.5) { it.pos }}" +
                " · neg median ${neg.score(0.5) { it.neg }} p90 ${neg.score(0.9) { it.neg }} max ${neg.score(1.0) { it.neg }}" +
                " · wear max ${wear.score(1.0) { it.wear }}",
        )
        report("$scope extremes: pos min ${pos.joins(0.0) { it.pos }} · neg max ${neg.joins(1.0) { it.neg }} · wear max ${wear.joins(1.0) { it.wear }}")

        val rows = THRESHOLDS.map { t -> MARGINS.map { m -> Setting(t, m, probes.count { it.right(t, m) }, probes.count { it.wrong(t, m) }) } }
        for (row in rows) {
            report("$scope threshold ${f2(row.first().threshold)}: " + row.joinToString(" · ") { "margin ${f2(it.margin)} right ${pct(it.right, probes.size)} wrong ${pct(it.wrong, probes.size)}" })
        }
        val safe = rows.flatten().filter { it.right > 0 && it.wrong == 0 }
        if (safe.isEmpty()) {
            report("$scope best: no threshold and margin here lands a line on its own moment without landing one on another")
            return
        }
        val most = safe.maxOf { it.right }
        // Ties go to the largest margin, then to the middle of its tied thresholds, the farthest from either edge.
        val margin = safe.filter { it.right == most }.maxOf { it.margin }
        val tied = safe.filter { it.right == most && it.margin == margin }
        val best = tied[tied.size / 2]
        report(
            "$scope best with no wrong hit: threshold ${f2(best.threshold)}, margin ${f2(margin)}, right ${pct(most, probes.size)} ($most of ${probes.size})" +
                if (tied.size > 1) "; ${f2(tied.first().threshold)} to ${f2(tied.last().threshold)} tie at that margin" else "",
        )
    }

    /** One line of the report, to Logcat and as an instrumentation status, which `am instrument -r` prints. */
    private fun report(line: String) {
        Log.i(TAG, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(IN_PROGRESS, Bundle().apply { putString(TAG, line) })
    }

    private companion object {
        const val TAG = "PlaybookCalibration"
        const val ASSET = "playbook/zh-CN.json"

        /** The status code for output during a test: as the runner reports them, 1 starts a test and 0 or less ends one. */
        const val IN_PROGRESS = 2

        val THRESHOLDS = (70..95).map { it / 100f }
        val MARGINS = listOf(0f, 0.02f, 0.03f, 0.05f)
    }
}

/** The app's asset at [path] as UTF-8, or null when the build has none. */
private fun Context.assetText(path: String): String? = try {
    assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
} catch (e: FileNotFoundException) {
    null
}

/** The element at quantile [p] of this sorted list, by nearest rank, or null when it is empty. */
private fun <T> List<T>.quantile(p: Double): T? = if (isEmpty()) null else this[((size - 1) * p).roundToInt()]

/** Two decimals in any locale, so logs read the same on every phone. */
private fun f2(x: Float): String = "%.2f".format(Locale.ROOT, x)

/** Three decimals in any locale, or a dash for no score. */
private fun f3(x: Float?): String = if (x == null) "–" else "%.3f".format(Locale.ROOT, x)

/** [n] of [total] as a percentage, to one decimal. */
private fun pct(n: Int, total: Int): String = if (total == 0) "–" else "%.1f%%".format(Locale.ROOT, 100.0 * n / total)
