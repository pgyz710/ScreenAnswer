package com.screenanswer.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR (ML Kit, bundled Chinese model). Works without Google Play Services and without
 * network access — the screenshot never leaves the phone.
 */
class ScreenOcr : Closeable {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                val builder = StringBuilder()
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val text = line.text.trim()
                        if (text.isNotEmpty()) builder.append(text).append('\n')
                    }
                }
                continuation.resume(builder.toString().trim())
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    override fun close() {
        recognizer.close()
    }
}
