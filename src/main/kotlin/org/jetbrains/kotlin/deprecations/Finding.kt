package org.jetbrains.kotlin.deprecations

/** Severity of a deprecation. */
enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

/**
 * A match of a deprecated KGP API in an embedded script or reflective call.
 */
data class Finding(
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,
    val level: DeprecationLevel,
    val message: String,
    val className: String = symbol.substringBeforeLast('.'),
    val memberName: String? = null,
) {
    /**
     * Identity of the logical deprecation. A default interface method or an inherited property is
     * declared in every sub-interface of a hierarchy, so the same call site matches dozens of
     * classes; grouping/deduplicating by this key reports it once.
     */
    val deprecationId: String get() = memberName ?: className.substringAfterLast('.')
}
