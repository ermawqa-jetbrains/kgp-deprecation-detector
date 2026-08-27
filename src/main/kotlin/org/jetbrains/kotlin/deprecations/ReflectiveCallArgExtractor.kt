package org.jetbrains.kotlin.deprecations

import java.io.File

/** A string-literal method/field name passed as the first arg to a `callReflective*` helper */
data class ReflectiveCallArg(val name: String, val line: Int, val column: Int)

/**
 * Extracts reflective call target names (literals or local constants) from .kt/.java source.
 * Example: `instance.callReflectiveGetter("getCompilation", logger)`
 *
 * Scans the whole file at once to handle calls where the literal wraps to the next line.
 * Resolves string constants declared in the same file, but not those in other files
 * or computed at runtime.
 */
object ReflectiveCallArgExtractor {

    // Matches literals. Spans newlines. Requires the literal to be the whole
    // argument to avoid partial matches like "get" in "get" + name.
    private val CALL_SITE = Regex("""\bcallReflective\w*\s*\(\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*[,)]""")

    /** Matches identifiers resolved against same-file string constants. */
    private val CALL_SITE_IDENT =
        Regex("""\bcallReflective\w*\s*\(\s*((?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*)\s*[,)]""")

    /** `const val X = "y"`, `val X: String = "y"` (Kotlin) and `static final String X = "y"` (Java). */
    private val KOTLIN_STRING_CONST =
        Regex("""\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*"([^"\\\n]*)"""")
    private val JAVA_STRING_CONST =
        Regex("""\bString\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*"([^"\\\n]*)"""")

    fun extract(file: File): List<ReflectiveCallArg> = extractFromText(file.readText())

    internal fun extractFromText(text: String): List<ReflectiveCallArg> {
        val masked = maskComments(text)
        val constants = stringConstants(masked)
        val literals = CALL_SITE.findAll(masked).map { match ->
            val nameGroup = match.groups[1]!!
            argAt(masked, nameGroup.value, nameGroup.range.first)
        }
        val viaConstants = CALL_SITE_IDENT.findAll(masked).mapNotNull { match ->
            val identGroup = match.groups[1]!!
            val name = constants[identGroup.value.substringAfterLast('.')] ?: return@mapNotNull null
            argAt(masked, name, identGroup.range.first)
        }
        return (literals + viaConstants)
            .sortedWith(compareBy({ it.line }, { it.column }))
            .toList()
    }

    /** Resolves local string constants. Ignores ambiguous names (declared twice). */
    private fun stringConstants(masked: String): Map<String, String> {
        val found = mutableMapOf<String, String?>()
        for (regex in listOf(KOTLIN_STRING_CONST, JAVA_STRING_CONST)) {
            for (match in regex.findAll(masked)) {
                val name = match.groupValues[1]
                val value = match.groupValues[2]
                found[name] = if (found.containsKey(name) && found[name] != value) null else value
            }
        }
        return found.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun argAt(masked: String, name: String, offset: Int): ReflectiveCallArg =
        ReflectiveCallArg(name, lineOf(masked, offset), columnOf(masked, offset))

    /** 1-based line of [offset] in [text]. */
    private fun lineOf(text: String, offset: Int): Int =
        text.take(offset).count { it == '\n' } + 1

    /** 1-based column of [offset] within its line. */
    private fun columnOf(text: String, offset: Int): Int =
        offset - text.lastIndexOf('\n', offset - 1) // -1 for absent '\n' yields offset + 1
}

/**
 * Replaces comments with spaces while keeping string content intact.
 * Strings must survive as they are the targets for reflective call extraction.
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
