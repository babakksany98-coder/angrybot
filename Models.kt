package com.example.angrybot.model

import kotlin.math.hypot

/** Add new types here, then add a matching rule in vision/ColorRules.kt */
enum class ObjectType { BIRD, PIG, WOOD, STONE, GLASS, SLINGSHOT, GROUND, UNKNOWN }

enum class LevelStatus { LEVEL_RUNNING, LEVEL_COMPLETED, LEVEL_FAILED, UNKNOWN }

data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    fun dist(o: Vec2): Float = hypot(x - o.x, y - o.y)
}

data class DetectedObject(
    val type: ObjectType,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val features: Map<String, Float> = emptyMap(),
    val rotation: Float = 0f
) {
    val left: Float get() = centerX - width / 2f
    val right: Float get() = centerX + width / 2f
    val top: Float get() = centerY - height / 2f
    val bottom: Float get() = centerY + height / 2f
    val radius: Float get() = (width + height) / 4f
}

data class GameState(
    val width: Int,
    val height: Int,
    val objects: List<DetectedObject>,
    val groundY: Float,
    val status: LevelStatus = LevelStatus.UNKNOWN,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun of(type: ObjectType): List<DetectedObject> = objects.filter { it.type == type }
    val birds: List<DetectedObject> get() = of(ObjectType.BIRD)
    val pigs: List<DetectedObject> get() = of(ObjectType.PIG)
    val structures: List<DetectedObject>
        get() = objects.filter {
            it.type == ObjectType.WOOD || it.type == ObjectType.STONE || it.type == ObjectType.GLASS
        }
    val slingshot: DetectedObject? get() = of(ObjectType.SLINGSHOT).firstOrNull()
    val activeBird: DetectedObject? get() = birds.firstOrNull { (it.features["active"] ?: 0f) > 0.5f }
}

data class Candidate(
    val id: Int,
    val dragStart: Vec2,
    val dragEnd: Vec2,
    val angleDeg: Float,
    val power: Float,
    val target: DetectedObject?,
    val impact: Vec2,
    val trajectory: List<Vec2>,
    val score: Float,
    val hitType: ObjectType?,
    val apexY: Float
)

data class MoveResult(
    val success: Boolean,
    val destroyedTargets: Int,
    val destroyedObjects: Int,
    val remainingTargets: Int,
    val scoreChange: Int,
    val confidence: Float
)
