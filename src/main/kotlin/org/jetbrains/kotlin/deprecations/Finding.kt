package org.jetbrains.kotlin.deprecations

/** Severity of a deprecation, mapped from the compiler diagnostic severity. */
enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

/**
 * A resolved usage of a deprecated API in a Gradle build script.
 *
 * Produced from a compiler DEPRECATION diagnostic on the fully-resolved script, so
 * [line]/[column] are exact and there is no spelling heuristic — a same-named symbol
 * on a non-deprecated receiver is never reported. [symbol] is the deprecated
 * declaration's rendered signature (e.g. `fun withJava(): Unit`), used for grouping
 * and allowlisting; [message] is the compiler's own deprecation text.
 */
data class Finding(
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,
    val level: DeprecationLevel,
    val message: String,
)
