package com.example.angrybot.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

object FrameDiff {
    /** Fraction (0..1) of sampled pixels that changed noticeably between two frames. */
    fun ratio(a: Bitmap, b: Bitmap): Float {
        if (a.isRecycled || b.isRecycled) return 1f
        if (a.width != b.width || a.height != b.height) return 1f
        val gw = 64
        val gh = 36
        var diff = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = ((gx + 0.5f) * a.width / gw).toInt().coerceIn(0, a.width - 1)
            val y = ((gy + 0.5f) * a.height / gh).toInt().coerceIn(0, a.height - 1)
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) +
                    abs(Color.blue(p) - Color.blue(q))
            if (d > 60) diff++
        }
        return diff / (gw * gh).toFloat()
    }
}
