package com.example.angrybot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.example.angrybot.bot.BotController
import com.example.angrybot.model.BotUiState
import com.example.angrybot.model.DebugSnapshot

class DebugOverlayView(ctx: Context) : View(ctx) {
    var snapshot: DebugSnapshot? = null

    override fun onDraw(canvas: Canvas) {
        val s = snapshot ?: return
        if (s.state.width == 0 || s.state.height == 0) return
        DebugRenderer.draw(
            canvas, s.state, s.candidates, s.best,
            width / s.state.width.toFloat(), height / s.state.height.toFloat()
        )
    }
}

/**
 * Floating windows created through the accessibility service (TYPE_ACCESSIBILITY_OVERLAY, so no
 * extra permission): a full-screen non-touchable debug layer and a small control panel.
 */
class OverlayManager(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var debugView: DebugOverlayView? = null
    private var panel: LinearLayout? = null
    private var btnRun: Button? = null
    private var debugWanted = true
    private var captureHidden = false

    fun show() {
        if (debugView != null) return
        val dv = DebugOverlayView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(dv, lp)
        debugView = dv

        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(6, 6, 6, 6)
        }
        fun mkBtn(label: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }
        val run = mkBtn("Start") {
            if (BotController.ui.value.running) BotController.stop() else BotController.start()
        }
        val test = mkBtn("Test") { BotController.testOnce() }
        val dbg = mkBtn("Dbg") { BotController.settings.value = BotController.settings.value.let { it.copy(debug = !it.debug) } }
        p.addView(run)
        p.addView(test)
        p.addView(dbg)
        btnRun = run

        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        wm.addView(p, plp)
        panel = p
        // The panel is visible in screenshots; tell the detector to ignore its area.
        p.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            BotController.ignoreRects = listOf(Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))
        }
    }

    fun hide() {
        try { debugView?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        debugView = null
        panel = null
        btnRun = null
    }

    private fun applyVisibility() {
        debugView?.visibility = if (debugWanted && !captureHidden) View.VISIBLE else View.INVISIBLE
    }

    fun setCaptureHidden(h: Boolean) { captureHidden = h; applyVisibility() }
    fun setDebugWanted(d: Boolean) { debugWanted = d; applyVisibility() }
    fun onUiState(s: BotUiState) { btnRun?.text = if (s.running) "Stop" else "Start" }
    fun onSnapshot(s: DebugSnapshot?) { debugView?.snapshot = s; debugView?.invalidate() }
}
