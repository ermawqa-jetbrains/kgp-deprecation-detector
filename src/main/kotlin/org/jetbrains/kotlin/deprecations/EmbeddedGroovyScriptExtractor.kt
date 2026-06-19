package org.jetbrains.kotlin.deprecations

import java.io.File

/** A Gradle script hardcoded as a triple-quoted string literal inside a `.kt`/`.java` file. */
data class EmbeddedScript(
    /** 1-based line in the host file of the literal's first content character. */
    val startLine: Int,
    /** 1-based column in the host file of the literal's first content character. */
    val startColumn: Int,
    val text: String,
)

/**
 * Pulls Gradle script fragments out of `.kt`/`.java` source — IDE-injected init/build
 * scripts hardcoded as triple-quoted string literals (e.g. `val initScript = """ … """`).
 *
 * v1 handles multiline triple-quoted literals only (Groovy Gradle scripts are always
 * multiline); single-line `"…"` scripts are out of scope. The Kotlin multi-dollar
 * prefix (`$$"""…"""`) is handled transparently — the `$$` are ordinary characters
 * before the `"""` delimiter. Only literals that look like Gradle scripts (per
 * [SCRIPT_MARKER]) are returned, so plain `description = """ … """` prose is skipped.
 */
object EmbeddedGroovyScriptExtractor {

    private val SCRIPT_MARKER = Regex(
        "allprojects|afterEvaluate|tasks\\.(create|register)|gradle\\.|pluginManager|GradleVersion",
    )

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
                repeat(3) { advance() } // skip opening """
                val contentStartLine = line
                val contentStartCol = col
                val sb = StringBuilder()
                while (i < n && !isTripleQuoteAt(i)) {
                    sb.append(text[i]); advance()
                }
                if (i < n) repeat(3) { advance() } // skip closing """
                val content = sb.toString()
                if (SCRIPT_MARKER.containsMatchIn(content)) {
                    result.add(EmbeddedScript(contentStartLine, contentStartCol, content))
                }
            } else {
                advance()
            }
        }
        return result
    }
}
