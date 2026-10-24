package dev.dejalango.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal enum class Mark { HOME, CHAT, BOOK, MIC, SETTINGS, SWAP, GLASSES, PAUSE, PLAY, CHECK, BACK, NEXT, PERSON, SPARK, CLOSE }

/** Geometric icons in the selected study's consistent 1.7/24 stroke. Labels belong to their controls. */
@Composable
internal fun MarkIcon(mark: Mark, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    Canvas(modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val pen = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Float) {
                val p = Path(); p.moveTo(points[0], points[1]); var i = 2
                while (i < points.size) { p.lineTo(points[i], points[i + 1]); i += 2 }; drawPath(p, color, style = pen)
            }
            when (mark) {
                Mark.HOME -> { path(3f, 11f, 12f, 3f, 21f, 11f); path(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f); path(10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f) }
                Mark.CHAT -> { drawRoundRect(color, Offset(4f, 3f), Size(17f, 15f), CornerRadius(5f), style = pen); path(4f, 14f, 3f, 22f, 10f, 18f); line(8f, 8f, 17f, 8f); line(8f, 12f, 14f, 12f) }
                Mark.BOOK -> { path(12f, 5f, 8f, 3f, 2f, 3f, 2f, 19f, 8f, 19f, 12f, 21f, 16f, 19f, 22f, 19f, 22f, 3f, 16f, 3f, 12f, 5f, 12f, 21f) }
                Mark.MIC -> { drawRoundRect(color, Offset(9f, 2f), Size(6f, 13f), CornerRadius(3f), style = pen); drawArc(color, 0f, 180f, false, Offset(5f, 7f), Size(14f, 12f), style = pen); line(12f, 19f, 12f, 22f); line(8f, 22f, 16f, 22f) }
                Mark.SETTINGS -> { listOf(6f, 12f, 18f).forEachIndexed { i, y -> line(3f, y, 21f, y); drawCircle(color, 2.3f, Offset(if (i == 1) 8f else 16f, y), style = pen) } }
                Mark.SWAP -> { path(4f, 7f, 20f, 7f, 16f, 3f); path(20f, 17f, 4f, 17f, 8f, 21f) }
                Mark.GLASSES -> { drawRoundRect(color, Offset(2f, 9f), Size(8f, 8f), CornerRadius(3f), style = pen); drawRoundRect(color, Offset(14f, 9f), Size(8f, 8f), CornerRadius(3f), style = pen); drawArc(color, 180f, 180f, false, Offset(10f, 9f), Size(4f, 4f), style = pen); line(3f, 10f, 5f, 4f); line(21f, 10f, 19f, 4f) }
                Mark.PAUSE -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
                Mark.PLAY -> path(8f, 4f, 19f, 12f, 8f, 20f, 8f, 4f)
                Mark.CHECK -> path(4f, 12f, 9f, 17f, 20f, 6f)
                Mark.BACK -> { path(14f, 5f, 7f, 12f, 14f, 19f); line(7f, 12f, 21f, 12f) }
                Mark.NEXT -> { path(10f, 5f, 17f, 12f, 10f, 19f); line(3f, 12f, 17f, 12f) }
                Mark.PERSON -> { drawCircle(color, 3f, Offset(12f, 6f), style = pen); drawArc(color, 180f, 180f, false, Offset(5f, 12f), Size(14f, 17f), style = pen) }
                Mark.SPARK -> path(12f, 2f, 15f, 9f, 22f, 12f, 15f, 15f, 12f, 22f, 9f, 15f, 2f, 12f, 9f, 9f, 12f, 2f)
                Mark.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            }
        }
    }
}
/** The original transparent artwork is cropped at draw time, never redrawn or approximated. */
@Composable
internal fun Buddy(pose: Int, modifier: Modifier = Modifier) {
    val bitmap = companionBitmap("buddy_poses.webp")
    val side = bitmap.width / 2
    Canvas(modifier) {
        drawImage(bitmap, srcOffset = IntOffset(pose % 2 * side, pose / 2 * side), srcSize = IntSize(side, side),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

@Composable
internal fun companionBitmap(name: String): ImageBitmap {
    val assets = LocalContext.current.assets
    return remember(assets, name) { assets.open("companion/$name").use { BitmapFactory.decodeStream(it).asImageBitmap() } }
}
