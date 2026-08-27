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
)
