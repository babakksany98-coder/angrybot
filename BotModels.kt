package com.example.angrybot.model

import android.graphics.Bitmap

data class BotUiState(
    val running: Boolean = false,
    val detection: String = "idle",
    val levelStatus: LevelStatus = LevelStatus.UNKNOWN,
    val target: String = "-",
    val angle: Float = 0f,
    val power: Float = 0f,
    val lastAction: String = "-",
    val confidence: Float = 0f
)

data class BotSettings(
    val debug: Boolean = true,
    val simulation: Boolean = false,
    val dragDurationMs: Long = 450L,
    val holdMs: Long = 250L,
    val settleTimeoutMs: Long = 9000L,
    val loopDelayMs: Long = 600L,
    val debugShowMs: Long = 700L,
    val minBirdConfidence: Float = 0.48f,
    val minPigConfidence: Float = 0.45f,
    val postShotDelayMs: Long = 650L,
    val useGemini: Boolean = true,
    val geminiTimeoutMs: Long = 45000L,
    val geminiMinIntervalMs: Long = 1500L
)

data class DebugSnapshot(
    val state: GameState,
    val candidates: List<Candidate>,
    val best: Candidate?,
    val image: Bitmap?
)
