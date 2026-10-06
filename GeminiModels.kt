package com.example.angrybot.ai

/** What Gemini believes is currently on screen. */
enum class ScreenState {
    PLAYING, LEVEL_COMPLETE, LEVEL_FAILED, OUT_OF_HEARTS, AD_PLAYING, MENU, UNKNOWN
}

enum class AiAction { DRAG, CLICK, WAIT, STOP }

/**
 * Parsed Gemini answer. All coordinates are normalised to a 0..1000 grid
 * (x to the right, y downwards) of the image that was sent; -1 means "not provided".
 */
data class GeminiDecision(
    val state: ScreenState,
    val action: AiAction,
    val candidateId: Int,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val buttonLabel: String,
    val waitMs: Long,
    val reason: String
) {
    val hasStart: Boolean get() = startX >= 0f && startY >= 0f
    val hasEnd: Boolean get() = endX >= 0f && endY >= 0f
}

class GeminiException(message: String, val retryable: Boolean) : Exception(message)
