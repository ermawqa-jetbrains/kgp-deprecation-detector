package org.jetbrains.kotlin.deprecations

/**
 * A @Deprecated declaration indexed from a KGP jar.
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

    /** Kotlin property name derived from JVM getter/setter (e.g., 'getFoo' -> 'foo'). */
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
