package com.example.androidbot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * يكتشف منطقة النشر الصالحة عن طريق تصنيف كل بكسل: "عشب" (منطقة مفتوحة)
 * أو "مبنى/طريق" (أي شي مش عشب)، وبعدين يستبعد أي عشب قريب من مبنى
 * (هامش أمان). هاي الطريقة أعم من كشف "خط حدودي واحد" لأنها مش معتمدة
 * على لون خط معيّن ممكن يتشابه مع عناصر الواجهة.
 */
object DeploymentZoneDetector {

    private const val TAG = "DeploymentZoneDetector"

    data class DeployPoint(val x: Float, val y: Float)

    data class DebugResult(
        val points: List<DeployPoint>,
        val success: Boolean,
        val reason: String,
        val debugBitmap: Bitmap
    )

    private const val TOP_MARGIN_FRACTION = 0.10
    private const val BOTTOM_MARGIN_FRACTION = 0.15
    private const val SIDE_MARGIN_FRACTION = 0.09

    // مدى اللون الأخضر (عشب) - واسع شوي عشان يغطي درجتين العشب
    // المتبدّلتين (رقعة الشطرنج) يلي اللعبة بترسمها
    private val GRASS_LOWER = Scalar(30.0, 30.0, 30.0)
    private val GRASS_UPPER = Scalar(95.0, 255.0, 255.0)

    fun findDeployPoints(
        screenshot: Bitmap,
        numPoints: Int = 12,
        outwardOffset: Double = 60.0
    ): List<DeployPoint> {
        return detect(screenshot, numPoints).points
    }

    fun findDeployPointsDebug(
        screenshot: Bitmap,
        numPoints: Int = 12,
        outwardOffset: Double = 60.0
    ): DebugResult {
        val result = detect(screenshot, numPoints)
        val debugBitmap = buildDebugBitmap(screenshot, result.battleRect, result.validMaskBitmap, result.points)
        return DebugResult(result.points, result.success, result.reason, debugBitmap)
    }

    private data class InternalResult(
        val points: List<DeployPoint>,
        val success: Boolean,
        val reason: String,
        val battleRect: android.graphics.Rect,
        val validMaskBitmap: Bitmap?
    )

