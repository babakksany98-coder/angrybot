package com.example.angrybot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.angrybot.R
import com.example.angrybot.automation.BotAccessibilityService
import com.example.angrybot.bot.BotController
import com.example.angrybot.capture.ScreenCaptureService
import com.example.angrybot.model.BotUiState
import com.example.angrybot.utils.Logger
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var ivDebug: ImageView

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                ScreenCaptureService.start(this, r.resultCode, data)
            } else {
                Logger.log(Logger.Tag.SYSTEM, "Screen capture permission denied")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun openAccessibility() {
        Toast.makeText(this, "Enable 'Angry Bot Gesture Service'", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        ivDebug = findViewById(R.id.ivDebug)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        findViewById<Button>(R.id.btnCapture).setOnClickListener { requestCapture() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibility() }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            when {
                !ScreenCaptureService.running.value -> requestCapture()
                !BotAccessibilityService.connected.value && !BotController.settings.value.simulation -> openAccessibility()
                else -> {
                    BotController.start()
                    Toast.makeText(this, "Bot started. Switch to the game.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener { BotController.stop() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!ScreenCaptureService.running.value) requestCapture() else BotController.testOnce()
        }
        val swDebug = findViewById<SwitchCompat>(R.id.swDebug)
        val swSim = findViewById<SwitchCompat>(R.id.swSimulation)
        swDebug.isChecked = BotController.settings.value.debug
        swSim.isChecked = BotController.settings.value.simulation
        swDebug.setOnCheckedChangeListener { _, c -> BotController.settings.value = BotController.settings.value.copy(debug = c) }
        val swGemini = findViewById<SwitchCompat>(R.id.swGemini)
        swGemini.isChecked = BotController.settings.value.useGemini
        swGemini.setOnCheckedChangeListener { _, c -> BotController.settings.value = BotController.settings.value.copy(useGemini = c) }
        swSim.setOnCheckedChangeListener { _, c -> BotController.settings.value = BotController.settings.value.copy(simulation = c) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        BotController.ui, ScreenCaptureService.running, BotAccessibilityService.connected
                    ) { u, c, a -> Triple(u, c, a) }.collect { (u, c, a) -> render(u, c, a) }
                }
                launch {
                    Logger.lines.collect { tvLog.text = it.takeLast(120).joinToString("\n") }
                }
                launch {
                    BotController.debug.collect { snap -> snap?.image?.let { ivDebug.setImageBitmap(it) } }
                }
            }
        }
    }

    private fun render(u: BotUiState, capture: Boolean, access: Boolean) {
        tvStatus.text = buildString {
            appendLine("Bot: " + if (u.running) "RUNNING" else "stopped")
            appendLine("Screen Capture: " + if (capture) "active" else "OFF")
            appendLine("Accessibility: " + if (access) "connected" else "OFF")
            appendLine("Gemini: " + if (!BotController.gemini.available) "no API key" else if (BotController.settings.value.useGemini) "on (${BotController.gemini.model})" else "off")
            appendLine("Detection: ${u.detection}")
            appendLine("Current State: ${u.levelStatus}")
            appendLine("Current Target: ${u.target}")
            appendLine("Current Angle: %.1f deg   Power: %.0f%%".format(u.angle, u.power * 100))
            appendLine("Last Action: ${u.lastAction}")
            append("Confidence: %.0f%%".format(u.confidence * 100))
        }
    }
}
