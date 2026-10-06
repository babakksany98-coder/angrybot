package com.example.angrybot.planner

import com.example.angrybot.model.Candidate
import com.example.angrybot.model.GameState
import com.example.angrybot.model.LevelStatus
import com.example.angrybot.model.MoveResult

/** Compares two independently detected game states without assuming that a missing detection is a kill. */
object ResultEvaluator {
    fun evaluate(before: GameState, after: GameState, c: Candidate): MoveResult {
        val completed = after.status == LevelStatus.LEVEL_COMPLETED
        val beforeCount = before.pigs.size
        val afterCount = after.pigs.size

        // A zero-pig frame can be a detector miss. Only treat it as a confirmed clear when
        // the level detector itself agrees; otherwise require an actual count reduction.
        val countReduction = (beforeCount - afterCount).coerceAtLeast(0)
        val confirmedTargets = if (completed) beforeCount.coerceAtLeast(1) else countReduction
        val destroyedObjects = (before.structures.size - after.structures.size).coerceAtLeast(0)

        val confidenceSamples = before.pigs.map { it.confidence } + after.pigs.map { it.confidence }
        val conf = if (confidenceSamples.isEmpty()) 0.5f else confidenceSamples.average().toFloat().coerceIn(0f, 1f)
        val success = completed || confirmedTargets > 0

        return MoveResult(
            success = success,
            destroyedTargets = confirmedTargets,
            destroyedObjects = destroyedObjects,
            remainingTargets = afterCount,
            scoreChange = confirmedTargets * 100 + destroyedObjects * 10,
            confidence = conf
        )
    }
}
