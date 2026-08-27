package org.jetbrains.kotlin.deprecations

import java.io.File

/** A string-literal method/field name passed as the first arg to a `callReflective*` helper */
data class ReflectiveCallArg(val name: String, val line: Int, val column: Int)

/**
 * Pulls reflective-call target names out of `.kt`/`.java` source,
 * e.g. `instance.callReflectiveGetter("getCompilation", logger)`
 *
 * Matching runs over the **whole** masked text rather than line by line: these helpers take 2-3
 * extra arguments, so the formatter routinely wraps the call and puts the literal on the line
 * after `callReflective…(`. Splitting into lines first made every such call invisible - a
 * false-negative class in exactly the `gradleTooling/reflect` sources this pass targets.
 *
 * Known limitation: only inline string literals are seen. A name held in a `const val` or built
 * by concatenation is invisible - resolving it would need the very compilation this pass exists
 * to work without.
 */
object ReflectiveCallArgExtractor {

    // `\s*` spans newlines, so the literal may sit on a later line than the call itself.
    private val CALL_SITE = Regex("""\bcallReflective\w*\s*\(\s*"([A-Za-z_][A-Za-z0-9_]*)"""")

    fun extract(file: File): List<ReflectiveCallArg> = extractFromText(file.readText())

    internal fun extractFromText(text: String): List<ReflectiveCallArg> {
        val masked = maskComments(text)
        return CALL_SITE.findAll(masked).map { match ->
            val nameGroup = match.groups[1]!!
            val offset = nameGroup.range.first
            ReflectiveCallArg(nameGroup.value, lineOf(masked, offset), columnOf(masked, offset))
        }.toList()
    }

    /** 1-based line of [offset] in [text]. */
    private fun lineOf(text: String, offset: Int): Int =
        text.take(offset).count { it == '\n' } + 1

    /** 1-based column of [offset] within its line. */
    private fun columnOf(text: String, offset: Int): Int =
        offset - text.lastIndexOf('\n', offset - 1) // -1 for absent '\n' yields offset + 1
}

/**
 * Replace `//` and `/* */` comments with spaces while preserving line structure and string/char
 * literal content verbatim - unlike [maskCommentsAndStrings], strings must survive here since the
 * reflective-call target name [ReflectiveCallArgExtractor] looks for IS a string literal.
 */
internal fun maskComments(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        val next = if (i + 1 < n) src[i + 1] else ' '
        when {
            c == '/' && next == '/' -> {
                while (i < n && src[i] != '\n') { out.append(' '); i++ }
            }
            c == '/' && next == '*' -> {
                out.append("  "); i += 2
                while (i < n) {
                    if (i + 1 < n && src[i] == '*' && src[i + 1] == '/') { out.append("  "); i += 2; break }
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            c == '"' -> {
                out.append(c); i++
                while (i < n && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) { out.append(src[i]); out.append(src[i + 1]); i += 2 }
                    else { out.append(src[i]); i++ }
                }
                if (i < n && src[i] == '"') { out.append(src[i]); i++ }
            }
            c == '\'' -> {
                out.append(c); i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) { out.append(src[i]); out.append(src[i + 1]); i += 2 }
                    else { out.append(src[i]); i++ }
                }
                if (i < n && src[i] == '\'') { out.append(src[i]); i++ }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}
