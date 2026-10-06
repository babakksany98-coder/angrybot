package com.example.angrybot.vision

import com.example.angrybot.model.ObjectType
import kotlin.math.atan2

class Blob(
    val type: ObjectType,
    val area: Int,
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val cx: Float, val cy: Float,
    val orientationDeg: Float
) {
    val w: Int get() = maxX - minX + 1
    val h: Int get() = maxY - minY + 1
    val fill: Float get() = area.toFloat() / (w * h)
}

/** Connected-component labelling (4-neighbourhood) on a per-pixel class map. */
object Segmenter {
    fun label(cls: ByteArray, w: Int, h: Int, minArea: Int): List<Blob> {
        val total = w * h
        val visited = BooleanArray(total)
        val stack = IntArray(total)
        val out = ArrayList<Blob>()
        val types = ObjectType.values()
        for (start in 0 until total) {
            val c = cls[start]
            if (c < 0 || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var n = 0
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                n++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sx += x; sy += y
                sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
                if (x > 0) { val q = p - 1; if (!visited[q] && cls[q] == c) { visited[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (!visited[q] && cls[q] == c) { visited[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (!visited[q] && cls[q] == c) { visited[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (!visited[q] && cls[q] == c) { visited[q] = true; stack[sp++] = q } }
            }
            if (n < minArea) continue
            val mx = sx / n
            val my = sy / n
            val mu20 = sxx / n - mx * mx
            val mu02 = syy / n - my * my
            val mu11 = sxy / n - mx * my
            val theta = Math.toDegrees(0.5 * atan2(2 * mu11, mu20 - mu02)).toFloat()
            out.add(Blob(types[c.toInt()], n, minX, minY, maxX, maxY, mx.toFloat(), my.toFloat(), theta))
        }
        return out
    }
}
