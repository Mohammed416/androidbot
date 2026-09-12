package com.example.androidbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BotAccessibilityService"

        var instance: BotAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "الخدمة اتفعّلت وجاهزة لإرسال إيماءات")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "الخدمة انقطعت (onInterrupt)")
    }

    fun performTap(x: Float, y: Float, durationMs: Long = 50L) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "الضغطة نُفّذت بنجاح عند ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "الضغطة أُلغيت عند ($x, $y)")
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "فشل إرسال الإيماءة - تأكد إن canPerformGestures مفعّلة بالإعدادات")
        }
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, null, null)
    }
}
