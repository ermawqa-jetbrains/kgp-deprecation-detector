package org.jetbrains.kotlin.deprecations

/**
 * A `@Deprecated` declaration read out of a KGP jar by [KgpDeprecationExtractor].
 *
 * This is the deprecated-API *index* the Groovy heuristic pass matches against — it
 * does not represent a usage (that is [Finding]). [DeprecationLevel] is shared with the
 * resolution pass (declared in `Finding.kt`).
 */
data class DeprecatedSymbol(
    val className: String,
    val memberName: String?,
    val memberDescriptor: String?,
    val level: DeprecationLevel,
    val message: String,
    val replaceWith: String?,
) {
    val qualifiedName: String
        get() = if (memberName != null) "$className.$memberName" else className

    /** Kotlin property access name derived from a JVM getter/setter (`getFoo` -> `foo`). */
    val searchName: String
        get() {
            val name = memberName ?: return className.substringAfterLast('.')
            return when {
                name.length > 3 && name.startsWith("get") && name[3].isUpperCase() ->
                    name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
                name.length > 3 && name.startsWith("set") && name[3].isUpperCase() ->
                    name.removePrefix("set").replaceFirstChar { it.lowercaseChar() }
                else -> name
            }
        }
}
