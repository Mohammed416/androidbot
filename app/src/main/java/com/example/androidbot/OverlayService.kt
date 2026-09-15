package com.example.androidbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private lateinit var windowManager: WindowManager
    private var overlayButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidBot")
            .setContentText("الزر العائم شغال")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "الزر العائم",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun addOverlayButton() {
        val button = Button(this).apply {
            text = "بوت"
            alpha = 0.85f
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 400

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        button.setOnTouchListener { view, event ->
            try {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 25 || abs(dy) > 25) isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            runDetectionTest()
                        }
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Toast.makeText(this, "خطأ بالسحب: ${e.message}", Toast.LENGTH_SHORT).show()
                true
            }
        }

        windowManager.addView(button, params)
        overlayButton = button
    }

    private fun runDetectionTest() {
        try {
            Toast.makeText(this, "تم الضغط - جاري التحقق...", Toast.LENGTH_SHORT).show()

            val captureService = ScreenCaptureService.instance

            if (captureService == null || !captureService.isReady()) {
                Toast.makeText(this, "الصلاحية متوقفة - جاري إعادة الطلب...", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, RequestCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            }

            thread {
                try {
                    val templateBitmap = try {
                        assets.open("template_attack_button.jpg").use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    if (templateBitmap == null) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(this, "فشل تحميل صورة القالب", Toast.LENGTH_LONG).show()
                        }
                        return@thread
                    }

                    val screenBitmap = captureService.captureBitmapOnce()
                    if (screenBitmap == null) {
                        Handler(Looper.getMainLooper()).post {
                            val errorMsg = captureService.lastError ?: "سبب غير معروف"
                            Toast.makeText(this, "فشل التقاط الشاشة: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                        return@thread
                    }

                    // نحفظ نسخة من نفس الصورة يلي استخدمها البوت بالفحص، عشان نقدر نشوفها بالمعرض
                    val savedPath = captureService.saveBitmapToPicturesClash(screenBitmap)

                    val result = ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.6)

                    Handler(Looper.getMainLooper()).post {
                        if (result.found) {
                            val point = result.point
                            if (point != null) {
                                val accessibilityService = BotAccessibilityService.instance
                                if (accessibilityService != null) {
                                    accessibilityService.performTap(point.x, point.y)
                                    Toast.makeText(
                                        this,
                                        "لقى الزر وضغطه! عند (${point.x.toInt()}, ${point.y.toInt()}) بثقة ${"%.2f".format(result.confidence)}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this,
                                        "لقى الزر بس ما قدر يضغطه - خدمة الإتاحة مش مفعّلة (الزر ١)",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            Toast.makeText(
                                this,
                                "ما لقاه (أعلى ثقة: ${"%.2f".format(result.confidence)}) - محفوظة بالمعرض: $savedPath",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "خطأ داخلي: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ بالضغطة نفسها: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // العرض ممكن يكون انشال أصلاً
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
