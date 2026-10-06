package com.example.angrybot.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.angrybot.model.GameState
import com.example.angrybot.model.LevelStatus

/** Image based level status: dim overlay + star colour, pig count history, bird absence. */
class LevelStateDetector {
    private var baseline = -1f
    private var seenPigs = false
    private var noPigFrames = 0
    private var lastBirdTime = System.currentTimeMillis()

    fun reset() {
        baseline = -1f
        seenPigs = false
        noPigFrames = 0
        lastBirdTime = System.currentTimeMillis()
    }

    private fun stats(frame: Bitmap): Pair<Float, Float> {
        val gw = 48
        val gh = 27
        var sumV = 0f
        var cnt = 0
        var yel = 0
        var ycnt = 0
        val hsv = FloatArray(3)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = ((gx + 0.5f) * frame.width / gw).toInt().coerceIn(0, frame.width - 1)
            val y = ((gy + 0.5f) * frame.height / gh).toInt().coerceIn(0, frame.height - 1)
            Color.colorToHSV(frame.getPixel(x, y), hsv)
            sumV += hsv[2]
            cnt++
            if (gx in 12..35 && gy in 6..20) {
                ycnt++
                if (hsv[0] in 40f..58f && hsv[1] > 0.7f && hsv[2] > 0.85f) yel++
            }
        }
        return Pair(sumV / cnt, yel.toFloat() / ycnt)
    }

    fun update(frame: Bitmap, state: GameState): LevelStatus {
        val now = state.timestamp
        val (meanV, yellow) = stats(frame)
        val dim = baseline > 0f && meanV < baseline * 0.70f
        if (!dim) baseline = if (baseline < 0f) meanV else baseline * 0.9f + meanV * 0.1f

        if (state.pigs.isNotEmpty()) {
            seenPigs = true
            noPigFrames = 0
        } else noPigFrames++
        if (state.birds.isNotEmpty()) lastBirdTime = now

        return when {
            dim && yellow > 0.02f -> LevelStatus.LEVEL_COMPLETED
            seenPigs && noPigFrames >= 3 -> LevelStatus.LEVEL_COMPLETED
            dim && state.pigs.isNotEmpty() -> LevelStatus.LEVEL_FAILED
            state.pigs.isNotEmpty() && now - lastBirdTime > 7000 -> LevelStatus.LEVEL_FAILED
            state.pigs.isNotEmpty() || state.birds.isNotEmpty() -> LevelStatus.LEVEL_RUNNING
            else -> LevelStatus.UNKNOWN
        }
    }
}
