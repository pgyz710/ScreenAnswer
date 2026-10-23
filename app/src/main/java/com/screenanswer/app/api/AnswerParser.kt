package com.screenanswer.app.api

/**
 * Splits the model output into a short answer (shown next to the floating ball) and the longer
 * explanation (only shown when the user opens the popup).
 *
 * The prompt asks for exactly two sections:
 *
 * ```
 * 答案：...
 * 解析：...
 * ```
 *
 * Models are not always obedient, so brackets (【答案】), ASCII colons and a few synonyms are
 * accepted too. When no marker is present at all the whole text is treated as the answer.
 */
object AnswerParser {

    data class Parsed(
        val answer: String,
        val explanation: String,
        val structured: Boolean
    ) {
        val hasAnswer: Boolean get() = answer.isNotBlank()
    }

    private val ANSWER_MARKERS = listOf(
        Regex("""【\s*(?:最终)?答案\s*】\s*[:：]?\s*"""),
        Regex("""\[\s*(?:最终)?答案\s*\]\s*[:：]?\s*"""),
        Regex("""(?m)^\s*(?:最终)?答案\s*[:：]\s*"""),
        Regex("""(?:最终)?答案\s*[:：]\s*"""),
        Regex("""(?im)^\s*(?:final\s+)?answers?\s*[:：]\s*""")
    )

    private val EXPLANATION_MARKERS = listOf(
        Regex("""【\s*(?:解析|解题思路|详解|Explanation)\s*】\s*[:：]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""\[\s*(?:解析|解题思路|详解|Explanation)\s*\]\s*[:：]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""(?m)^\s*(?:解析|解题思路|详解|Explanation)\s*[:：]\s*""", RegexOption.IGNORE_CASE),
        Regex("""(?:解析|解题思路|详解)\s*[:：]\s*"""),
        Regex("""(?im)^\s*(?:explanation|reasoning)\s*[:：]\s*""")
    )

    private val headingPrefix = Regex("""^#{1,6}\s*""")
    private val bulletPrefix = Regex("""^[-*•·]\s+""")
    private val repeatedNewlines = Regex("""\n{3,}""")

    /**
     * @param fallbackToWhole when no marker is found, treat the whole text as the answer. Use false
     *   while streaming so a half-received marker does not flash as an "answer".
     */
    fun parse(raw: String, fallbackToWhole: Boolean = true): Parsed {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')

        val answerMatch = findMarker(text, ANSWER_MARKERS)
        val explanationMatch = findMarker(text, EXPLANATION_MARKERS)

        val answer: String
        val explanation: String
        var structured = false

        when {
            answerMatch != null && explanationMatch != null &&
                explanationMatch.range.first > answerMatch.range.first -> {
                structured = true
                answer = text.substring(answerMatch.range.last + 1, explanationMatch.range.first)
                explanation = text.substring(explanationMatch.range.last + 1)
            }

            answerMatch != null && (explanationMatch == null ||
                explanationMatch.range.first < answerMatch.range.first) -> {
                structured = true
                answer = text.substring(answerMatch.range.last + 1)
                explanation = ""
            }

            explanationMatch != null -> {
                structured = true
                answer = ""
                explanation = text.substring(explanationMatch.range.last + 1)
            }

            else -> {
                answer = if (fallbackToWhole) text else ""
                explanation = ""
            }
        }

        return Parsed(clean(answer), clean(explanation), structured)
    }

    private fun findMarker(text: String, patterns: List<Regex>): MatchResult? =
        patterns.mapNotNull { it.find(text) }.minByOrNull { it.range.first }

    private fun clean(value: String): String {
        val lines = value
            .replace("**", "")
            .replace("__", "")
            .split('\n')
            .map { line ->
                line.trim()
                    .replace(headingPrefix, "")
                    .replace(bulletPrefix, "")
                    .trim()
            }
        return lines.joinToString("\n")
            .replace(repeatedNewlines, "\n\n")
            .trim()
    }
}
