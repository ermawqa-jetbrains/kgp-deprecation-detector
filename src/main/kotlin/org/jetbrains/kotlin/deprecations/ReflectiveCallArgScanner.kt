package org.jetbrains.kotlin.deprecations

/**
 * Matches reflective call targets against the deprecated API index by JVM member name.
 */
class ReflectiveCallArgScanner(index: List<DeprecatedSymbol>) {

    private val byMemberName: Map<String, List<DeprecatedSymbol>> = index
        .filter { it.memberName != null && it.memberName != "<init>" && it.memberName != "<clinit>" }
        .groupBy { it.memberName!! }

    fun scan(args: List<ReflectiveCallArg>, file: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val seen = mutableSetOf<Triple<Int, Int, String>>()
        for (arg in args) {
            for (symbol in byMemberName[arg.name].orEmpty()) {
                if (!seen.add(Triple(arg.line, arg.column, symbol.qualifiedName))) continue
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
