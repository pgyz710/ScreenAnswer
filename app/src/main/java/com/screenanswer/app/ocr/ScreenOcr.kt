package com.screenanswer.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * On-device OCR (ML Kit, bundled models). Chinese and Latin models are both bundled, so English
 * exam papers can use the Latin model instead of the (much weaker for English) Chinese one.
 *
 * The screenshot never leaves the phone.
 */
class ScreenOcr : Closeable {

    private var chinese: TextRecognizer? = null
    private var latin: TextRecognizer? = null

    private fun chineseClient(): TextRecognizer =
        chinese ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { chinese = it }

    private fun latinClient(): TextRecognizer =
        latin ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { latin = it }

    /**
     * @param english true for English papers: use the Latin model, fall back to Chinese if the
     *   Latin model finds nothing (mixed pages).
     */
    suspend fun recognize(bitmap: Bitmap, english: Boolean = false): String {
        val primary = if (english) latinClient() else chineseClient()
        val fallback = if (english) chineseClient() else latinClient()

        // Two-column exam papers confuse line detection: a text line can contain both columns.
        // Detect the white gutter and OCR each column separately, which also fixes reading order.
        val gutter = findColumnGutter(bitmap)
        var text = if (gutter != null) {
            processColumns(primary, bitmap, gutter)
        } else {
            process(primary, bitmap)
        }
        if (text.isBlank()) text = process(fallback, bitmap)
        return text
    }

    private suspend fun processColumns(
        client: TextRecognizer,
        bitmap: Bitmap,
        gutter: Int
    ): String {
        val height = bitmap.height
        val leftWidth = gutter.coerceIn(1, bitmap.width - 1)
        val left = Bitmap.createBitmap(bitmap, 0, 0, leftWidth, height)
        val right = Bitmap.createBitmap(bitmap, leftWidth, 0, bitmap.width - leftWidth, height)
        return try {
            val leftText = process(client, left)
            val rightText = process(client, right)
            listOf(leftText, rightText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } finally {
            left.recycle()
            right.recycle()
        }
    }

    /**
     * Finds the white vertical gutter of a two-column layout by sampling pixels. Only the central
     * part of the screen is analysed so the status bar, browser toolbar and bottom navigation do
     * not break the run of white columns.
     *
     * @return the x coordinate to split at, or null when the page looks single-column.
     */
    private fun findColumnGutter(bitmap: Bitmap): Int? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 300 || height < 400) return null

        val top = (height * 0.15f).toInt()
        val bottom = (height * 0.88f).toInt()
        if (bottom - top < 200) return null

        val columnStep = max(1, width / 240)
        val rowStep = max(1, (bottom - top) / 220)
        val columnCount = width / columnStep + 1
        val darkInColumn = IntArray(columnCount)
        var sampledRows = 0

        var y = top
        while (y < bottom) {
            sampledRows++
            var x = 0
            while (x < width) {
                val color = bitmap.getPixel(x, y)
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                if (luminance < 150) darkInColumn[x / columnStep]++
                x += columnStep
            }
            y += rowStep
        }
        if (sampledRows < 50) return null

        // A gutter column is white for almost the whole analysed height (<=1% dark rows).
        val whiteThreshold = max(1, (sampledRows * 0.01f).toInt())
        val denseThreshold = whiteThreshold * 3
        val runs = mutableListOf<IntRange>()
        var runStart = -1
        for (index in 0 until columnCount) {
            val white = darkInColumn[index] <= whiteThreshold
            if (white) {
                if (runStart < 0) runStart = index
            } else if (runStart >= 0) {
                runs.add(runStart until index)
                runStart = -1
            }
        }
        if (runStart >= 0) runs.add(runStart until columnCount)

        // Margins are the widest white runs, so evaluate every run and keep the valid one.
        var bestSplit: Int? = null
        var bestWidth = 0
        runs.forEach { run ->
            val start = run.first * columnStep
            val end = min(width, run.last * columnStep)
            val runWidth = end - start
            if (runWidth < width * 0.02f) return@forEach

            val split = (start + end) / 2
            if (split < width * 0.25f || split > width * 0.75f) return@forEach

            val leftDense = (0 until run.first).count { darkInColumn[it] > denseThreshold }
            val rightDense = (run.last until columnCount)
                .count { darkInColumn[it] > denseThreshold }
            if (leftDense < 3 || rightDense < 3) return@forEach

            if (runWidth > bestWidth) {
                bestWidth = runWidth
                bestSplit = split
            }
        }
        return bestSplit
    }

    private suspend fun process(
        client: TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCancellableCoroutine { continuation ->
        client.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                continuation.resume(orderText(result))
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    /**
     * ML Kit returns blocks in its own reading order, which interleaves the two columns common in
     * exam papers. Rebuild the order: detect a two-column layout by the widest horizontal gap,
     * then emit the left column top-to-bottom and then the right column.
     */
    private fun orderText(result: Text): String {
        val blocks = result.textBlocks
            .mapNotNull { block -> orderedBlock(block) }
            .filter { it.lines.isNotEmpty() }
        if (blocks.isEmpty()) return ""

        val columns = splitColumns(blocks)
        val ordered = if (columns != null) {
            blocks.filter { it.box.left < columns }.sortedBy { it.box.top } +
                blocks.filter { it.box.left >= columns }.sortedBy { it.box.top }
        } else {
            blocks.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }

        val builder = StringBuilder()
        ordered.forEach { block ->
            block.lines.forEach { line ->
                if (line.isNotEmpty()) builder.append(line).append('\n')
            }
        }
        return builder.toString().trim()
    }

    private fun orderedBlock(block: Text.TextBlock): OrderedBlock? {
        val box = block.boundingBox ?: return null
        val lines = block.lines
            .filter { it.boundingBox != null }
            .sortedWith(compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left }))
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return OrderedBlock(box, lines)
    }

    /**
     * @return the x coordinate that separates the two columns, or null for single-column text.
     */
    private fun splitColumns(blocks: List<OrderedBlock>): Int? {
        if (blocks.size < 4) return null
        val sorted = blocks.sortedBy { it.box.left }
        var bestGap = 0
        var splitX = 0
        for (index in 0 until sorted.lastIndex) {
            val leftEdge = sorted[index].box.right
            val rightEdge = sorted[index + 1].box.left
            val gap = rightEdge - leftEdge
            if (gap > bestGap) {
                bestGap = gap
                splitX = (leftEdge + rightEdge) / 2
            }
        }
        if (bestGap <= 0) return null
        val leftCount = blocks.count { it.box.left < splitX }
        val rightCount = blocks.size - leftCount
        // A real column gap leaves at least two blocks on each side.
        if (leftCount < 2 || rightCount < 2) return null
        return splitX
    }

    private data class OrderedBlock(val box: Rect, val lines: List<String>)

    override fun close() {
        chinese?.close()
        latin?.close()
        chinese = null
        latin = null
    }
}
