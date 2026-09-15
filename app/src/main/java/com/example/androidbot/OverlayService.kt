package com.example.androidbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
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
                            runFullSequence()
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

    /**
     * تسلسل الهجوم الكامل - كل خطوة إلها اسم صورة قالب، ووصف للرسائل.
     * بين كل خطوة وثانية، منستنى شوي عشان الشاشة الجديدة تتحمل بالكامل.
     */
    data class SequenceStep(val templateFileName: String, val description: String, val delayAfterMs: Long)

    private val attackSequence = listOf(
        SequenceStep("template_attack_button.jpg", "زر الهجوم الرئيسي", 1500L),
        SequenceStep("template_search_button.jpg", "زر البحث عن مطابقة", 3000L),
        SequenceStep("template_battle_attack_button.jpg", "زر الهجوم بالمعركة", 1000L)
    )

    private fun runFullSequence() {
        val captureService = ScreenCaptureService.instance

        if (captureService == null || !captureService.isReady()) {
            Toast.makeText(this, "الصلاحية متوقفة - جاري إعادة الطلب...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, RequestCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        val accessibilityService = BotAccessibilityService.instance
        if (accessibilityService == null) {
            Toast.makeText(this, "خدمة الإتاحة مش مفعّلة (الزر ١)", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "بدء تسلسل الهجوم الكامل...", Toast.LENGTH_SHORT).show()

        thread {
            for ((index, step) in attackSequence.withIndex()) {
                val stepNumber = index + 1
                var found = false
                var attempts = 0

                // نحاول عدة مرات (مش مرة وحدة بس) لأنه الشاشة ممكن تكون لسا بتتحمل
                while (!found && attempts < 6) {
                    val templateBitmap = try {
                        assets.open(step.templateFileName).use { s -> BitmapFactory.decodeStream(s) }
                    } catch (e: Exception) {
                        null
                    }

                    if (templateBitmap == null) {
                        showToast("فشل تحميل قالب: ${step.templateFileName}")
                        return@thread
                    }

                    val screenBitmap = captureService.captureBitmapOnce()
                    if (screenBitmap == null) {
                        showToast("فشل التقاط الشاشة بالخطوة $stepNumber: ${captureService.lastError}")
                        return@thread
                    }

                    val result = ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.6)

                    if (result.found && result.point != null) {
                        accessibilityService.performTap(result.point.x, result.point.y)
                        showToast("خطوة $stepNumber (${step.description}): لقى وضغط ✅")
                        found = true
                        Thread.sleep(step.delayAfterMs)
                    } else {
                        attempts++
                        Thread.sleep(500)
                    }
                }

                if (!found) {
                    showToast("توقف عند خطوة $stepNumber (${step.description}) - ما لقاها بعد عدة محاولات")
                    return@thread
                }
            }

            showToast("تم التسلسل الكامل بنجاح! 🎉")
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
