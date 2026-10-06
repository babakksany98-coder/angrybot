package com.example.angrybot.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Logger {
    enum class Tag { SYSTEM, CAPTURE, DETECTION, PHYSICS, PLANNER, EXECUTOR, RESULT }

    private const val MAX = 300
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(tag: Tag, msg: String) {
        Log.d("AngryBot", "[$tag] $msg")
        buffer.addLast("[$tag] $msg")
        while (buffer.size > MAX) buffer.removeFirst()
        _lines.value = buffer.toList()
    }
}
