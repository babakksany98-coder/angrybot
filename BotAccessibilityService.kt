package com.example.angrybot.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.example.angrybot.bot.BotController
import com.example.angrybot.model.Vec2
import com.example.angrybot.ui.OverlayManager
import com.example.angrybot.utils.Logger
import com.example.angrybot.utils.ScreenUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BotAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BotAccessibilityService? = null
        val connected = MutableStateFlow(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: OverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.value = true
        Logger.log(Logger.Tag.SYSTEM, "Accessibility service connected")
        val ov = OverlayManager(this)
        overlay = ov
        ov.show()
        BotController.overlayVisibility = { visible -> mainHandler.post { ov.setCaptureHidden(!visible) } }
        scope.launch { BotController.ui.collect { ov.onUiState(it) } }
        scope.launch { BotController.debug.collect { ov.onSnapshot(it) } }
        scope.launch { BotController.settings.collect { ov.setDebugWanted(it.debug) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        connected.value = false
        BotController.overlayVisibility = null
        BotController.ignoreRects = emptyList()
        overlay?.hide()
        overlay = null
        scope.cancel()
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    /**
     * Human-like pull: the finger stays down across a chain of short strokes (continueStroke),
     * each with its own duration, so the pull starts fast and slows down while aiming.
     * Then it holds still and releases. The end point is exactly the requested one.
     */
    suspend fun dispatchHumanDrag(start: Vec2, end: Vec2, durationMs: Long, holdMs: Long): Boolean {
        val s = ScreenUtil.real(this)
        val a = HumanMotion.clampPoint(start, s.w.toFloat(), s.h.toFloat())
        val b = HumanMotion.clampPoint(end, s.w.toFloat(), s.h.toFloat())
        val plan = HumanMotion.planDrag(a, b, durationMs.coerceAtLeast(120L), holdMs)

        var prev: StrokeDescription? = null
        for ((i, seg) in plan.segments.withIndex()) {
            val path = Path().apply {
                moveTo(seg.from.x, seg.from.y)
                lineTo(seg.to.x, seg.to.y)
            }
            val willContinue = !(i == plan.segments.lastIndex && plan.holdMs <= 0L)
            val stroke = prev?.continueStroke(path, 0, seg.durationMs, willContinue)
                ?: StrokeDescription(path, 0, seg.durationMs, willContinue)
            if (!dispatch(GestureDescription.Builder().addStroke(stroke).build())) return false
            prev = stroke
        }
        if (plan.holdMs > 0L) {
            val holdPath = Path().apply { moveTo(b.x, b.y) }
            val hold = prev!!.continueStroke(holdPath, 0, plan.holdMs, false)
            return dispatch(GestureDescription.Builder().addStroke(hold).build())
        }
        return true
    }

    /** A single short tap (used only for plain game-flow buttons such as Next / Retry). */
    suspend fun dispatchTap(point: Vec2): Boolean {
        val s = ScreenUtil.real(this)
        val p = HumanMotion.clampPoint(HumanMotion.tapPoint(point), s.w.toFloat(), s.h.toFloat())
        val path = Path().apply { moveTo(p.x, p.y) }
        val stroke = StrokeDescription(path, 0, HumanMotion.tapDurationMs(), false)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }
}
