package com.example.angrybot.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.example.angrybot.model.Candidate
import com.example.angrybot.model.DetectedObject
import com.example.angrybot.model.GameState
import com.example.angrybot.model.ObjectType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the image that is sent to Gemini: downscaled screenshot + a 0..1000 labeled grid,
 * detected objects and the numbered candidate trajectories from the physics planner.
 */
object GridOverlay {

    private val palette = intArrayOf(
        Color.rgb(255, 64, 64), Color.rgb(0, 200, 255), Color.rgb(255, 200, 0),
        Color.rgb(190, 90, 255), Color.rgb(0, 230, 120)
    )

    fun render(frame: Bitmap, state: GameState, cands: List<Candidate>, maxSide: Int = 1280): Bitmap {
        val scale = min(1f, maxSide / max(frame.width, frame.height).toFloat())
        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
        val base = if (scale < 1f) Bitmap.createScaledBitmap(frame, w, h, true) else frame
        // Always draw into a fresh mutable bitmap (Bitmap.copy() can return null / fail on hardware bitmaps).
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(base, 0f, 0f, null)
        if (base !== frame) base.recycle()
        val ts = h * 0.024f

        drawObjects(c, state, scale, ts)
        drawCandidates(c, cands, scale, ts)
        drawGrid(c, w, h, ts)
        return out
    }

    private fun label(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = size * 0.22f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
        c.drawText(text, x, y, stroke)
        c.drawText(text, x, y, fill)
    }

    private fun drawGrid(c: Canvas, w: Int, h: Int, ts: Float) {
        val line = Paint().apply { color = Color.argb(95, 255, 255, 255); strokeWidth = 1.4f }
        for (i in 1..9) {
            val x = w * i / 10f
            val y = h * i / 10f
            c.drawLine(x, 0f, x, h.toFloat(), line)
            c.drawLine(0f, y, w.toFloat(), y, line)
        }
        for (i in 0..10) {
            val v = (i * 100).toString()
            val x = (w * i / 10f - ts * 0.9f).coerceIn(2f, w - ts * 2.2f)
            val y = (h * i / 10f + ts * 0.4f).coerceIn(ts, h - 4f)
            label(c, v, x, ts, ts * 0.85f, Color.YELLOW)
            label(c, v, x, h - 4f, ts * 0.85f, Color.YELLOW)
            label(c, v, 3f, y, ts * 0.85f, Color.CYAN)
            label(c, v, w - ts * 2.3f, y, ts * 0.85f, Color.CYAN)
        }
        // small intersection labels every 200 units to help precise reading
        for (ix in 2..8 step 2) for (iy in 2..8 step 2) {
            label(c, "${ix * 100},${iy * 100}", w * ix / 10f + 3f, h * iy / 10f - 3f, ts * 0.6f, Color.WHITE)
        }
    }

    private fun box(c: Canvas, o: DetectedObject, s: Float, color: Int, tag: String, ts: Float) {
        val p = Paint().apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRect(o.left * s, o.top * s, o.right * s, o.bottom * s, p)
        label(c, tag, o.left * s, o.top * s - 3f, ts * 0.8f, color)
    }

    private fun drawObjects(c: Canvas, st: GameState, s: Float, ts: Float) {
        st.pigs.forEach { box(c, it, s, Color.GREEN, "pig", ts) }
        st.birds.forEach { box(c, it, s, Color.RED, if ((it.features["active"] ?: 0f) > 0.5f) "bird*" else "bird", ts) }
        st.of(ObjectType.SLINGSHOT).forEach { box(c, it, s, Color.YELLOW, "sling", ts) }
    }

    private fun drawCandidates(c: Canvas, cands: List<Candidate>, s: Float, ts: Float) {
        cands.take(5).forEachIndexed { idx, cd ->
            val col = palette[idx % palette.size]
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col; style = Paint.Style.STROKE; strokeWidth = 3.5f; alpha = 200
            }
            val path = Path()
            cd.trajectory.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x * s, p.y * s) else path.lineTo(p.x * s, p.y * s)
            }
            c.drawPath(path, line)
            val bx = cd.impact.x * s
            val by = cd.impact.y * s
            val r = ts * 0.75f
            c.drawCircle(bx, by, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
            label(c, cd.id.toString(), bx - ts * 0.28f, by + ts * 0.3f, ts * 0.9f, Color.BLACK)
        }
    }
}
