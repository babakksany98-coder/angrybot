package com.example.angrybot.planner

import com.example.angrybot.model.Candidate
import com.example.angrybot.model.DetectedObject
import com.example.angrybot.model.GameState
import com.example.angrybot.model.MoveResult
import com.example.angrybot.model.ObjectType
import com.example.angrybot.model.Vec2
import com.example.angrybot.physics.PhysicsPredictor
import com.example.angrybot.physics.Trajectory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Samples an (angle x power) grid, simulates each shot with the PhysicsPredictor, scores it
 * (pig hits, splash, structure interaction, stone/ground penalties, history of failed shots)
 * and returns up to 5 diverse candidates, best first.
 */
class TrajectoryPlanner(private val physics: PhysicsPredictor) {

    private class Rec(val angle: Float, val power: Float, val success: Boolean)

    private val history = ArrayList<Rec>()

    fun reset() { history.clear() }

    fun record(c: Candidate, r: MoveResult) {
        history.add(Rec(c.angleDeg, c.power, r.success))
    }

    fun plan(state: GameState): List<Candidate> {
        val bird = state.activeBird ?: return emptyList()
        if (state.pigs.isEmpty()) return emptyList()
        val origin = Vec2(bird.centerX, bird.centerY)
        val r = bird.radius.coerceIn(state.height * 0.015f, state.height * 0.05f)
        val obstacles = state.pigs + state.structures
        val maxDrag = physics.params.maxDragPx
        val all = ArrayList<Candidate>()

        for (ai in 0..18) {
            val angle = 8f + 4f * ai
            val rad = Math.toRadians(angle.toDouble())
            for (pi in 0..10) {
                val power = 0.35f + 0.065f * pi
                val len = maxDrag * power
                val drag = Vec2((-cos(rad) * len).toFloat(), (sin(rad) * len).toFloat())
                val end = origin + drag
                if (end.x < 2f || end.y > state.groundY - 2f) continue
                val traj = physics.predict(origin, drag, obstacles, state.groundY, state.width.toFloat(), r)
                val (base, target) = score(traj, state.pigs, state.width.toFloat(), state.birds.size)
                var sc = base
                for (h in history) {
                    if (!h.success && abs(h.angle - angle) < 5f && abs(h.power - power) < 0.1f) sc -= 40f
                }
                all.add(
                    Candidate(0, origin, end, angle, power, target, traj.impact, traj.points, sc, traj.hitType, traj.apexY)
                )
            }
        }
        all.sortByDescending { it.score }
        val picked = ArrayList<Candidate>()
        for (c in all) {
            if (picked.size >= 7) break
            val tooSimilar = picked.any {
                abs(it.angleDeg - c.angleDeg) < 5f &&
                abs(it.power - c.power) < 0.12f &&
                it.target?.let { a -> c.target?.let { b -> a.centerX == b.centerX && a.centerY == b.centerY } ?: false } == true
            }
            if (!tooSimilar) picked.add(c.copy(id = picked.size + 1))
        }
        return picked
    }

    private fun score(
        traj: Trajectory, pigs: List<DetectedObject>, width: Float, birdsLeft: Int
    ): Pair<Float, DetectedObject?> {
        var nearest: DetectedObject? = null
        var best = Float.MAX_VALUE
        for (p in pigs) {
            val d = hypot(traj.impact.x - p.centerX, traj.impact.y - p.centerY)
            if (d < best) { best = d; nearest = p }
        }
        val hit = traj.hit
        if (traj.hitType == ObjectType.PIG && hit != null) {
            val splash = pigs.count { it !== hit && hypot(it.centerX - hit.centerX, it.centerY - hit.centerY) < hit.radius * 3.5f }
            val edgeBonus = if (traj.impact.y < hit.centerY) 4f else 0f
            return Pair(140f + 25f * splash + edgeBonus, hit)
        }
        val urgency = if (birdsLeft <= 1) 1.3f else 1f
        var s = 70f * exp(-best / (0.10f * width)) * urgency
        when (traj.hitType) {
            ObjectType.WOOD, ObjectType.GLASS -> s += 8f
            ObjectType.STONE -> s -= 12f
            ObjectType.GROUND -> if (best > 0.15f * width) s -= 10f
            else -> s -= 15f
        }
        return Pair(s, nearest)
    }
}
