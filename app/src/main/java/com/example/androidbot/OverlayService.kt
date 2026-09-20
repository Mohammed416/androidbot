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

        addDebugButton()
    }

    /**
     * زر تشخيص منفصل تمامًا عن منطق الهجوم - بيلتقط الشاشة، يشغّل كاشف
     * منطقة النشر بوضع Debug، ويحفظ صورة توضيحية بمجلد Pictures/Clash
     * عشان تقدر تفحصها. ما بيضغط ولا بيهاجم أي شي.
     */
    private fun addDebugButton() {
        val debugButton = Button(this).apply {
            text = "🔍"
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
        params.y = 550

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        debugButton.setOnTouchListener { view, event ->
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
                        runDeploymentDebugCapture()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(debugButton, params)
    }

    private fun runDeploymentDebugCapture() {
        val captureService = ScreenCaptureService.instance
        if (captureService == null || !captureService.isReady()) {
            Toast.makeText(this, "لازم تفعّل التقاط الشاشة أول", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "جاري تحليل منطقة النشر...", Toast.LENGTH_SHORT).show()

        thread {
            val screenBitmap = captureService.captureBitmapOnce()
            if (screenBitmap == null) {
                showToast("فشل التقاط الشاشة: ${captureService.lastError}")
                return@thread
            }

            val debugResult = DeploymentZoneDetector.findDeployPointsDebug(screenBitmap)
            val savedPath = captureService.saveBitmapToPicturesClash(debugResult.debugBitmap)
            screenBitmap.recycle()
            debugResult.debugBitmap.recycle()

            showToast(
                "${if (debugResult.success) "نجح ✅" else "فشل ❌"}: ${debugResult.reason} - " +
                        "محفوظة: $savedPath"
            )
        }
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

                showToast("لقى قرية مناسبة! جاري تنفيذ جيش الموارد_التنين الكهربائي...")
                Thread.sleep(1000)
                deployElectroDragonResourceArmy(captureService, accessibilityService)
                showToast("تم نشر الجيش بالكامل! 🎉")

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

    private fun findCardPosition(screenBitmap: Bitmap, templateFileName: String): android.graphics.PointF? {
        val templateBitmap = try {
            assets.open(templateFileName).use { s -> BitmapFactory.decodeStream(s) }
        } catch (e: Exception) {
            return null
        }
        val result = try {
            ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.55)
        } catch (e: Exception) {
            null
        }
        templateBitmap.recycle()
        return if (result?.found == true) result.point else null
    }

    /**
     * يقرأ عدد القطع المتبقية فوق بطاقة معيّنة - منطقة بحث أوسع هالمرة
     * (200×120 بدل 180×50) عشان نضمن نلقط الرقم حتى لو موقعه انزاح شوي
     * عن التخمين الأصلي.
     */
    private fun readCardQuantity(screenBitmap: Bitmap, cardCenter: android.graphics.PointF): Int {
        val badgeRegion = safeCrop(
            screenBitmap,
            (cardCenter.x - 100).toInt(),
            (cardCenter.y - 160).toInt(),
            200,
            120
        ) ?: return 1
        val qty = TextReader.readNumber(badgeRegion)
        badgeRegion.recycle()
        return if (qty != null && qty in 1..99) qty.toInt() else 1
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
     * جيش الموارد_التنين الكهربائي - الجيش كله (التنانين، المنطاد، الأبطال)
     * ينزل **بمكان واحد متقارب** (مش موزّع عالمحيط بالكامل)، عشان يضل
     * الجيش متجمّع ومنطقي حتى لو كاشف إطار القرية أخطأ ببعض النقاط.
     */
    private fun deployElectroDragonResourceArmy(
        captureService: ScreenCaptureService,
        accessibilityService: BotAccessibilityService
    ) {
        val screenForZone = captureService.captureBitmapOnce()
        if (screenForZone == null) {
            showToast("فشل التقاط الشاشة لتحليل منطقة النشر")
            return
        }
        val deployPoints = DeploymentZoneDetector.findDeployPoints(screenForZone, numPoints = 16, outwardOffset = 60.0)
        screenForZone.recycle()

        // نختار نقطة دخول واحدة بس (أقرب نقطة صالحة لأعلى منتصف الشاشة -
        // المنطقة يلي أثبتت نجاحها بالتجارب السابقة)
        val preferredEntry = 1170f to 140f
        val entryPoint = nearestPoint(preferredEntry, deployPoints)

        if (deployPoints.isEmpty()) {
            showToast("تحذير: ما قدر يحدد إطار القرية - رح يجرب نقطة افتراضية")
        } else {
            showToast("نقطة النشر: (${entryPoint.first.toInt()}, ${entryPoint.second.toInt()})")
        }

        // نقاط قريبة من بعض حوالين نقطة الدخول - عشان الجيش ينتشر بمساحة صغيرة مش كل القرية
        val clusterPoints = listOf(
            entryPoint,
            entryPoint.first - 80f to entryPoint.second + 20f,
            entryPoint.first + 80f to entryPoint.second + 20f,
            entryPoint.first - 40f to entryPoint.second - 30f,
            entryPoint.first + 40f to entryPoint.second - 30f
        )
        var clusterIndex = 0
        fun nextClusterPoint(): Pair<Float, Float> {
            val p = clusterPoints[clusterIndex % clusterPoints.size]
            clusterIndex++
            return p
        }

        // 1) كل التنانين المتاحة
        val screenForDragon = captureService.captureBitmapOnce()
        val dragonPos = screenForDragon?.let { findCardPosition(it, "template_card_dragon.jpg") }
        val dragonQty = if (dragonPos != null && screenForDragon != null) readCardQuantity(screenForDragon, dragonPos) else 1
        screenForDragon?.recycle()

        if (dragonPos != null) {
            showToast("التنين: لقاه، جاري نشر $dragonQty قطعة")
            Thread.sleep(1500)
            repeat(dragonQty) {
                accessibilityService.performTap(dragonPos.x, dragonPos.y)
                Thread.sleep(150)
                val p = nextClusterPoint()
                accessibilityService.performTap(p.first, p.second)
                Thread.sleep(150)
            }
        } else {
            showToast("ما لقى بطاقة التنين")
        }

        // 2) المنطاد الحجري
        deploySingleCard(captureService, accessibilityService, "template_card_siege.jpg", "المنطاد الحجري", nextClusterPoint())

        // 3) الملكة
        deploySingleCard(captureService, accessibilityService, "template_card_queen.jpg", "ملكة الرماة", nextClusterPoint())

        // 4) الملك
        deploySingleCard(captureService, accessibilityService, "template_card_king.jpg", "ملك البرابرة", nextClusterPoint())

        // 5) الآمر
        deploySingleCard(captureService, accessibilityService, "template_card_warden.jpg", "الآمر الكبير", nextClusterPoint())

        // 6) أمير المينيون
        deploySingleCard(captureService, accessibilityService, "template_card_pet.jpg", "أمير المينيون", nextClusterPoint())
    }

    private fun deploySingleCard(
        captureService: ScreenCaptureService,
        accessibilityService: BotAccessibilityService,
        templateFileName: String,
        description: String,
        point: Pair<Float, Float>
    ) {
        val screenBitmap = captureService.captureBitmapOnce()
        val cardPos = screenBitmap?.let { findCardPosition(it, templateFileName) }
        screenBitmap?.recycle()

        if (cardPos == null) {
            showToast("ما لقى بطاقة: $description")
            return
        }

        accessibilityService.performTap(cardPos.x, cardPos.y)
        Thread.sleep(400)
        accessibilityService.performTap(point.first, point.second)
        Thread.sleep(500)
        showToast("$description: تم النشر ✅")
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
