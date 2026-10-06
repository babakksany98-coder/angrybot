package com.example.angrybot.vision

import com.example.angrybot.model.ObjectType

/**
 * Pluggable colour rules (H 0..360, S 0..1, V 0..1). Order matters: first match wins.
 * To add a new object type: add an enum entry in ObjectType and a Rule here.
 * Tuned for the classic Angry Birds palette; adjust for other games/skins.
 */
object ColorRules {
    class Rule(val type: ObjectType, val test: (Float, Float, Float) -> Boolean)

    val rules: MutableList<Rule> = mutableListOf(
        Rule(ObjectType.SLINGSHOT) { h, s, v -> h in 14f..38f && s > 0.35f && v in 0.12f..0.36f },
        Rule(ObjectType.PIG) { h, s, v -> h in 72f..112f && s > 0.40f && v > 0.45f },
        Rule(ObjectType.BIRD) { h, s, v -> (h <= 10f || h >= 348f) && s > 0.65f && v > 0.55f },
        Rule(ObjectType.WOOD) { h, s, v -> h in 18f..42f && s in 0.30f..0.85f && v in 0.36f..0.92f },
        Rule(ObjectType.GLASS) { h, s, v -> h in 175f..200f && s in 0.15f..0.45f && v > 0.80f },
        Rule(ObjectType.STONE) { h, s, v -> s < 0.12f && v in 0.40f..0.78f }
    )

    /** Returns ObjectType ordinal or -1 */
    fun classify(h: Float, s: Float, v: Float): Int {
        for (i in rules.indices) {
            val r = rules[i]
            if (r.test(h, s, v)) return r.type.ordinal
        }
        return -1
    }
}
