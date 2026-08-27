package org.jetbrains.kotlin.deprecations

import java.io.File

data class EmbeddedScript(
    val startLine: Int,
    val startColumn: Int,
    val text: String,
)

/**
 * Extracts multiline triple-quoted Gradle script fragments from .kt/.java files.
 *
 * A literal is skipped if it has an @Language tag that isn't 'groovy' or 'kotlin'.
 * This prevents scanning documentation snippets.
 */
object EmbeddedScriptExtractor {

    private val SCRIPT_MARKER = Regex(EmbeddedScriptFinder.MARKER)
    private val LANGUAGE_ANNOTATION = Regex("""@Language\(\s*"([^"]+)"\s*\)""")
    private val ALLOWED_LANGUAGES = setOf("groovy", "kotlin")

    fun extract(file: File): List<EmbeddedScript> = extractFromText(file.readText())

    internal fun extractFromText(text: String): List<EmbeddedScript> {
        val result = mutableListOf<EmbeddedScript>()
        val n = text.length
        var i = 0
        var line = 1
        var col = 1

        fun advance() {
            if (text[i] == '\n') { line++; col = 1 } else col++
            i++
        }
        fun isTripleQuoteAt(idx: Int) =
            idx + 2 < n && text[idx] == '"' && text[idx + 1] == '"' && text[idx + 2] == '"'

        while (i < n) {
            if (isTripleQuoteAt(i)) {
                val quoteStart = i
                repeat(3) { advance() }
                val contentStartLine = line
                val contentStartCol = col
                val sb = StringBuilder()
                while (i < n && !isTripleQuoteAt(i)) {
                    sb.append(text[i]); advance()
                }
                if (i < n) repeat(3) { advance() }
                val content = sb.toString()
                val language = languageAnnotationBefore(text, quoteStart)
                val languageAllowed = language == null || language.lowercase() in ALLOWED_LANGUAGES
                if (languageAllowed && SCRIPT_MARKER.containsMatchIn(content)) {
                    result.add(EmbeddedScript(contentStartLine, contentStartCol, content))
                }
            } else {
                advance()
            }
        }
        return result
    }

    // Scans up to 3 lines back for @Language tags. Handles wrapped declarations.
    private const val LANGUAGE_LOOKBACK_LINES = 3

    /** The `@Language("X")` tag within [LANGUAGE_LOOKBACK_LINES] lines before [quoteStart]. */
    private fun languageAnnotationBefore(text: String, quoteStart: Int): String? {
        var lineEnd = quoteStart
        while (lineEnd > 0 && text[lineEnd - 1] != '\n') lineEnd--
        repeat(LANGUAGE_LOOKBACK_LINES) {
            if (lineEnd == 0) return null
            val end = lineEnd - 1 // the '\n' before this line
            var start = end
            while (start > 0 && text[start - 1] != '\n') start--
            LANGUAGE_ANNOTATION.find(text.substring(start, end))?.let { return it.groupValues[1] }
            lineEnd = start
        }
        return null
    }
}
