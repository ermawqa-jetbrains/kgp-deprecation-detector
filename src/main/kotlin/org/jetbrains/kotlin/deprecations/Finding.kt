package org.jetbrains.kotlin.deprecations

/** Severity of a deprecation, read from the `@Deprecated` annotation's `level`. */
enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

/**
 * A name match of a deprecated KGP API inside an embedded Gradle script (a Groovy or Kotlin-DSL
 * script literal hardcoded as a string inside `.kt`/`.java`). Since the embedded script is never
 * compiled — and Groovy can't be resolved by any frontend regardless — this is whole-word name
 * matching, not compiler resolution: [symbol] is the deprecated declaration's qualified name,
 * used for grouping and allowlisting; [message] is the deprecation text; [line]/[column] point
 * into the host `.kt`/`.java` file.
 */
data class Finding(
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,
    val level: DeprecationLevel,
    val message: String,
)
