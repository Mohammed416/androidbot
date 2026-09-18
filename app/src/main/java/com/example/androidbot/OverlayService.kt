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
import kotlin.math.hypot

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

                showToast("لقى قرية مناسبة! جاري تحليل شريط الجيش والخريطة...")
                Thread.sleep(1000)
                executeEdragAttack(captureService, accessibilityService)
                showToast("تم تنفيذ الهجوم بالكامل! 🎉")

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

    data class CardLayout(
        val dragonX: Float,
        val balloonX: Float,
        val queenX: Float,
        val wardenX: Float,
        val kingX: Float,
        val petX: Float,
        val rageX: Float?,
        val freezeX: Float?
    )

    /**
     * يحسب مواقع البطاقات من نفس شريط المعركة الحالية (يتجاهل بطاقات x0 الفاضية)،
     * بافتراض إن ترتيب التدريب ثابت: تنانين → بالونات → أبطال → غضب → تجميد.
     */
    private fun computeCardLayout(quantityCards: List<TroopBarDetector.QuantityCard>): CardLayout? {
        val nonEmpty = quantityCards.filter { it.quantity > 0 }.sortedBy { it.centerX }
        if (nonEmpty.size < 2) return null

        val dragon = nonEmpty[0]
        val balloon = nonEmpty[1]
        val pitch = balloon.centerX - dragon.centerX
        if (pitch <= 10f) return null

        val queenX = balloon.centerX + pitch
        val wardenX = queenX + pitch
        val kingX = wardenX + pitch
        val petX = kingX + pitch
        val afterHeroesX = petX + pitch * 0.5f

        val spellCards = nonEmpty.filter { it.centerX > afterHeroesX }.sortedBy { it.centerX }

        return CardLayout(
            dragonX = dragon.centerX,
            balloonX = balloon.centerX,
            queenX = queenX,
            wardenX = wardenX,
            kingX = kingX,
            petX = petX,
            rageX = spellCards.getOrNull(0)?.centerX,
            freezeX = spellCards.getOrNull(1)?.centerX
        )
    }

    private fun nearestPoint(
        target: Pair<Float, Float>,
        candidates: List<DeploymentZoneDetector.DeployPoint>
    ): Pair<Float, Float> {
        if (candidates.isEmpty()) return target
        val nearest = candidates.minByOrNull {
            hypot((it.x - target.first).toDouble(), (it.y - target.second).toDouble())
        }
        return if (nearest != null) nearest.x to nearest.y else target
    }

    /**
     * استراتيجية هجوم Edrag - مواقع البطاقات محسوبة ديناميكيًا من شريط
     * المعركة الحالية، ونقطة دخول الجيش مصحّحة لأقرب نقطة نشر صحيحة فعليًا.
     */
    private fun executeEdragAttack(captureService: ScreenCaptureService, accessibilityService: BotAccessibilityService) {
        val fullScreen = captureService.captureBitmapOnce()
        if (fullScreen == null) {
            showToast("فشل التقاط الشاشة لتحليل المعركة")
            return
        }

        // تحليل شريط الجيش (نفس هاي المعركة بالضبط)
        val barBitmap = safeCrop(fullScreen, 300, 900, 2040, 180)
        val quantityCards = if (barBitmap != null) TroopBarDetector.detectQuantityCards(barBitmap) else emptyList()
        barBitmap?.recycle()

        val layout = computeCardLayout(quantityCards)
        if (layout == null) {
            showToast("ما قدر يحلل شريط الجيش - توقف الهجوم")
            fullScreen.recycle()
            return
        }

        showToast("لقى ${quantityCards.size} بطاقة بالشريط - جاري تحديد منطقة النشر...")

        // كاشف منطقة النشر الصحيحة حوالين القرية
        val validPoints = DeploymentZoneDetector.findDeployPoints(fullScreen, numPoints = 14, outwardOffset = 60.0)
        fullScreen.recycle()

        // نقاط الخريطة الأصلية كما حددها المستخدم
        val intendedPoint1 = 1025f to 232f
        val point2 = 828f to 328f
        val point3 = 1093f to 328f
        val point4 = 888f to 452f
        val point5 = 1230f to 433f
        val point6 = 988f to 570f
        val point7 = 1121f to 529f

        // نصحح نقطة الدخول لأقرب نقطة نشر صالحة فعليًا (مش مكان جوا سور مباشرة)
        val entryPoint = nearestPoint(intendedPoint1, validPoints)
        if (validPoints.isNotEmpty()) {
            showToast("نقطة الدخول المصححة: (${entryPoint.first.toInt()}, ${entryPoint.second.toInt()})")
        } else {
            showToast("ما لقى نقاط نشر - رح يجرب النقطة الأصلية")
        }

        val dragonCard = layout.dragonX to 990f
        val balloonCard = layout.balloonX to 990f
        val queenCard = layout.queenX to 990f
        val wardenCard = layout.wardenX to 990f
        val kingCard = layout.kingX to 990f
        val petCard = layout.petX to 990f
        val rageCard = (layout.rageX ?: 2212f) to 990f
        val freezeCard = (layout.freezeX ?: 1957f) to 990f

        // نشر الجيش كامل دفعة واحدة عند نقطة الدخول المصححة
        repeat(10) {
            accessibilityService.performTap(dragonCard.first, dragonCard.second)
            Thread.sleep(150)
            accessibilityService.performTap(entryPoint.first, entryPoint.second)
            Thread.sleep(150)
        }
        repeat(2) {
            accessibilityService.performTap(balloonCard.first, balloonCard.second)
            Thread.sleep(150)
            accessibilityService.performTap(entryPoint.first, entryPoint.second)
            Thread.sleep(150)
        }
        for (hero in listOf(queenCard, wardenCard, kingCard, petCard)) {
            accessibilityService.performTap(hero.first, hero.second)
            Thread.sleep(180)
            accessibilityService.performTap(entryPoint.first, entryPoint.second)
            Thread.sleep(180)
        }

        // تعويذتين غضب عند 2+3
        Thread.sleep(4000)
        accessibilityService.performTap(rageCard.first, rageCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point2.first, point2.second)
        Thread.sleep(300)

        accessibilityService.performTap(rageCard.first, rageCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point3.first, point3.second)
        Thread.sleep(300)

        // تعويذتين غضب عند 3+4
        Thread.sleep(4000)
        accessibilityService.performTap(rageCard.first, rageCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point3.first, point3.second)
        Thread.sleep(300)

        accessibilityService.performTap(rageCard.first, rageCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point4.first, point4.second)
        Thread.sleep(300)

        // تجميد عند 5 و6
        Thread.sleep(4000)
        accessibilityService.performTap(freezeCard.first, freezeCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point5.first, point5.second)
        Thread.sleep(300)

        accessibilityService.performTap(freezeCard.first, freezeCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point6.first, point6.second)
        Thread.sleep(300)

        // تجميد عند 7 بتأخير 5 ثواني إضافية
        Thread.sleep(5000)
        accessibilityService.performTap(freezeCard.first, freezeCard.second)
        Thread.sleep(200)
        accessibilityService.performTap(point7.first, point7.second)
        Thread.sleep(300)
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
