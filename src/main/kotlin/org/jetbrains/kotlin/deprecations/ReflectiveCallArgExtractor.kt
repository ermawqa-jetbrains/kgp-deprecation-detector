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
 * A target passed as a constant declared **in the same file** is resolved too (`private const val
 * GETTER = "getX"` / `callReflectiveGetter(GETTER, logger)`), by collecting the file's own
 * string-valued `val`/`static final String` declarations into a local map. The reported position
 * stays on the call site, not on the declaration, so the caret lands where the reader must act.
 *
 * Known limitations: a constant declared in **another** file is not resolved (matching on the
 * simple name across files would produce wrong values for same-named constants), and a name built
 * by concatenation or interpolation is undecidable statically - both would need the very
 * compilation this pass exists to work without.
 */
object ReflectiveCallArgExtractor {

    // `\s*` spans newlines, so the literal may sit on a later line than the call itself. The
    // trailing `[,)]` requires the literal to BE the whole argument: in `callReflectiveGetter(
    // "get" + name, …)` the name is computed, and reporting the fragment `get` would be a wrong
    // hit rather than a missing one.
    private val CALL_SITE = Regex("""\bcallReflective\w*\s*\(\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*[,)]""")

    /**
     * The same call shape, but with an identifier (optionally qualified, e.g. `Companion.GETTER`)
     * instead of a literal. Only resolved against [stringConstants] of the same file; an unknown
     * name yields nothing, so the pass fails closed rather than guessing.
     */
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

    /**
     * String constants declared in this file, keyed by simple name. A name declared twice with
     * different values is dropped: picking either one would be a guess.
     */
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
