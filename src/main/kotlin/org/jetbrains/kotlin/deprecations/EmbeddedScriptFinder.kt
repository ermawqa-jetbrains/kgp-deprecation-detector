package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Finds the small set of `.kt`/`.java` files worth scanning for embedded Gradle scripts. On a
 * large monorepo a full source walk is prohibitive, so the fast path shells out to ripgrep
 * (`rg`) to pre-filter by marker. Without `rg` on the PATH it falls back to an in-process walk
 * with a cheap marker check. Both paths live in [SourceFileFinder] so they cannot drift apart;
 * this object only declares the marker.
 */
object EmbeddedScriptFinder {

    /**
     * The single source of truth for what "looks like a Gradle script" (Groovy or Kotlin-DSL).
     * Shared, as text, with [EmbeddedScriptExtractor.SCRIPT_MARKER] - keeping only one copy
     * means the finder can never end up narrower than the extractor and silently lose
     * candidates. Pinned by `EmbeddedScriptMarkerTest`.
     */
    internal const val MARKER =
        "allprojects|afterEvaluate|tasks\\.(create|register)|" +
            "gradle\\.(ext|rootProject|settingsEvaluated|buildFinished)|pluginManager|GradleVersion|" +
            "plugins\\s*\\{|kotlin\\s*\\{|dependencies\\s*\\{|compilerOptions|kotlinOptions|withType\\("
    private val MARKER_REGEX = Regex(MARKER)

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> =
        SourceFileFinder.candidates(root, MARKER, MARKER_REGEX, fixedString = false, excludePatterns = excludePatterns)
}
