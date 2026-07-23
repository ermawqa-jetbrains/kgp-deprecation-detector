package org.jetbrains.kotlin.deprecations

import java.io.File

data class EmbeddedScript(
    val startLine: Int,
    val startColumn: Int,
    val text: String,
)

/**
 * Pulls Gradle script fragments out of `.kt`/`.java` source
 *
 * v1 handles multiline triple-quoted literals only (Gradle scripts are always multiline);
 * single-line scripts are out of scope.
 *
 * A literal is only returned if it looks like a Gradle script (per [SCRIPT_MARKER]) - but if the
 * literal is preceded by an `@Language("…")` annotation (a real Kotlin annotation, or the `//
 * @Language("…")` comment form used on locals), that tag is authoritative and skipped only when
 * it names a language that isn't Gradle-ish (see [ALLOWED_LANGUAGES]): this is what keeps e.g. a
 * `@Language("Markdown")` README-as-a-string from being scanned just because its documentation
 * happens to show a `plugins { }` code sample.
 */
object EmbeddedScriptExtractor {

    private val SCRIPT_MARKER = Regex(
        "allprojects|afterEvaluate|tasks\\.(create|register)|" +
            "gradle\\.(ext|rootProject|settingsEvaluated|buildFinished)|pluginManager|GradleVersion|" +
            "plugins\\s*\\{|kotlin\\s*\\{|dependencies\\s*\\{|compilerOptions|kotlinOptions|withType\\(",
    )
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
                repeat(3) { advance() } // skip opening """
                val contentStartLine = line
                val contentStartCol = col
                val sb = StringBuilder()
                while (i < n && !isTripleQuoteAt(i)) {
                    sb.append(text[i]); advance()
                }
                if (i < n) repeat(3) { advance() } // skip closing """
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

    // how many lines above the opening """ to look for an @Language tag. The declaration can
    // split across lines (annotation, then `internal val X: String =`, then `"""` on its own
    // line), so 1 line isn't enough; a small bounded window avoids picking up an unrelated
    // annotation from a declaration further above.
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
