package com.example.angrybot.ai

/**
 * Safety filter for any CLICK that Gemini proposes outside of normal shooting.
 * Only plain game-flow buttons (Next / Retry / Play ...) are allowed. Anything related to ads,
 * rewards, purchases or skipping/closing ads is always refused, whatever the model says.
 */
object NavGuard {
    private val allowed = Regex(
        "\\b(next|continue|retry|replay|restart|play|start|ok|okay|try again|next level|resume)\\b|ادامه|بعدی|دوباره|شروع|تایید|باشه",
        RegexOption.IGNORE_CASE
    )
    private val denied = Regex(
        "\\b(ads?|advert\\w*|watch|video|reward\\w*|free|buy|purchase|shop|store|subscribe|install|claim|coins?|gems?|offers?|bonus|close|skip|sponsor\\w*|heart|hearts|lives|refill)\\b|تبلیغ|ویدیو|ویدئو|رایگان|خرید|جایزه|فروشگاه|بستن|رد کردن|قلب",
        RegexOption.IGNORE_CASE
    )

    fun isAllowedClick(d: GeminiDecision): Boolean {
        if (d.action != AiAction.CLICK || !d.hasStart) return false
        if (d.state != ScreenState.LEVEL_COMPLETE && d.state != ScreenState.LEVEL_FAILED && d.state != ScreenState.MENU) return false
        val label = d.buttonLabel.trim()
        if (label.isEmpty()) return false
        if (denied.containsMatchIn(label) || denied.containsMatchIn(d.reason)) return false
        return allowed.containsMatchIn(label)
    }
}
