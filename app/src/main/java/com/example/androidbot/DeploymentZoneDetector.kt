package com.example.androidbot

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * يكتشف الخط الحدودي البرتقالي/الأحمر يلي اللعبة بترسمه حوالين منطقة
 * القرية بالكامل (بغض النظر عن شكلها)، ويحسب نقاط نشر صحيحة تلقائيًا
 * على طول المحيط - للخارج، بعيد عن السور، ومبعّدة عن مناطق الواجهة.
 */
object DeploymentZoneDetector {

    private const val TAG = "DeploymentZoneDetector"

    data class DeployPoint(val x: Float, val y: Float)

    fun findDeployPoints(
        screenshot: Bitmap,
        numPoints: Int = 12,
        outwardOffset: Double = 60.0
    ): List<DeployPoint> {
        val mat = Mat()
        val hsv = Mat()
        val mask = Mat()

        try {
            Utils.bitmapToMat(screenshot, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

            // مدى اللون البرتقالي/الأحمر لخط الحدود (محدد من عينات حقيقية)
            val lower = Scalar(8.0, 90.0, 90.0)
            val upper = Scalar(28.0, 255.0, 255.0)
            Core.inRange(hsv, lower, upper, mask)

            // نوسّع الخط شوي عشان يصير متصل (اللعبة بترسمه رفيع جدًا)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.dilate(mask, mask, kernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            if (contours.isEmpty()) {
                Log.w(TAG, "ما لقى أي Contour للخط الحدودي")
                return emptyList()
            }

            // نختار أكبر Contour - المفروض يكون خط حدود القرية نفسه
            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return emptyList()
            val points = largest.toArray()

            if (points.isEmpty()) return emptyList()

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
                    val outX = p.x + nx * outwardOffset
                    val outY = p.y + ny * outwardOffset

                    // نتجنب مناطق الواجهة (شريط الجيش تحت، الموارد فوق)
                    if (outY in 130.0..880.0 && outX in 20.0..(screenshot.width - 20).toDouble()) {
                        result.add(DeployPoint(outX.toFloat(), outY.toFloat()))
                    }
                }
                i += step
            }

            Log.i(TAG, "لقى ${result.size} نقطة نشر صالحة")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء كشف منطقة النشر", e)
            return emptyList()
        } finally {
            mat.release()
            hsv.release()
            mask.release()
        }
    }
}
