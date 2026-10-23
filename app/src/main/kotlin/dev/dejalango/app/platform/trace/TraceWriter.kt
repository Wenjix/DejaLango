package dev.dejalango.app.platform.trace

import android.content.Context
import dev.dejalango.engine.trace.TurnTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Appends every TurnTrace to files/traces/YYYY-MM-DD.csv. `adb pull` it; it's the week's benchmark. */
class TraceWriter(context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "traces").apply { mkdirs() }
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Synchronized
    fun append(t: TurnTrace) {
        val f = File(dir, day.format(Date()) + ".csv")
        val fresh = !f.exists()
        f.appendText((if (fresh) TurnTrace.CSV_HEADER + "\n" else "") + t.toCsvRow() + "\n")
    }

    val path: String get() = dir.absolutePath
}
