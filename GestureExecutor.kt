package com.example.angrybot.automation

import com.example.angrybot.model.Vec2
import com.example.angrybot.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/** Serialises gestures (only one at a time) and performs them with human-like timing and motion. */
object GestureExecutor {
    private val mutex = Mutex()

    suspend fun drag(start: Vec2, end: Vec2, durationMs: Long, holdMs: Long): Boolean {
        val svc = BotAccessibilityService.instance
        if (svc == null) {
            Logger.log(Logger.Tag.EXECUTOR, "Accessibility service is not connected")
            return false
        }
        if (!mutex.tryLock()) {
            Logger.log(Logger.Tag.EXECUTOR, "Gesture already running; request skipped")
            return false
        }
        try {
            delay(HumanMotion.delayMs(180))   // short reaction pause before touching the screen
            Logger.log(
                Logger.Tag.EXECUTOR,
                "Drag (%.0f,%.0f) -> (%.0f,%.0f) ~%dms hold ~%dms (curved, ease-out)"
                    .format(start.x, start.y, end.x, end.y, durationMs, holdMs)
            )
            val ok = svc.dispatchHumanDrag(start, end, durationMs, holdMs)
            Logger.log(Logger.Tag.EXECUTOR, if (ok) "Gesture completed" else "Gesture cancelled")
            return ok
        } finally {
            mutex.unlock()
        }
    }

    suspend fun tap(point: Vec2): Boolean {
        val svc = BotAccessibilityService.instance
        if (svc == null) {
            Logger.log(Logger.Tag.EXECUTOR, "Accessibility service is not connected")
            return false
        }
        if (!mutex.tryLock()) return false
        try {
            delay(HumanMotion.delayMs(350))
            Logger.log(Logger.Tag.EXECUTOR, "Tap (%.0f,%.0f)".format(point.x, point.y))
            return svc.dispatchTap(point)
        } finally {
            mutex.unlock()
        }
    }
}
