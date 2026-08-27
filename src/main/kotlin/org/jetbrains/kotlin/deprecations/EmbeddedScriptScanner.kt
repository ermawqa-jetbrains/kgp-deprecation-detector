package org.jetbrains.kotlin.deprecations

/**
 * Matches deprecated symbols in embedded Gradle scripts.
 */
class EmbeddedScriptScanner(index: List<DeprecatedSymbol>) {

    // Drops short terms (< 4 chars) and ctors to reduce noise
    private val searchIndex: List<Pair<Regex, DeprecatedSymbol>> = index
        .flatMap { symbol -> setOfNotNull(symbol.searchName, symbol.memberName).map { it to symbol } }
        .filter { (term, _) -> term.isNotBlank() && term != "<init>" && term != "<clinit>" && term.length >= 4 }
        .map { (term, symbol) -> Regex("\\b${Regex.escape(term)}\\b") to symbol }

    /**
     * Scans [text] and maps hits to the host [file] position.
     */
    fun scanText(text: String, file: String, lineOffset: Int, colOffset: Int): List<Finding> {
        val masked = maskCommentsAndStrings(text).lines()
        val findings = mutableListOf<Finding>()
        val seen = mutableSetOf<Triple<Int, Int, String>>()

        masked.forEachIndexed { idx, maskedLine ->
            val contentLine = idx + 1
            val absoluteLine = lineOffset + contentLine - 1
            for ((pattern, symbol) in searchIndex) {
                for (match in pattern.findAll(maskedLine)) {
                    val matchCol = match.range.first + 1
                    // Only the first line needs colOffset; subsequent lines carry host indentation.
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
 * Replaces comments and strings with spaces to avoid false positives
 * while preserving line/column structure.
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
