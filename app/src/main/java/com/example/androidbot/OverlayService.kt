package com.example.androidbot

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
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayButton()
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
        params.x = 0
        params.y = 300

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        button.setOnTouchListener { view, event ->
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
        }

        windowManager.addView(button, params)
        overlayButton = button
    }

    private fun runDetectionTest() {
        val captureService = ScreenCaptureService.instance

        // لو الخدمة مش موجودة أصلاً أو موجودة بس مش جاهزة (توقفت الصلاحية)،
        // نفتح نافذة إعادة الطلب السريعة بدل ما نعطي خطأ بس
        if (captureService == null || !captureService.isReady()) {
            Toast.makeText(this, "الصلاحية متوقفة - جاري إعادة الطلب...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, RequestCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        Toast.makeText(this, "جاري الفحص...", Toast.LENGTH_SHORT).show()

        thread {
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
            Handler(Looper.getMainLooper()).post {
                if (screenBitmap == null) {
                    val errorMsg = captureService.lastError ?: "سبب غير معروف"
                    Toast.makeText(this, "فشل التقاط الشاشة: $errorMsg", Toast.LENGTH_LONG).show()
                    return@post
                }

                val result = ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.6)

                if (result.found) {
                    Toast.makeText(
                        this,
                        "لقى الزر! عند (${result.point?.x?.toInt()}, ${result.point?.y?.toInt()}) بثقة ${"%.2f".format(result.confidence)}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "ما لقاه (أعلى ثقة: ${"%.2f".format(result.confidence)})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
