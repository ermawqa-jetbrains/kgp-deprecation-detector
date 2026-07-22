package org.jetbrains.kotlin.deprecations

/**
 * Matches reflective-call target names (from [ReflectiveCallArgExtractor]) against the
 * deprecated-API index by exact JVM member name. The name is already an isolated identifier —
 * exactly the raw `memberName` [KgpDeprecationExtractor] recorded — so an exact-match lookup is
 * enough; no whole-word regex or comment/string masking is needed the way [EmbeddedScriptScanner]
 * needs it for a block of embedded-script text.
 */
class ReflectiveCallArgScanner(index: List<DeprecatedSymbol>) {

    private val byMemberName: Map<String, List<DeprecatedSymbol>> = index
        .filter { it.memberName != null && it.memberName != "<init>" && it.memberName != "<clinit>" }
        .groupBy { it.memberName!! }

    fun scan(args: List<ReflectiveCallArg>, file: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val seen = mutableSetOf<Pair<Int, String>>() // (line, qualifiedName)
        for (arg in args) {
            for (symbol in byMemberName[arg.name].orEmpty()) {
                if (!seen.add(arg.line to symbol.qualifiedName)) continue
                findings += Finding(
                    file = file,
                    line = arg.line,
                    column = arg.column,
                    symbol = symbol.qualifiedName,
                    level = symbol.level,
                    message = symbol.message,
                )
            }
        }
        return findings
    }
}
