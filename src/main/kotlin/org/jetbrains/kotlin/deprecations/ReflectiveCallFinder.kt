package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Finds `.kt`/`.java` files that call a `callReflective*` helper - the reflective-dispatch
 * convention used for cross-KGP-version compatibility (e.g. `callReflectiveGetter("getCompilation", logger)`).
 * The `rg` fast path and the walk fallback both live in [SourceFileFinder]; this object only
 * declares the marker.
 */
object ReflectiveCallFinder {

    private const val MARKER = "callReflective"
    private val MARKER_REGEX = Regex(MARKER)

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> =
        SourceFileFinder.candidates(root, MARKER, MARKER_REGEX, fixedString = true, excludePatterns = excludePatterns)
}
