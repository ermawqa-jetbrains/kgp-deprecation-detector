package org.jetbrains.kotlin.deprecations

import java.io.File

/** A string-literal method/field name passed as the first arg to a `callReflective*` helper */
data class ReflectiveCallArg(val name: String, val line: Int, val column: Int)

/**
 * Pulls reflective-call target names out of `.kt`/`.java` source,
 * e.g. `instance.callReflectiveGetter("getCompilation", logger)`
 */
object ReflectiveCallArgExtractor {

    private val CALL_SITE = Regex("""\bcallReflective\w*\s*\(\s*"([A-Za-z_][A-Za-z0-9_]*)"""")

    fun extract(file: File): List<ReflectiveCallArg> = extractFromText(file.readText())

    internal fun extractFromText(text: String): List<ReflectiveCallArg> {
        val results = mutableListOf<ReflectiveCallArg>()
        maskComments(text).lines().forEachIndexed { idx, line ->
            for (match in CALL_SITE.findAll(line)) {
                val nameGroup = match.groups[1]!!
                results.add(ReflectiveCallArg(nameGroup.value, idx + 1, nameGroup.range.first + 1))
            }
        }
        return results
    }
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
