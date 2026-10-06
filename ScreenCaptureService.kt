package com.example.angrybot.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.angrybot.utils.Logger
import com.example.angrybot.utils.ScreenUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground service that owns the MediaProjection. The latest frame is kept as an
 * un-converted Image; conversion to Bitmap happens only on demand (cheap on weak phones).
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "capture"

        @Volatile
        var instance: ScreenCaptureService? = null
        val running = MutableStateFlow(false)

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val lock = Any()
    private var held: Image? = null

    @Volatile
    private var heldTime = 0L
    private var curW = 0
    private var curH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent?.getParcelableExtra(EXTRA_DATA)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Angry Bot")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (data == null) {
            Logger.log(Logger.Tag.CAPTURE, "Missing projection data; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Logger.log(Logger.Tag.CAPTURE, "Projection stopped by system")
                cleanup()
                running.value = false
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        thread = HandlerThread("capture").also { it.start() }
        handler = Handler(thread!!.looper)
        buildPipeline()
        instance = this
        running.value = true
        Logger.log(Logger.Tag.CAPTURE, "Screen capture started ${curW}x$curH")
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    held?.close()
                    held = img
                    heldTime = SystemClock.elapsedRealtime()
                }
            }
        }, handler)
        return r
    }

    private fun buildPipeline() {
        val s = ScreenUtil.real(this)
        curW = s.w
        curH = s.h
        val r = newReader(s.w, s.h)
        reader = r
        virtualDisplay = projection!!.createVirtualDisplay(
            "AngryBotCapture", s.w, s.h, s.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    /** Handles rotation: rebuilds the reader when the real screen size changed. */
    private fun ensureSize() {
        val s = ScreenUtil.real(this)
        if (s.w == curW && s.h == curH) return
        Logger.log(Logger.Tag.CAPTURE, "Screen size changed to ${s.w}x${s.h}; rebuilding reader")
        synchronized(lock) { held?.close(); held = null }
        val old = reader
        curW = s.w
        curH = s.h
        val r = newReader(s.w, s.h)
        reader = r
        virtualDisplay?.resize(s.w, s.h, s.dpi)
        virtualDisplay?.surface = r.surface
        old?.close()
    }

    /** Converts the most recent frame into a new Bitmap (caller may recycle it). */
    fun grab(): Bitmap? {
        synchronized(lock) {
            val img = held ?: return null
            return try {
                val plane = img.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * img.width
                val full = Bitmap.createBitmap(
                    img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                plane.buffer.rewind()
                full.copyPixelsFromBuffer(plane.buffer)
                if (rowPadding == 0) full
                else {
                    val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                    full.recycle()
                    cropped
                }
            } catch (e: Exception) {
                Logger.log(Logger.Tag.CAPTURE, "grab failed: ${e.message}")
                null
            }
        }
    }

    /** Waits for a frame newer than [sinceMs] (elapsedRealtime) or times out and returns the latest. */
    suspend fun awaitFrame(sinceMs: Long, timeoutMs: Long): Bitmap? {
        ensureSize()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (heldTime < sinceMs && SystemClock.elapsedRealtime() < deadline) delay(15)
        return grab()
    }

    private fun cleanup() {
        synchronized(lock) { held?.close(); held = null }
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        val p = projection
        projection = null
        p?.stop()
        thread?.quitSafely(); thread = null
        instance = null
    }

    override fun onDestroy() {
        cleanup()
        running.value = false
        Logger.log(Logger.Tag.CAPTURE, "Screen capture stopped")
        super.onDestroy()
    }
}
