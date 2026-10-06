package com.example.angrybot.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

object ScreenUtil {
    data class Size(val w: Int, val h: Int, val dpi: Int)

    @Suppress("DEPRECATION")
    fun real(ctx: Context): Size {
        val dm = DisplayMetrics()
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return Size(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }
}
