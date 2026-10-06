package com.example.angrybot.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.example.angrybot.model.Candidate
import com.example.angrybot.model.GameState
import com.example.angrybot.model.ObjectType
import kotlin.math.min

object DebugRenderer {

    private fun color(t: ObjectType): Int = when (t) {
        ObjectType.BIRD -> Color.RED
        ObjectType.PIG -> Color.GREEN
        ObjectType.WOOD -> 0xFFFF9800.toInt()
        ObjectType.STONE -> Color.LTGRAY
        ObjectType.GLASS -> Color.CYAN
        ObjectType.SLINGSHOT -> Color.YELLOW
        ObjectType.GROUND -> Color.DKGRAY
        ObjectType.UNKNOWN -> Color.MAGENTA
    }

    private fun path(c: Candidate, sx: Float, sy: Float): Path {
        val p = Path()
        c.trajectory.forEachIndexed { i, pt ->
            if (i == 0) p.moveTo(pt.x * sx, pt.y * sy) else p.lineTo(pt.x * sx, pt.y * sy)
        }
        return p
    }

    fun draw(canvas: Canvas, state: GameState, cands: List<Candidate>, best: Candidate?, sx: Float, sy: Float) {
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; style = Paint.Style.FILL }

        for (o in state.objects) {
            if (o.type == ObjectType.GROUND) {
                box.color = color(o.type)
                canvas.drawLine(0f, state.groundY * sy, state.width * sx, state.groundY * sy, box)
                continue
            }
            box.color = color(o.type)
            canvas.drawRect(o.left * sx, o.top * sy, o.right * sx, o.bottom * sy, box)
            txt.color = color(o.type)
            val active = if ((o.features["active"] ?: 0f) > 0.5f) "*" else ""
            canvas.drawText("${o.type.name}$active ${(o.confidence * 100).toInt()}%", o.left * sx, o.top * sy - 4f, txt)
        }

        val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x88FFFFFF.toInt() }
        for (c in cands) if (c !== best && c.id != best?.id) canvas.drawPath(path(c, sx, sy), thin)

        if (best != null) {
            val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.YELLOW }
            canvas.drawPath(path(best, sx, sy), thick)
            val drag = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
            canvas.drawLine(best.dragStart.x * sx, best.dragStart.y * sy, best.dragEnd.x * sx, best.dragEnd.y * sy, drag)
            val ix = best.impact.x * sx
            val iy = best.impact.y * sy
            val x = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.RED }
            canvas.drawLine(ix - 18f, iy - 18f, ix + 18f, iy + 18f, x)
            canvas.drawLine(ix - 18f, iy + 18f, ix + 18f, iy - 18f, x)
            txt.color = Color.WHITE
            canvas.drawText("angle=%.0f power=%.0f%% score=%.0f".format(best.angleDeg, best.power * 100, best.score), 12f, 28f, txt)
        }
    }

    /** Renders a downscaled screenshot with all debug graphics burned in. */
    fun renderBitmap(frame: Bitmap, state: GameState, cands: List<Candidate>, best: Candidate?): Bitmap {
        val sc = min(1f, 1280f / frame.width)
        val w = (frame.width * sc).toInt().coerceAtLeast(1)
        val h = (frame.height * sc).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(frame, null, Rect(0, 0, w, h), null)
        draw(canvas, state, cands, best, sc, sc)
        return out
    }
}
