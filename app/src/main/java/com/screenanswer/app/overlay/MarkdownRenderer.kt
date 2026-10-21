package com.screenanswer.app.overlay

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Tiny Markdown-lite renderer for streamed answers: headings, bullets, **bold**, `inline code`
 * and fenced code blocks. It deliberately tolerates half-received markers while streaming.
 */
object MarkdownRenderer {

    private const val CODE_BACKGROUND = 0x1A7C9CFF
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^[-*]\\s+")

    fun render(raw: String): CharSequence {
        val out = SpannableStringBuilder()
        var inCodeBlock = false

        raw.split('\n').forEachIndexed { index, original ->
            if (index > 0) out.append('\n')
            val trimmedEnd = original.trimEnd()
            if (trimmedEnd.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                return@forEachIndexed
            }
            if (inCodeBlock) {
                val start = out.length
                out.append(trimmedEnd)
                if (out.length > start) {
                    out.setSpan(
                        TypefaceSpan("monospace"), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    out.setSpan(
                        BackgroundColorSpan(CODE_BACKGROUND), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                return@forEachIndexed
            }

            var line = trimmedEnd.replace(heading, "")
            if (bullet.containsMatchIn(line)) {
                line = "• " + line.replaceFirst(bullet, "")
            }
            appendInline(out, line)
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String) {
        var index = 0
        while (index < line.length) {
            when {
                line.startsWith("**", index) -> {
                    val end = line.indexOf("**", index + 2)
                    if (end == -1) {
                        out.append(line, index, line.length)
                        return
                    }
                    val start = out.length
                    out.append(line, index + 2, end)
                    if (out.length > start) {
                        out.setSpan(
                            StyleSpan(Typeface.BOLD), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    index = end + 2
                }

                line[index] == '`' -> {
                    val end = line.indexOf('`', index + 1)
                    if (end == -1) {
                        out.append(line, index, line.length)
                        return
                    }
                    val start = out.length
                    out.append(line, index + 1, end)
                    if (out.length > start) {
                        out.setSpan(
                            TypefaceSpan("monospace"), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        out.setSpan(
                            BackgroundColorSpan(CODE_BACKGROUND), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    index = end + 1
                }

                else -> {
                    var next = line.indexOf("**", index).let { if (it < 0) line.length else it }
                    val tick = line.indexOf('`', index).let { if (it < 0) line.length else it }
                    next = minOf(next, tick)
                    if (next == index) {
                        out.append(line[index])
                        index++
                    } else {
                        out.append(line, index, next)
                        index = next
                    }
                }
            }
        }
    }
}
