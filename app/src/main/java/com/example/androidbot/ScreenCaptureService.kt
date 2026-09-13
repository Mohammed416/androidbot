package com.example.androidbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // رسالة آخر خطأ صار - عشان نقدر نعرضها بالواجهة لأنه ما في وصول لـ Logcat بدون كمبيوتر
    var lastError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground(NOTIFICATION_ID, buildNotification())

        if (resultCode != -1 && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        } else {
            lastError = "لم يتم استلام صلاحية صحيحة من النظام"
        }

        return START_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            if (mediaProjection == null) {
                lastError = "فشل الحصول على MediaProjection - الصلاحية غير صالحة"
                return
            }

            // مطلوب من أندرويد 14 فما فوق: لازم نسجل مستمع قبل إنشاء الشاشة الافتراضية
            // وإلا العملية بتفشل بصمت بدون ما تعطي أي صورة
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection توقفت من النظام")
                    lastError = "توقفت صلاحية التقاط الشاشة - لازم تفعّلها من جديد (الزر ٣)"
                    virtualDisplay?.release()
                    imageReader?.close()
                    virtualDisplay = null
                    imageReader = null
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)

            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AndroidBotCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            if (virtualDisplay == null) {
                lastError = "فشل إنشاء الشاشة الافتراضية (createVirtualDisplay رجعت null)"
                return
            }

            lastError = null
            Log.i(TAG, "تم تجهيز التقاط الشاشة: ${screenWidth}x${screenHeight}")

        } catch (e: Exception) {
            lastError = "استثناء أثناء التجهيز: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "فشل إعداد MediaProjection", e)
        }
    }

    /**
     * يلتقط إطار واحد حاليًا من الشاشة ويحفظه كملف PNG.
     * فيها إعادة محاولة لأن أول إطار ممكن ياخد وقت بسيط لحد ما يجهز.
     * يرجع مسار الملف لو نجح، أو null لو فشل (تحقق من lastError لمعرفة السبب).
     */
    fun captureOnce(): String? {
        val reader = imageReader
        if (reader == null) {
            lastError = lastError ?: "الخدمة لسا ما جهزت (imageReader غير موجود) - تأكد إنك ضغطت الزر ٣ ووافقت على مشاركة الشاشة"
            return null
        }

        var image: Image? = null
        var attempts = 0
        while (image == null && attempts < 20) {
            image = reader.acquireLatestImage()
            if (image == null) {
                Thread.sleep(200)
                attempts++
            }
        }

        if (image == null) {
            lastError = "ما وصلت أي صورة من الشاشة بعد ${20 * 200}ms - جرب تتأكد إنك اخترت \"مشاركة الشاشة بأكملها\" مش تطبيق واحد"
            return null
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val dir = getExternalFilesDir("captures")
            if (dir != null && !dir.exists()) dir.mkdirs()

            val file = File(dir, "capture_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            lastError = null
            Log.i(TAG, "تم حفظ الصورة: ${file.absolutePath}")
            return file.absolutePath

        } catch (e: Exception) {
            lastError = "استثناء أثناء الحفظ: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "فشل التقاط الصورة", e)
            return null
        } finally {
            image.close()
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroidBot")
            .setContentText("خدمة التقاط الشاشة شغالة")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "التقاط الشاشة",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
