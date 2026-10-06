package com.example.angrybot.physics

import com.example.angrybot.model.DetectedObject
import com.example.angrybot.model.ObjectType
import com.example.angrybot.model.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** All values are in screen pixels / seconds (y grows downward). */
class PhysicsParams(
    var gravity: Float,
    var speedScale: Float,   // launch speed (px/s) per pixel of drag
    var maxDragPx: Float
) {
    companion object {
        fun defaults(w: Int, h: Int): PhysicsParams {
            val g = h * 1.0f
            val maxDrag = h * 0.16f
            val vMax = sqrt(0.9f * w * g)
            return PhysicsParams(g, vMax / maxDrag, maxDrag)
        }
    }
}

class Trajectory(
    val points: List<Vec2>,
    val impact: Vec2,
    val hit: DetectedObject?,
    val hitType: ObjectType?,
    val flightTime: Float,
    val apexY: Float
)

class PhysicsPredictor(val params: PhysicsParams) {

    /**
     * Predicts a ballistic path. [drag] is the vector from the bird's rest position to the
     * pulled-back position; launch velocity is the opposite direction.
     */
    fun predict(
        origin: Vec2,
        drag: Vec2,
        obstacles: List<DetectedObject>,
        groundY: Float,
        width: Float,
        birdRadius: Float,
        maxTime: Float = 5f
    ): Trajectory {
        val vx = -drag.x * params.speedScale
        val vy = -drag.y * params.speedScale
        val dt = 0.016f
        val pts = ArrayList<Vec2>()
        var t = 0f
        var apex = origin.y
        var hit: DetectedObject? = null
        var pos = origin
        var grounded = false
        while (t < maxTime) {
            val x = origin.x + vx * t
            val y = origin.y + vy * t + 0.5f * params.gravity * t * t
            pos = Vec2(x, y)
            pts.add(pos)
            if (y < apex) apex = y
            if (t > 0.05f) {
                val o = collide(pos, obstacles, birdRadius, origin)
                if (o != null) { hit = o; break }
                if (y + birdRadius >= groundY) { grounded = true; pos = Vec2(x, groundY - birdRadius); break }
            }
            if (x > width * 1.05f || x < -width * 0.1f) break
            t += dt
        }
        val type = hit?.type ?: if (grounded) ObjectType.GROUND else null
        return Trajectory(pts, pos, hit, type, t, apex)
    }

    private fun collide(p: Vec2, obstacles: List<DetectedObject>, r: Float, origin: Vec2): DetectedObject? {
        for (o in obstacles) {
            if (hypot(o.centerX - origin.x, o.centerY - origin.y) < r * 2f) continue
            if (o.type == ObjectType.PIG) {
                if (hypot(p.x - o.centerX, p.y - o.centerY) < o.radius + r * 0.8f) return o
            } else {
                val hw = o.width / 2f * 0.85f + r * 0.8f
                val hh = o.height / 2f * 0.85f + r * 0.8f
                if (abs(p.x - o.centerX) <= hw && abs(p.y - o.centerY) <= hh) return o
            }
        }
        return null
    }
}
