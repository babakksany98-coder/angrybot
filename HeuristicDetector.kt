package com.example.angrybot.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.angrybot.model.DetectedObject
import com.example.angrybot.model.GameState
import com.example.angrybot.model.ObjectType
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resolution independent detector: downscale -> HSV classification -> connected components
 * -> geometric filtering. All coordinates are scaled back to the real frame size.
 */
class HeuristicDetector : ObjectDetector {

    override fun detect(frame: Bitmap, config: DetectionConfig): GameState {
        val fw = frame.width
        val fh = frame.height
        val aw = min(config.analysisWidth, fw)
        val ah = max(1, (fh * aw / fw.toFloat()).roundToInt())
        val scale = fw / aw.toFloat()

        val small = Bitmap.createScaledBitmap(frame, aw, ah, true)
        val px = IntArray(aw * ah)
        small.getPixels(px, 0, aw, 0, 0, aw, ah)
        if (small !== frame) small.recycle()

        val cls = ByteArray(aw * ah)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
            cls[i] = ColorRules.classify(hsv[0], hsv[1], hsv[2]).toByte()
        }
        for (r in config.ignoreRects) {
            val x0 = (r.left / scale).toInt().coerceIn(0, aw)
            val x1 = ceil(r.right / scale).toInt().coerceIn(0, aw)
            val y0 = (r.top / scale).toInt().coerceIn(0, ah)
            val y1 = ceil(r.bottom / scale).toInt().coerceIn(0, ah)
            for (y in y0 until y1) for (x in x0 until x1) cls[y * aw + x] = -1
        }

        val minArea = max(10, (aw * ah * 0.00018f).toInt())
        val frameArea = aw * ah
        val blobs = Segmenter.label(cls, aw, ah, minArea).filter { b ->
            // drop sky / ground / huge background regions
            !(b.area > frameArea * 0.12f || b.w > aw * 0.6f)
        }
        val leftLimit = aw * 0.40f

        val objs = ArrayList<DetectedObject>()

        fun toObj(b: Blob, type: ObjectType, conf: Float, feats: Map<String, Float> = emptyMap()) =
            DetectedObject(
                type = type,
                centerX = (b.minX + b.maxX + 1) / 2f * scale,
                centerY = (b.minY + b.maxY + 1) / 2f * scale,
                width = b.w * scale,
                height = b.h * scale,
                confidence = conf.coerceIn(0f, 1f),
                features = feats + mapOf("area" to b.area * scale * scale, "fill" to b.fill),
                rotation = b.orientationDeg
            )

        // Slingshot
        val sling = blobs.filter {
            it.type == ObjectType.SLINGSHOT && it.cx < leftLimit && it.h >= it.w * 1.2f
        }.maxByOrNull { it.area }
        if (sling != null) objs.add(toObj(sling, ObjectType.SLINGSHOT, 0.6f + 0.4f * sling.fill))

        // Birds (only the left part of the screen; the one nearest the sling top is active)
        val birdBlobs = blobs.filter {
            it.type == ObjectType.BIRD && it.cx < leftLimit && it.w.toFloat() / it.h in 0.5f..2f
        }
        val active = if (sling != null)
            birdBlobs.minByOrNull { hypot(it.cx - sling.cx, it.cy - sling.minY.toFloat()) }
        else birdBlobs.minByOrNull { it.cy }
        for (b in birdBlobs) {
            objs.add(toObj(b, ObjectType.BIRD, b.fill / 0.78f, mapOf("active" to if (b === active) 1f else 0f)))
        }

        // Pigs
        for (b in blobs) {
            if (b.type != ObjectType.PIG) continue
            val ar = b.w.toFloat() / b.h
            if (ar !in 0.5f..2f || b.fill < 0.5f) continue
            val conf = (0.45f + 0.55f * b.fill).coerceIn(0f, 1f)
            objs.add(toObj(b, ObjectType.PIG, conf))
        }

        // Structures
        for (b in blobs) {
            if (b.type != ObjectType.WOOD && b.type != ObjectType.STONE && b.type != ObjectType.GLASS) continue
            if (b.area < minArea * 2) continue
            val shapeBonus = if (b.w.toFloat() / b.h in 0.15f..6f) 0.08f else 0f
            objs.add(toObj(b, b.type, (0.35f + 0.57f * b.fill + shapeBonus).coerceIn(0f, 1f)))
        }

        val groundY = if (sling != null) (sling.maxY + 1) * scale
        else (objs.maxOfOrNull { it.bottom } ?: fh * 0.9f).coerceIn(fh * 0.6f, fh * 0.97f)
        objs.add(
            DetectedObject(
                ObjectType.GROUND, fw / 2f, groundY + (fh - groundY) / 2f,
                fw.toFloat(), fh - groundY, 0.5f
            )
        )
        return GameState(fw, fh, objs, groundY)
    }
}
