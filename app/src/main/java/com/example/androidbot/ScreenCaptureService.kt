package com.example.androidbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
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

    var lastError: String? = null
        private set

    fun isReady(): Boolean = virtualDisplay != null && imageReader != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground(NOTIFICATION_ID, buildNotification())

        if (resultCode != Int.MIN_VALUE && resultData != null) {
            setupMediaProjection(resultCode, resultData)
        } else {
            lastError = "تشخيص: intent موجود=${intent != null}, resultCode=$resultCode, resultData موجود=${resultData != null}"
        }

        return START_NOT_STICKY
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
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            val rawWidth = metrics.widthPixels
            val rawHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            // نفرض دايمًا إن الأبعاد أفقية (العرض أكبر من الارتفاع) - نبدّل لو لازم
            if (rawWidth < rawHeight) {
                screenWidth = rawHeight
                screenHeight = rawWidth
            } else {
                screenWidth = rawWidth
                screenHeight = rawHeight
            }

            Log.i(TAG, "الأبعاد الخام: ${rawWidth}x${rawHeight} - المستخدمة: ${screenWidth}x${screenHeight}")

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
     * يلتقط إطار واحد من الشاشة ويرجعه كـ Bitmap مباشرة بالذاكرة (بدون حفظ ملف).
     * بيقص أي بكسلات فاضية زيادة (Padding) بتنتج عن طريقة تخزين الصورة الداخلية.
     */
    fun captureBitmapOnce(): Bitmap? {
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

        return try {
            val actualWidth = image.width
            val actualHeight = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * actualWidth

            val rawBitmap = Bitmap.createBitmap(
                actualWidth + rowPadding / pixelStride,
                actualHeight,
                Bitmap.Config.ARGB_8888
            )
            rawBitmap.copyPixelsFromBuffer(buffer)

            // نقص البكسلات الفاضية الزيادة يلي بتظهر كتشويش بحافة الصورة
            val cleanBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(rawBitmap, 0, 0, actualWidth, actualHeight)
            } else {
                rawBitmap
            }

            lastError = null
            cleanBitmap
        } catch (e: Exception) {
            lastError = "استثناء أثناء المعالجة: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "فشل التقاط الصورة", e)
            null
        } finally {
            image.close()
        }
    }

    /**
     * يلتقط إطار ويحفظه بمجلد Pictures/Clash العام.
     */
    fun captureOnce(): String? {
        val bitmap = captureBitmapOnce() ?: return null
        return saveBitmapToPicturesClash(bitmap)
    }

    fun saveBitmapToPicturesClash(bitmap: Bitmap): String? {
        val filename = "capture_${System.currentTimeMillis()}.png"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Clash")
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                if (uri == null) {
                    lastError = "فشل إنشاء الملف بالمعرض (MediaStore رجع null)"
                    return null
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                lastError = null
                "Pictures/Clash/$filename"
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val clashDir = File(picturesDir, "Clash")
                if (!clashDir.exists()) clashDir.mkdirs()

                val file = File(clashDir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                lastError = null
                file.absolutePath
            }
        } catch (e: Exception) {
            lastError = "فشل الحفظ بالمعرض: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "فشل الحفظ", e)
            null
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
