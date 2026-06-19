package org.jetbrains.kotlin.deprecations

/** Severity of a deprecation, mapped from the compiler diagnostic severity. */
enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

/**
 * Which pass produced a [Finding].
 *
 * [RESOLVED] — compiler-verified from a fully-resolved `.gradle.kts` (zero false
 * positives; owns the CI gate). [HEURISTIC] — a name match inside a Groovy script
 * (embedded in `.kt`/`.java`, or a standalone `.gradle`), where dynamic typing makes
 * resolution impossible. Heuristic findings are reported separately and never gate CI
 * by default. The two are never mixed.
 */
enum class FindingSource { RESOLVED, HEURISTIC }

/**
 * A usage of a deprecated API in a Gradle build script.
 *
 * For [FindingSource.RESOLVED] this comes from a compiler DEPRECATION diagnostic on the
 * fully-resolved script, so [line]/[column] are exact and a same-named symbol on a
 * non-deprecated receiver is never reported; [symbol] is the rendered signature (e.g.
 * `fun withJava(): Unit`). For [FindingSource.HEURISTIC] it is a whole-word name match;
 * [symbol] is the deprecated declaration's qualified name. [symbol] is used for grouping
 * and allowlisting; [message] is the deprecation text.
 */
data class Finding(
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,
    val level: DeprecationLevel,
    val message: String,
    val source: FindingSource = FindingSource.RESOLVED,
)
