package com.example.androidbot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val statusText = TextView(this).apply {
            text = "الحالة: تحقق من تفعيل الخدمة"
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

        root.addView(statusText)
        root.addView(enableButton)
        root.addView(testTapButton)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
    }
}