    private fun detect(screenshot: Bitmap, numPoints: Int): InternalResult {
        val width = screenshot.width
        val height = screenshot.height

        val top = (height * TOP_MARGIN_FRACTION).toInt()
        val bottom = (height * (1 - BOTTOM_MARGIN_FRACTION)).toInt()
        val left = (width * SIDE_MARGIN_FRACTION).toInt()
        val right = (width * (1 - SIDE_MARGIN_FRACTION)).toInt()
        val battleRect = android.graphics.Rect(left, top, right, bottom)

        val mat = Mat()
        val hsv = Mat()

        try {
            Utils.bitmapToMat(screenshot, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)

            val battleMat = hsv.submat(battleRect.top, battleRect.bottom, battleRect.left, battleRect.right)

            // 1) قناع العشب (المناطق الخضراء المفتوحة)
            val grassMask = Mat()
            Core.inRange(battleMat, GRASS_LOWER, GRASS_UPPER, grassMask)
            battleMat.release()

            // ننظّف قناع العشب من نقاط تشويش صغيرة (Opening)
            val smallKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            Imgproc.morphologyEx(grassMask, grassMask, Imgproc.MORPH_OPEN, smallKernel)

            // 2) قناع "غير العشب" (مباني/طرق/زخارف) = عكس قناع العشب
            val nonGrassMask = Mat()
            Core.bitwise_not(grassMask, nonGrassMask)

            // نوسّع منطقة "غير العشب" عشان نضيف هامش أمان حوالين كل مبنى
            // (بنسبة من أبعاد منطقة اللعب، مش رقم بكسل ثابت)
            val marginSize = (minOf(battleRect.width(), battleRect.height()) * 0.025).coerceAtLeast(8.0)
            val marginKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(marginSize, marginSize)
            )
            Imgproc.dilate(nonGrassMask, nonGrassMask, marginKernel)

            // 3) المنطقة الصالحة = عشب وفي نفس الوقت مش داخل الهامش الموسّع
            val invalidZone = Mat()
            Core.bitwise_not(nonGrassMask, invalidZone) // هلق invalidZone = عكس (غير العشب الموسّع) - اسم مؤقت
            val validMask = Mat()
            Core.bitwise_and(grassMask, invalidZone, validMask)

            grassMask.release()
            nonGrassMask.release()
            invalidZone.release()

            // 4) نمسح شبكة نقاط منتظمة فوق القناع الصالح، ونلقط أي نقطة لسا
            //    عشب صافي (نتأكد من جيرانها كمان، مش بس نقطة وحدة معزولة)
            val gridStep = (minOf(battleRect.width(), battleRect.height()) / 22).coerceAtLeast(20)
            val candidatePoints = mutableListOf<DeployPoint>()

            var y = 0
            while (y < validMask.rows()) {
                var x = 0
                while (x < validMask.cols()) {
                    if (isSolidValidArea(validMask, x, y, gridStep / 3)) {
                        candidatePoints.add(
                            DeployPoint((x + battleRect.left).toFloat(), (y + battleRect.top).toFloat())
                        )
                    }
                    x += gridStep
                }
                y += gridStep
            }

            val debugMaskBitmap = matMaskToBitmap(validMask)
            validMask.release()

            if (candidatePoints.isEmpty()) {
                return InternalResult(emptyList(), false, "ما لقى أي منطقة عشب مفتوحة كافية بعيدة عن المباني", battleRect, debugMaskBitmap)
            }

            // نختار عدد "numPoints" موزّعين من النقاط المرشحة (كل ما بعد عن بعض أفضل)
            val selected = downsample(candidatePoints, numPoints)

            Log.i(TAG, "لقى ${candidatePoints.size} نقطة مرشحة، اخترنا ${selected.size}")
            return InternalResult(selected, true, "نجح - ${selected.size} نقطة من ${candidatePoints.size} مرشحة", battleRect, debugMaskBitmap)

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء كشف منطقة النشر", e)
            return InternalResult(emptyList(), false, "استثناء: ${e.javaClass.simpleName} - ${e.message}", battleRect, null)
        } finally {
            mat.release()
            hsv.release()
        }
    }

    /**
     * يتأكد إن المنطقة حوالين نقطة معيّنة "عشب صافي" بالكامل (مش بس بكسل
     * واحد عشوائي) - عشان نضمن مساحة كافية فعليًا للنشر، مش نقطة حدّية.
     */
    private fun isSolidValidArea(mask: Mat, cx: Int, cy: Int, radius: Int): Boolean {
        val r = radius.coerceAtLeast(4)
        if (cx - r < 0 || cy - r < 0 || cx + r >= mask.cols() || cy + r >= mask.rows()) return false

        var whiteCount = 0
        var total = 0
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                total++
                if (mask.get(cy + dy, cx + dx)[0] > 0) whiteCount++
                dx += (r / 2).coerceAtLeast(1)
            }
            dy += (r / 2).coerceAtLeast(1)
        }
        return total > 0 && (whiteCount.toDouble() / total) > 0.85
    }

    /**
     * يوزّع النقاط المختارة عشان تكون متباعدة عن بعض (مش كلها بزاوية وحدة)،
     * بطريقة بسيطة: يقسّم المنطقة لخلايا شبكة، ونقطة وحدة بالكثير من كل خلية.
     */
    private fun downsample(points: List<DeployPoint>, target: Int): List<DeployPoint> {
        if (points.size <= target) return points

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val gridDim = kotlin.math.ceil(kotlin.math.sqrt(target.toDouble())).toInt().coerceAtLeast(1)
        val cellW = ((maxX - minX) / gridDim).coerceAtLeast(1f)
        val cellH = ((maxY - minY) / gridDim).coerceAtLeast(1f)

        val cells = LinkedHashMap<Pair<Int, Int>, DeployPoint>()
        for (p in points) {
            val cellX = ((p.x - minX) / cellW).toInt().coerceIn(0, gridDim - 1)
            val cellY = ((p.y - minY) / cellH).toInt().coerceIn(0, gridDim - 1)
            cells.putIfAbsent(cellX to cellY, p)
        }

        return cells.values.take(target)
    }

    private fun matMaskToBitmap(mask: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mask.cols(), mask.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mask, bmp)
        return bmp
    }

    private fun buildDebugBitmap(
        original: Bitmap,
        battleRect: android.graphics.Rect,
        validMaskBitmap: Bitmap?,
        points: List<DeployPoint>
    ): Bitmap {
        val debugBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(debugBitmap)

        // نرسم قناع المنطقة الصالحة بشفافية فوق الصورة الأصلية (أخضر شفاف)
        if (validMaskBitmap != null) {
            val overlayPaint = Paint().apply { alpha = 100 }
            val tinted = Bitmap.createBitmap(validMaskBitmap.width, validMaskBitmap.height, Bitmap.Config.ARGB_8888)
            val tintCanvas = Canvas(tinted)
            val tintPaint = Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            tintCanvas.drawBitmap(validMaskBitmap, 0f, 0f, null)
            tintCanvas.drawBitmap(validMaskBitmap, 0f, 0f, tintPaint)
            canvas.drawBitmap(tinted, battleRect.left.toFloat(), battleRect.top.toFloat(), overlayPaint)
        }

        val battlePaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(battleRect, battlePaint)

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
