package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Finds source files that might contain embedded Gradle scripts.
 * Uses ripgrep if available, falling back to a manual walk.
 */
object EmbeddedScriptFinder {

    /**
     * Marker for Gradle scripts. Shared with EmbeddedScriptExtractor
     * to ensure consistent filtering.
     */
    internal const val MARKER =
        "allprojects|afterEvaluate|tasks\\.(create|register)|" +
            "gradle\\.(ext|rootProject|settingsEvaluated|buildFinished)|pluginManager|GradleVersion|" +
            "plugins\\s*\\{|kotlin\\s*\\{|dependencies\\s*\\{|compilerOptions|kotlinOptions|withType\\("
    private val MARKER_REGEX = Regex(MARKER)

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> =
        SourceFileFinder.candidates(root, MARKER, MARKER_REGEX, fixedString = false, excludePatterns = excludePatterns)
}
