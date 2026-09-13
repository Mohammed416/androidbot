package com.example.androidbot

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * هاي الطبقة يلي "تشوف" - بتاخد لقطة شاشة كاملة وقالب صغير (مثلاً صورة زر)
 * وبتدور على القالب جوا اللقطة الكاملة، وترجع الإحداثيات وين لقته + نسبة الثقة.
 */
object ImageMatcher {

    private const val TAG = "ImageMatcher"

    data class MatchResult(
        val found: Boolean,
        val point: PointF? = null,   // مركز المكان يلي انلقى فيه القالب
        val confidence: Double = 0.0  // كل ما كانت أعلى (قريبة من 1.0) كل ما كانت مطابقة أدق
    )

    /**
     * يدور على "template" (قالب صغير، مثلاً صورة زر الهجوم) جوا "screenshot" (لقطة الشاشة الكاملة).
     * minConfidence: أقل نسبة ثقة نقبلها كمطابقة صحيحة (افتراضيًا 0.8 = 80%)
     */
    fun findTemplate(
        screenshot: Bitmap,
        template: Bitmap,
        minConfidence: Double = 0.8
    ): MatchResult {
        try {
            val screenMat = Mat()
            val templateMat = Mat()
            Utils.bitmapToMat(screenshot, screenMat)
            Utils.bitmapToMat(template, templateMat)

            // تحويل لتدرج رمادي بيسرّع المقارنة وبيقلل تأثير اختلاف الألوان الطفيف
            Imgproc.cvtColor(screenMat, screenMat, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(templateMat, templateMat, Imgproc.COLOR_RGBA2GRAY)

            val resultCols = screenMat.cols() - templateMat.cols() + 1
            val resultRows = screenMat.rows() - templateMat.rows() + 1

            if (resultCols <= 0 || resultRows <= 0) {
                Log.w(TAG, "القالب أكبر من اللقطة - تأكد من حجم الصور")
                return MatchResult(found = false)
            }

            val result = Mat(resultRows, resultCols, org.opencv.core.CvType.CV_32FC1)
            Imgproc.matchTemplate(screenMat, templateMat, result, Imgproc.TM_CCOEFF_NORMED)

    
            val mmr: Core.MinMaxLocResult = Core.minMaxLoc(result)
            val confidence = mmr.maxVal

            screenMat.release()
            templateMat.release()
            result.release()

            return if (confidence >= minConfidence) {
                // maxLoc هو الزاوية العليا اليسار لمكان المطابقة - نحسب المركز
                val centerX = mmr.maxLoc.x + templateMat.cols() / 2.0
                val centerY = mmr.maxLoc.y + templateMat.rows() / 2.0
                Log.i(TAG, "لقيت مطابقة بثقة $confidence عند ($centerX, $centerY)")
                MatchResult(
                    found = true,
                    point = PointF(centerX.toFloat(), centerY.toFloat()),
                    confidence = confidence
                )
            } else {
                Log.i(TAG, "ما في مطابقة كافية - أعلى ثقة كانت $confidence")
                MatchResult(found = false, confidence = confidence)
            }

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء تحليل الصورة: ${e.message}", e)
            return MatchResult(found = false)
        }
    }
}
