package com.example.angrybot.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.angrybot.model.GameState

data class DetectionConfig(
    val ignoreRects: List<Rect> = emptyList(),
    val analysisWidth: Int = 400
)

/** Swap this interface's implementation to plug in a TFLite / OpenCV detector later. */
interface ObjectDetector {
    fun detect(frame: Bitmap, config: DetectionConfig): GameState
}
