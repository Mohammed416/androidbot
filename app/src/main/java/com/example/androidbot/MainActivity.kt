package com.example.androidbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val statusText = TextView(this).apply {
            text = "الحالة: تحقق من تفعيل الخدمات"
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
                    Toast.makeText(
                        this@MainActivity,
                        "الخدمة مش مفعّلة - فعّلها أول من الإعدادات",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    service.performTap(500f, 800f)
                    Toast.makeText(
                        this@MainActivity,
                        "تم إرسال ضغطة تجريبية عند (500, 800)",
                        Toast.LENGTH_SHORT
                    ).show()
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
                    Toast.makeText(
                        this@MainActivity,
                        "لازم تفعّل صلاحية التقاط الشاشة أول (الزر ٣)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "جاري الالتقاط...", Toast.LENGTH_SHORT).show()

                    thread {
                        val path = service.captureOnce()
                        Handler(Looper.getMainLooper()).post {
                            if (path != null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "تم الحفظ: $path",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                // هلق منعرض رسالة الخطأ الحقيقية بدل رسالة عامة
                                val errorMsg = service.lastError ?: "سبب غير معروف"
                                Toast.makeText(
                                    this@MainActivity,
                                    "فشل: $errorMsg",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        root.addView(statusText)
        root.addView(enableButton)
        root.addView(testTapButton)
        root.addView(requestCaptureButton)
        root.addView(captureNowButton)
        setContentView(root)
    }
}
