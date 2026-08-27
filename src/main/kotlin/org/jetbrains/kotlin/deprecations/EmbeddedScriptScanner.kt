package org.jetbrains.kotlin.deprecations

/**
 * Name-based heuristic matcher for embedded Gradle scripts (Groovy or Kotlin DSL, hardcoded as
 * string literals)
 */
class EmbeddedScriptScanner(index: List<DeprecatedSymbol>) {

    // one whole-word pattern per searchable term. Terms shorter than 4 chars and the
    // JVM ctor/clinit names are dropped to keep generic-name noise down
    private val searchIndex: List<Pair<Regex, DeprecatedSymbol>> = index
        .flatMap { symbol -> setOfNotNull(symbol.searchName, symbol.memberName).map { it to symbol } }
        .filter { (term, _) -> term.isNotBlank() && term != "<init>" && term != "<clinit>" && term.length >= 4 }
        .map { (term, symbol) -> Regex("\\b${Regex.escape(term)}\\b") to symbol }

    /**
     * Scan one embedded script [text]. [lineOffset]/[colOffset] are the 1-based position of the
     * text's first character in [file] - an embedded script passes the host literal's start
     * position so reported locations point into the `.kt`/`.java`.
     */
    fun scanText(text: String, file: String, lineOffset: Int, colOffset: Int): List<Finding> {
        val masked = maskCommentsAndStrings(text).lines()
        val findings = mutableListOf<Finding>()
        // (absoluteLine, absoluteColumn, qualifiedName): every distinct occurrence is reported,
        // so a line using a deprecated API twice yields two hits with two accurate carets. The
        // key still collapses index entries that resolve to the same qualified name.
        val seen = mutableSetOf<Triple<Int, Int, String>>()

        masked.forEachIndexed { idx, maskedLine ->
            val contentLine = idx + 1 // 1-based within text
            val absoluteLine = lineOffset + contentLine - 1
            for ((pattern, symbol) in searchIndex) {
                for (match in pattern.findAll(maskedLine)) {
                    val matchCol = match.range.first + 1 // 1-based within content line
                    // Only line 1 needs the literal's start column: from line 2 on, the raw
                    // triple-quoted content carries the host file's own indentation, so the
                    // in-content column already is the host column.
                    val absoluteCol = if (contentLine == 1) colOffset + matchCol - 1 else matchCol
                    if (!seen.add(Triple(absoluteLine, absoluteCol, symbol.qualifiedName))) continue
                    findings += Finding(
                        file = file,
                        line = absoluteLine,
                        column = absoluteCol,
                        symbol = symbol.qualifiedName,
                        level = symbol.level,
                        message = symbol.message,
                    )
                }
            }
        }
        return findings
    }
}

/**
 * Replace comments and string/char literals with spaces while preserving line structure
 * (newlines kept; line/column offsets unchanged). Handles Kotlin and Groovy syntax: `//`
 * line comments, `/* */` block comments, `"…"`, `"""…"""`, `'…'`, `'''…'''`.
 * Limitation: string-template `${…}` contents are masked along with the surrounding string; nested
 * block comments are not supported.
 */
internal fun maskCommentsAndStrings(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        val next = if (i + 1 < n) src[i + 1] else ' '

        when {
            c == '/' && next == '/' -> {
                while (i < n && src[i] != '\n') {
                    out.append(' ')
                    i++
                }
            }
            c == '/' && next == '*' -> {
                out.append("  "); i += 2
                while (i < n) {
                    if (i + 1 < n && src[i] == '*' && src[i + 1] == '/') {
                        out.append("  "); i += 2; break
                    }
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                out.append("   "); i += 3
                while (i < n) {
                    if (i + 2 < n && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                        out.append("   "); i += 3; break
                    }
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            c == '"' -> {
                out.append(' '); i++
                while (i < n && src[i] != '"' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        out.append("  "); i += 2
                    } else {
                        out.append(' '); i++
                    }
                }
                if (i < n && src[i] == '"') { out.append(' '); i++ }
            }
            c == '\'' && i + 2 < n && src[i + 1] == '\'' && src[i + 2] == '\'' -> {
                out.append("   "); i += 3
                while (i < n) {
                    if (i + 2 < n && src[i] == '\'' && src[i + 1] == '\'' && src[i + 2] == '\'') {
                        out.append("   "); i += 3; break
                    }
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            c == '\'' -> {
                out.append(' '); i++
                while (i < n && src[i] != '\'' && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < n) {
                        out.append("  "); i += 2
                    } else {
                        out.append(' '); i++
                    }
                }
                if (i < n && src[i] == '\'') { out.append(' '); i++ }
            }
            else -> {
                out.append(c); i++
            }
        }
    }
    return out.toString()
}
