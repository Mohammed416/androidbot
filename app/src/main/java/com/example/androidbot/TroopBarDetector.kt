package com.example.androidbot

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * يكتشف مواقع بطاقات الجيش والتعاويذ فعليًا من نفس لقطة المعركة الحالية
 * (عن طريق قراءة النصوص "xN" فوق كل بطاقة)، بدل إحداثيات ثابتة بتتحرك
 * كل ما تغيّر عدد البطاقات بالشريط.
 */
object TroopBarDetector {

    data class QuantityCard(val centerX: Float, val quantity: Int)

    /**
     * يرجع كل البطاقات يلي عليها رقم "xN" (الجيش والتعاويذ)، مرتبة من
     * اليسار لليمين. الأبطال ما بيظهروا هون لأنه ما عندهم رقم "x".
     */
    fun detectQuantityCards(barBitmap: Bitmap): List<QuantityCard> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(barBitmap, 0)
        val result = try {
            Tasks.await(recognizer.process(image))
        } catch (e: Exception) {
            return emptyList()
        }

        val cards = mutableListOf<QuantityCard>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim().lowercase()
                if (text.startsWith("x")) {
                    val qty = text.drop(1).filter { it.isDigit() }.toIntOrNull() ?: continue
                    val box = line.boundingBox ?: continue
                    cards.add(QuantityCard(box.exactCenterX().toFloat(), qty))
                }
            }
        }
        return cards.sortedBy { it.centerX }
    }
}
