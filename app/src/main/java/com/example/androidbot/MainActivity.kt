package com.example.androidbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "تم تفعيل التقاط الشاشة", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "تم رفض صلاحية التقاط الشاشة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // طلب صلاحية الإشعارات فعليًا - إلزامي من أندرويد 13 فما فوق
        // وإلا الخدمات الأمامية (Foreground Services) ما بتضل شغالة صحيح
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        val openCvLoaded = OpenCVLoader.initDebug()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val statusText = TextView(this).apply {
            text = if (openCvLoaded) {
                "الحالة: OpenCV تحمّلت بنجاح ✅"
            } else {
                "الحالة: فشل تحميل OpenCV ❌"
            }
            textSize = 16f
        }

        val enableButton = Button(this).apply {
            text = "١. فعّل خدمة الإتاحة (مرة واحدة)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val testTapButton = Button(this).apply {
            text = "٢. اختبار: أرسل ضغطة تجريبية"
            setOnClickListener {
                val service = BotAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this@MainActivity, "الخدمة مش مفعّلة - فعّلها أول من الإعدادات", Toast.LENGTH_LONG).show()
                } else {
                    service.performTap(500f, 800f)
                    Toast.makeText(this@MainActivity, "تم إرسال ضغطة تجريبية عند (500, 800)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val requestCaptureButton = Button(this).apply {
            text = "٣. فعّل صلاحية التقاط الشاشة"
            setOnClickListener {
                val projectionManager =
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        val captureNowButton = Button(this).apply {
            text = "٤. التقط صورة الشاشة الآن"
            setOnClickListener {
                val service = ScreenCaptureService.instance
                if (service == null) {
                    Toast.makeText(this@MainActivity, "لازم تفعّل صلاحية التقاط الشاشة أول (الزر ٣)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "جاري الالتقاط...", Toast.LENGTH_SHORT).show()
                    thread {
                        val path = service.captureOnce()
                        Handler(Looper.getMainLooper()).post {
                            if (path != null) {
                                Toast.makeText(this@MainActivity, "تم الحفظ: $path", Toast.LENGTH_LONG).show()
                            } else {
                                val errorMsg = service.lastError ?: "سبب غير معروف"
                                Toast.makeText(this@MainActivity, "فشل: $errorMsg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        val testOpenCvButton = Button(this).apply {
            text = "٥. اختبار: هل OpenCV بيقدر يلاقي صورة داخل صورة؟"
            setOnClickListener {
                val bigBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val bigCanvas = Canvas(bigBitmap)
                bigCanvas.drawColor(Color.WHITE)
                val bigPaint = android.graphics.Paint()
                bigPaint.color = Color.RED
                bigCanvas.drawRect(250f, 150f, 300f, 200f, bigPaint)
                bigPaint.color = Color.BLUE
                bigCanvas.drawRect(275f, 150f, 300f, 200f, bigPaint)

                val templateBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
                val templateCanvas = Canvas(templateBitmap)
                val templatePaint = android.graphics.Paint()
                templatePaint.color = Color.RED
                templateCanvas.drawRect(0f, 0f, 50f, 50f, templatePaint)
                templatePaint.color = Color.BLUE
                templateCanvas.drawRect(25f, 0f, 50f, 50f, templatePaint)

                val matchResult = ImageMatcher.findTemplate(bigBitmap, templateBitmap, minConfidence = 0.7)

                if (matchResult.found) {
                    Toast.makeText(
                        this@MainActivity,
                        "نجح! لقى المربع عند (${matchResult.point?.x}, ${matchResult.point?.y}) بثقة ${matchResult.confidence}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "فشل - ما لقى المطابقة (ثقة: ${matchResult.confidence})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val testRealTemplateButton = Button(this).apply {
            text = "٦. اختبار: قارن زر الهجوم الحقيقي مع الشاشة الحالية"
            setOnClickListener {
                val service = ScreenCaptureService.instance
                if (service == null) {
                    Toast.makeText(this@MainActivity, "لازم تفعّل صلاحية التقاط الشاشة أول (الزر ٣)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                Toast.makeText(this@MainActivity, "جاري المقارنة...", Toast.LENGTH_SHORT).show()

                thread {
                    val templateBitmap = try {
                        assets.open("template_attack_button.jpg").use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    Handler(Looper.getMainLooper()).post {
                        if (templateBitmap == null) {
                            Toast.makeText(
                                this@MainActivity,
                                "فشل تحميل صورة القالب - تأكد إنها موجودة بمسار app/src/main/assets/template_attack_button.jpg",
                                Toast.LENGTH_LONG
                            ).show()
                            return@post
                        }

                        thread {
                            val screenBitmap = service.captureBitmapOnce()
                            Handler(Looper.getMainLooper()).post {
                                if (screenBitmap == null) {
                                    val errorMsg = service.lastError ?: "سبب غير معروف"
                                    Toast.makeText(this@MainActivity, "فشل التقاط الشاشة: $errorMsg", Toast.LENGTH_LONG).show()
                                    return@post
                                }

                                val result = ImageMatcher.findTemplate(screenBitmap, templateBitmap, minConfidence = 0.6)

                                if (result.found) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "لقى الزر! عند (${result.point?.x?.toInt()}, ${result.point?.y?.toInt()}) بثقة ${"%.2f".format(result.confidence)}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "ما لقاه بهاي الشاشة (أعلى ثقة: ${"%.2f".format(result.confidence)}) - جرب من شاشة اللعبة الرئيسية",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }

        val overlayButton = Button(this).apply {
            text = "٧. فعّل الزر العائم (يشتغل فوق اللعبة)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(
                        this@MainActivity,
                        "فعّل صلاحية \"الظهور فوق التطبيقات الأخرى\" ثم ارجع اضغط هالزر مرة ثانية",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    Toast.makeText(
                        this@MainActivity,
                        "تم تفعيل الزر العائم - رح تلاقيه فوق أي تطبيق تفتحه، اضغطه لتشغيل الفحص",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        root.addView(statusText)
        root.addView(enableButton)
        root.addView(testTapButton)
        root.addView(requestCaptureButton)
        root.addView(captureNowButton)
        root.addView(testOpenCvButton)
        root.addView(testRealTemplateButton)
        root.addView(overlayButton)
        setContentView(root)
    }
}
