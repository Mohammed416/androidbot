package com.example.androidbot

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * يقرأ الأرقام الظاهرة بصورة صغيرة (مثلاً منطقة الذهب أو الإكسير)
 * ويرجعها كرقم صحيح. يشتغل بشكل متزامن (Blocking) - لازم يُستدعى من Thread خلفي فقط.
 */
object TextReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun readNumber(bitmap: Bitmap): Long? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            val rawText = result.text
            // نستخرج الأرقام بس من النص المقروء، ونتجاهل أي حروف أو رموز
            val digitsOnly = rawText.filter { it.isDigit() }
            if (digitsOnly.isEmpty()) null else digitsOnly.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
