package com.example.angrybot.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.angrybot.model.ObjectType
import com.example.angrybot.model.Vec2
import kotlin.math.roundToInt

/** Finds the flying (red) bird to the right of the launch point. Used for physics calibration. */
class BirdTracker {
    fun find(frame: Bitmap, originX: Float, ignore: List<Rect>): Vec2? {
        val aw = 240
        val ah = (frame.height * aw / frame.width.toFloat()).roundToInt().coerceAtLeast(1)
        val scale = frame.width / aw.toFloat()
        val small = Bitmap.createScaledBitmap(frame, aw, ah, true)
        val px = IntArray(aw * ah)
        small.getPixels(px, 0, aw, 0, 0, aw, ah)
        if (small !== frame) small.recycle()
        val minX = (originX / scale + 0.06f * aw).toInt()
        val hsv = FloatArray(3)
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until ah) for (x in minX until aw) {
            val fx = x * scale
            val fy = y * scale
            if (ignore.any { it.contains(fx.toInt(), fy.toInt()) }) continue
            val c = px[y * aw + x]
            Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
            if (ColorRules.classify(hsv[0], hsv[1], hsv[2]) == ObjectType.BIRD.ordinal) {
                sx += x; sy += y; n++
            }
        }
        if (n < 5) return null
        return Vec2((sx / n * scale).toFloat(), (sy / n * scale).toFloat())
    }
}
