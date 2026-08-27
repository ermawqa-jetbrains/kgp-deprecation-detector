package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Finds source files that call a 'callReflective*' helper.
 * Uses ripgrep if available, falling back to a manual walk.
 */
object ReflectiveCallFinder {

    private const val MARKER = "callReflective"
    private val MARKER_REGEX = Regex(MARKER)

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> =
        SourceFileFinder.candidates(root, MARKER, MARKER_REGEX, fixedString = true, excludePatterns = excludePatterns)
}
