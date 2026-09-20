package com.example.androidbot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * يكتشف الخط الحدودي البرتقالي/الأحمر يلي اللعبة بترسمه حوالين منطقة
 * القرية القابلة للنشر. النسخة هاي بتتحقق من شكل الخط (مش بس أكبر مساحة
 * لونية)، وبتشتغل بنسب من أبعاد الصورة الفعلية (مش أرقام ثابتة)، وبترجع
 * قائمة فاضية بوضوح لما ما تقدر تتأكد من الحدود - بدون أي تخمين.
 */
object DeploymentZoneDetector {

    private const val TAG = "DeploymentZoneDetector"

    data class DeployPoint(val x: Float, val y: Float)

    /** نتيجة موسّعة فيها صورة تصحيح توضح شو شاف الكاشف بالضبط - لأغراض الاختبار فقط. */
    data class DebugResult(
        val points: List<DeployPoint>,
        val success: Boolean,
        val reason: String,
        val debugBitmap: Bitmap
    )

    // نسب من أبعاد الصورة (مش أرقام بكسل ثابتة) - عشان تشتغل صح على أي دقة شاشة
    private const val TOP_MARGIN_FRACTION = 0.10
    private const val BOTTOM_MARGIN_FRACTION = 0.15
    private const val SIDE_MARGIN_FRACTION = 0.02

    /**
     * الدالة العامة الأساسية - نفس التوقيع القديم بالضبط، ما يحتاج أي تعديل
     * بالملفات يلي بتستدعيها. بترجع قائمة فاضية لو ما قدرت تتأكد من الحدود
     * بثقة كافية (بدل ما ترجع نقاط عشوائية).
     */
    fun findDeployPoints(
        screenshot: Bitmap,
        numPoints: Int = 12,
        outwardOffset: Double = 60.0
    ): List<DeployPoint> {
        val (points, _, _) = detect(screenshot, numPoints, outwardOffset)
        return points
    }

    /**
     * نفس الكشف، بس برجع معه تفاصيل تشخيصية وصورة توضيحية - للاستخدام وقت
     * الاختبار بس، ما بتوقف أي هجوم فعلي ولا بتتدخل بمنطقه.
     */
    fun findDeployPointsDebug(
        screenshot: Bitmap,
        numPoints: Int = 12,
        outwardOffset: Double = 60.0
    ): DebugResult {
        val (points, success, reason, contourForDraw, battleRect) = detectFull(screenshot, numPoints, outwardOffset)
        val debugBitmap = buildDebugBitmap(screenshot, battleRect, contourForDraw, points)
        return DebugResult(points, success, reason, debugBitmap)
    }

    private fun detect(
        screenshot: Bitmap,
        numPoints: Int,
        outwardOffset: Double
    ): Triple<List<DeployPoint>, Boolean, String> {
        val (points, success, reason, _, _) = detectFull(screenshot, numPoints, outwardOffset)
        return Triple(points, success, reason)
    }

    private data class InternalResult(
        val points: List<DeployPoint>,
        val success: Boolean,
        val reason: String,
        val contour: MatOfPoint?,
        val battleRect: android.graphics.Rect
    )

