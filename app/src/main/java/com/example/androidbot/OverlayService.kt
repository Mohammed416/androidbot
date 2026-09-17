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
        private const val MIN_RESOURCE_THRESHOLD = 1_000_000L
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
            try {
                if (!tapWhenFound(captureService, accessibilityService, "template_attack_button.jpg", "زر الهجوم الرئيسي", 6)) return@thread
                Thread.sleep(1500)

                if (!tapWhenFound(captureService, accessibilityService, "template_search_button.jpg", "زر البحث عن مطابقة", 6)) return@thread
                Thread.sleep(2000)

                if (!tapWhenFound(captureService, accessibilityService, "template_battle_attack_button.jpg", "زر تأكيد الهجوم", 6)) return@thread
                Thread.sleep(2000)

                var villageAccepted = false
                var skipAttempts = 0

                while (!villageAccepted && skipAttempts < 30) {
                    val (goldAmount, elixirAmount) = readStableResources(captureService)

                    if (goldAmount == null || elixirAmount == null) {
                        showToast("فشل قراءة الموارد: ${captureService.lastError}")
                        return@thread
                    }

                    showToast("قرية: ذهب $goldAmount - إكسير $elixirAmount")

                    if (goldAmount >= MIN_RESOURCE_THRESHOLD || elixirAmount >= MIN_RESOURCE_THRESHOLD) {
                        villageAccepted = true
                    } else {
                        skipAttempts++
                        if (!tapWhenFound(captureService, accessibilityService, "template_skip_button.jpg", "زر التخطي", 4)) {
                            showToast("ما قدر يلاقي زر التخطي")
                            return@thread
                        }
                        Thread.sleep(3500)
                    }
                }

                if (!villageAccepted) {
                    showToast("توقف - ما لقى قرية مناسبة بعد $skipAttempts محاولة تخطي")
                    return@thread
                }

                showToast("لقى قرية مناسبة! جاري نشر الجيش...")
                Thread.sleep(1000)
                deployArmy(accessibilityService)
                showToast("تم نشر الجيش والتعاويذ! 🎉")

            } catch (e: Throwable) {
                showToast("خطأ عام أوقف التسلسل: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    private fun tapWhenFound(
        captureService: ScreenCaptureService,
        accessibilityService: BotAccessibilityService,
        templateFileName: String,
        description: String,
        maxAttempts: Int
    ): Boolean {
        var attempts = 0
        while (attempts < maxAttempts) {
            val templateBitmap = try {
                assets.open(templateFileName).use { s -> BitmapFactory.decodeStream(s) }
            } catch (e: Exception) {
                null
            }

            if (templateBitmap == null) {
                showToast("فشل تحميل قالب: $templateFileName")
                return false
            }

            val screenBitmap = captureService.captureBitmapOnce()
            if (screenBitmap == null) {
                templateBitmap.recycle()
                showToast("فشل التقاط الشاشة ($description): ${captureService.lastError}")
                return false
            }

            val result = try {
                ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.6)
            } catch (e: Exception) {
                showToast("خطأ بالمطابقة ($description): ${e.message}")
                ImageMatcher.MatchResult(found = false)
            }

            templateBitmap.recycle()
            screenBitmap.recycle()

            if (result.found && result.point != null) {
                accessibilityService.performTap(result.point.x, result.point.y)
                showToast("$description: لقى وضغط ✅")
                return true
            }
            attempts++
            Thread.sleep(500)
        }
        showToast("ما لقى: $description بعد $maxAttempts محاولة")
        return false
    }

    private fun readStableResources(captureService: ScreenCaptureService): Pair<Long?, Long?> {
        var lastGold: Long? = null
        var lastElixir: Long? = null

        repeat(6) { attempt ->
            Thread.sleep(if (attempt == 0) 1000 else 600)

            val screenBitmap = captureService.captureBitmapOnce() ?: return Pair(null, null)
            val goldRegion = safeCrop(screenBitmap, 130, 130, 220, 60)
            val elixirRegion = safeCrop(screenBitmap, 130, 185, 220, 60)

            val gold = goldRegion?.let { TextReader.readNumber(it) } ?: 0L
            val elixir = elixirRegion?.let { TextReader.readNumber(it) } ?: 0L

            goldRegion?.recycle()
            elixirRegion?.recycle()
            screenBitmap.recycle()

            if (attempt > 0 && gold == lastGold && elixir == lastElixir && gold > 0) {
                return Pair(gold, elixir)
            }

            lastGold = gold
            lastElixir = elixir
        }

        return Pair(lastGold, lastElixir)
    }

    /**
     * نشر مركّز بأعلى الشاشة بس - المنطقة المثبتة عمليًا إنها مفتوحة وقابلة
     * للنشر (بعكس الجوانب والأسفل يلي فشلت بالتجربة الفعلية).
     * فيها تأخير أطول بين كل ضغطتين (500ms) لضمان إن كل لمسة توصل وتسجل صح.
     */
    private fun deployArmy(accessibilityService: BotAccessibilityService) {
        val dragonCard = 312f to 990f
        val balloonCard = 526f to 990f
        val babyDragonCard = 740f to 990f
        val queenCard = 1168f to 990f
        val wardenCard = 1382f to 990f
        val kingCard = 1596f to 990f
        val freezeSpellCard = 2024f to 990f
        val rageSpellCard = 2238f to 990f

        // كل نقاط النشر بأعلى الشاشة بس - منطقة مثبتة إنها مفتوحة فعليًا
        val topPoints = listOf(300f, 600f, 900f, 1200f, 1500f, 1800f, 2100f).map { it to 140f }
        val airTroopCards = listOf(dragonCard, balloonCard, babyDragonCard)

        for ((index, point) in topPoints.withIndex()) {
            val card = airTroopCards[index % airTroopCards.size]
            accessibilityService.performTap(card.first, card.second)
            Thread.sleep(500)
            accessibilityService.performTap(point.first, point.second)
            Thread.sleep(500)
        }

        // الأبطال - بنفس منطقة أعلى الشاشة يلي أثبتت نجاحها
        val heroCards = listOf(queenCard, wardenCard, kingCard)
        val heroDropPoints = listOf(500f to 140f, 1170f to 140f, 1850f to 140f)
        for ((hero, point) in heroCards.zip(heroDropPoints)) {
            accessibilityService.performTap(hero.first, hero.second)
            Thread.sleep(500)
            accessibilityService.performTap(point.first, point.second)
            Thread.sleep(500)
        }

        // ننتظر عشان الجيش يتحرك وينزل جوا القرية
        Thread.sleep(6000)

        // التعاويذ - بمنتصف الشاشة تقريبًا (فوق مكان تجمع الجيش، مش خارج الخريطة)
        accessibilityService.performTap(freezeSpellCard.first, freezeSpellCard.second)
        Thread.sleep(500)
        accessibilityService.performTap(1170f, 400f)
        Thread.sleep(500)

        accessibilityService.performTap(rageSpellCard.first, rageSpellCard.second)
        Thread.sleep(500)
        accessibilityService.performTap(1170f, 400f)
        Thread.sleep(500)
    }

    private fun safeCrop(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        return try {
            val safeX = x.coerceIn(0, bitmap.width - 1)
            val safeY = y.coerceIn(0, bitmap.height - 1)
            val safeW = w.coerceAtMost(bitmap.width - safeX)
            val safeH = h.coerceAtMost(bitmap.height - safeY)
            Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
        } catch (e: Exception) {
            null
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
