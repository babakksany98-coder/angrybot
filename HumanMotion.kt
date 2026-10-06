package com.example.angrybot.automation

import com.example.angrybot.model.Vec2
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Generates natural-looking finger motion for slingshot pulls and taps:
 * a slightly curved, slightly shaky path; fast start, slow finish (aiming); Gaussian timing.
 * The final point is always EXACTLY the requested end point, so aim precision is never degraded.
 * Pure Kotlin (no Android types) so it can be unit-tested.
 */
object HumanMotion {

    class Segment(val from: Vec2, val to: Vec2, val durationMs: Long)

    class DragPlan(val segments: List<Segment>, val holdMs: Long)

    private val rnd = Random()

    fun gaussian(mean: Double, sd: Double, lo: Double, hi: Double): Double =
        (mean + rnd.nextGaussian() * sd).coerceIn(lo, hi)

    /** Human-like pause (ms) around [meanMs]. */
    fun delayMs(meanMs: Long, relSd: Double = 0.22): Long =
        gaussian(meanMs.toDouble(), meanMs * relSd, meanMs * 0.5, meanMs * 1.8).toLong().coerceAtLeast(0L)

    fun planDrag(start: Vec2, end: Vec2, durationMs: Long, holdMs: Long, steps: Int = 12): DragPlan {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / dist          // unit normal to the drag direction
        val ny = dx / dist

        // Cubic Bezier control points offset sideways -> gentle arc instead of a ruler-straight line.
        val bend = gaussian(0.0, dist * 0.035, -dist * 0.09, dist * 0.09).toFloat()
        val bend2 = gaussian(bend * 0.6, dist * 0.02, -dist * 0.09, dist * 0.09).toFloat()
        val p1 = Vec2(start.x + dx / 3f + nx * bend, start.y + dy / 3f + ny * bend)
        val p2 = Vec2(start.x + 2f * dx / 3f + nx * bend2, start.y + 2f * dy / 3f + ny * bend2)

        val points = ArrayList<Vec2>(steps + 1)
        points.add(start)
        for (i in 1 until steps) {
            val t = i / steps.toFloat()
            val u = 1f - t
            var x = u * u * u * start.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * end.x
            var y = u * u * u * start.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * end.y
            // micro tremor (smaller near the end so aiming stays steady)
            val tremor = (1.6 * (1.0 - t * 0.7)).coerceAtLeast(0.3)
            x += (rnd.nextGaussian() * tremor).toFloat()
            y += (rnd.nextGaussian() * tremor).toFloat()
            points.add(Vec2(x, y))
        }
        points.add(end)

        // Time weights grow toward the end -> fast launch of the pull, slow final aiming.
        val total = gaussian(durationMs.toDouble(), durationMs * 0.08, durationMs * 0.75, durationMs * 1.3)
        val weights = DoubleArray(steps) { i -> 0.55 + 1.6 * (i / (steps - 1.0)).pow(1.6) }
        val wSum = weights.sum()
        val segs = ArrayList<Segment>(steps)
        for (i in 0 until steps) {
            val d = max(8L, (total * weights[i] / wSum).toLong())
            segs.add(Segment(points[i], points[i + 1], d))
        }
        val hold = if (holdMs <= 0L) 0L else gaussian(holdMs.toDouble(), holdMs * 0.1, holdMs * 0.7, holdMs * 1.4).toLong()
        return DragPlan(segs, hold)
    }

    /** Small offset for taps on buttons (never used for aiming). */
    fun tapPoint(p: Vec2, jitterPx: Float = 3f): Vec2 =
        Vec2(
            p.x + gaussian(0.0, jitterPx / 2.0, -jitterPx.toDouble(), jitterPx.toDouble()).toFloat(),
            p.y + gaussian(0.0, jitterPx / 2.0, -jitterPx.toDouble(), jitterPx.toDouble()).toFloat()
        )

    fun tapDurationMs(): Long = gaussian(75.0, 15.0, 40.0, 140.0).toLong()

    fun clampPoint(p: Vec2, w: Float, h: Float) = Vec2(min(max(p.x, 1f), w - 2f), min(max(p.y, 1f), h - 2f))
}