    private fun detectFull(
        screenshot: Bitmap,
        numPoints: Int,
        outwardOffset: Double
    ): InternalResult {
        val width = screenshot.width
        val height = screenshot.height

        // 1) نحدد منطقة اللعب (Battle Area) - نستثني شريط الموارد فوق وشريط
        //    الجيش تحت، كنسبة من أبعاد الصورة نفسها (مش أرقام ثابتة)
        val top = (height * TOP_MARGIN_FRACTION).toInt()
        val bottom = (height * (1 - BOTTOM_MARGIN_FRACTION)).toInt()
        val left = (width * SIDE_MARGIN_FRACTION).toInt()
        val right = (width * (1 - SIDE_MARGIN_FRACTION)).toInt()
        val battleRect = android.graphics.Rect(left, top, right, bottom)

        val mat = Mat()
        val hsv = Mat()
        val mask = Mat()

        try {
            Utils.bitmapToMat(screenshot, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

            // نقتصر التحليل على منطقة اللعب بس - يقلل التشويش من عناصر
            // الواجهة والزخارف اللي فوق/تحت منطقة اللعب
            val battleMat = hsv.submat(battleRect.top, battleRect.bottom, battleRect.left, battleRect.right)

            // مدى اللون البرتقالي/الأحمر لخط الحدود
            val lower = Scalar(8.0, 80.0, 80.0)
            val upper = Scalar(30.0, 255.0, 255.0)
            val localMask = Mat()
            Core.inRange(battleMat, lower, upper, localMask)

            // Closing (تمدد ثم تآكل) بدل تمدد بس - يلمّ فجوات الخط المقطوع
            // بسبب المباني/الأشجار يلي بتتراكب فوقه، بدون ما يكبّر الكتل
            // الملوّنة الثانية بشكل مبالغ فيه
            val kernelSize = (minOf(battleRect.width(), battleRect.height()) * 0.012).coerceAtLeast(5.0)
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize, kernelSize)
            )
            Imgproc.morphologyEx(localMask, localMask, Imgproc.MORPH_CLOSE, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(localMask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            localMask.release()
            battleMat.release()

            if (contours.isEmpty()) {
                return InternalResult(emptyList(), false, "ما لقى أي خط بلون الحدود بمنطقة اللعب", null, battleRect)
            }

            val battleArea = (battleRect.width() * battleRect.height()).toDouble()

            // 2) نفلتر الـ Contours بالشكل - نستبعد أي كتلة صغيرة (أيقونة/زخرفة)
            //    أو كبيرة جدًا (خطأ تغطية الشاشة كلها)، ونفضّل الشكل الحلقي
            //    الرفيع (محيط² / مساحة كبيرة) على الكتلة المصمتة
            data class Candidate(val contour: MatOfPoint, val score: Double)

            val candidates = mutableListOf<Candidate>()
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < battleArea * 0.03 || area > battleArea * 0.90) continue

                val perimeter = Imgproc.arcLength(MatOfPoint2fFrom(c), true)
                if (perimeter <= 0) continue

                // نسبة "الرفعة الحلقية" - كل ما زادت، كل ما كان الشكل أقرب
                // لخط رفيع/حلقة مش كتلة مصمتة (دائرة مصمتة نسبتها ~12.57،
                // خط رفيع طويل نسبته أعلى بكثير)
                val ringScore = (perimeter * perimeter) / area
                if (ringScore < 12.0) continue // كتلة مصمتة مدوّرة - مش خط حدود

                candidates.add(Candidate(c, ringScore * area)) // وزن بالمساحة كمان عشان نفضّل الخط الأشمل
            }

            if (candidates.isEmpty()) {
                return InternalResult(emptyList(), false, "لقى ألوان مشابهة بس ولا وحدة منها شكلها خط حدود حقيقي", null, battleRect)
            }

            val best = candidates.maxByOrNull { it.score }!!.contour
            val points = best.toArray()
            if (points.size < 8) {
                return InternalResult(emptyList(), false, "الخط المكتشف قصير/مجتزأ كتير - مش موثوق", null, battleRect)
            }

            var cx = 0.0
            var cy = 0.0
            for (p in points) {
                cx += p.x
                cy += p.y
            }
            cx /= points.size
            cy /= points.size

            val step = (points.size / numPoints).coerceAtLeast(1)
            val result = mutableListOf<DeployPoint>()

            var i = 0
            while (i < points.size && result.size < numPoints) {
                val p = points[i]
                val dx = p.x - cx
                val dy = p.y - cy
                val dist = sqrt(dx * dx + dy * dy)

                if (dist > 1.0) {
                    val nx = dx / dist
                    val ny = dy / dist
                    // إحداثيات النقطة داخل منطقة اللعب المقصوصة - نضيف الإزاحة
                    // (left, top) عشان نرجعها لإحداثيات الصورة الكاملة
                    val outX = (p.x + nx * outwardOffset) + battleRect.left
                    val outY = (p.y + ny * outwardOffset) + battleRect.top

                    // نتأكد النقطة لسا داخل حدود منطقة اللعب (مش طلعت برا الشاشة كليًا)
                    if (outX >= battleRect.left && outX <= battleRect.right &&
                        outY >= battleRect.top && outY <= battleRect.bottom
                    ) {
                        result.add(DeployPoint(outX.toFloat(), outY.toFloat()))
                    }
                }
                i += step
            }

            if (result.isEmpty()) {
                return InternalResult(emptyList(), false, "لقى خط حدود بس كل النقاط طلعت برا منطقة اللعب المسموحة", best, battleRect)
            }

            Log.i(TAG, "لقى ${result.size} نقطة نشر موثوقة")
            return InternalResult(result, true, "نجح - ${result.size} نقطة", best, battleRect)

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء كشف منطقة النشر", e)
            return InternalResult(emptyList(), false, "استثناء: ${e.javaClass.simpleName} - ${e.message}", null, battleRect)
        } finally {
            mat.release()
            hsv.release()
            mask.release()
        }
    }

    private fun MatOfPoint2fFrom(c: MatOfPoint): org.opencv.core.MatOfPoint2f {
        val m2f = org.opencv.core.MatOfPoint2f()
        c.convertTo(m2f, org.opencv.core.CvType.CV_32F)
        return m2f
    }

    /**
     * صورة تشخيصية بس للاختبار: الخط الأزرق = حدود منطقة اللعب يلي اتحللت،
     * الخط الأخضر = الحدود المكتشفة، النقاط الصفراء = نقاط النشر المقبولة.
     */
    private fun buildDebugBitmap(
        original: Bitmap,
        battleRect: android.graphics.Rect,
        contour: MatOfPoint?,
        points: List<DeployPoint>
    ): Bitmap {
        val debugBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)

        val battlePaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(battleRect, battlePaint)

        if (contour != null) {
            val contourPaint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val pts = contour.toArray()
            for (i in pts.indices) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % pts.size]
                canvas.drawLine(
                    (p1.x + battleRect.left).toFloat(), (p1.y + battleRect.top).toFloat(),
                    (p2.x + battleRect.left).toFloat(), (p2.y + battleRect.top).toFloat(),
                    contourPaint
                )
            }
        }

        val pointPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 14f, pointPaint)
        }

        return debugBitmap
    }
}
