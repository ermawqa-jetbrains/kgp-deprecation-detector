package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.IOException

/**
 * Finds the small set of `.kt`/`.java` files worth scanning for embedded Gradle scripts. On a
 * large monorepo a full source walk is prohibitive, so the fast path shells out to ripgrep
 * (`rg`) to pre-filter by marker. Without `rg` on the PATH it falls back to an in-process walk
 * with a cheap marker check.
 *
 * [excludePatterns] are substring patterns; any file whose absolute path contains one is skipped.
 */
object EmbeddedScriptFinder {

    private const val MARKER =
        "allprojects|afterEvaluate|tasks\\.(create|register)|" +
            "gradle\\.(ext|rootProject|settingsEvaluated|buildFinished)|pluginManager|GradleVersion|" +
            "plugins\\s*\\{|kotlin\\s*\\{|dependencies\\s*\\{|compilerOptions|kotlinOptions|withType\\("
    private val MARKER_REGEX = Regex(MARKER)
    private val EXTENSIONS = setOf("kt", "java")

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> {
        val results = runRipgrep(root)?.asSequence() ?: walkFallback(root)
        return if (excludePatterns.isEmpty()) results
        else results.filter { f -> excludePatterns.none { pat -> f.path.contains(pat) } }
    }

    /** returns matched files, or null if `rg` could not be run (falls back to a walk) */
    private fun runRipgrep(root: File): List<File>? = try {
        val proc = ProcessBuilder(
            "rg", "-l", "-0", "--no-messages",
            "-e", MARKER,
            "-g", "*.kt", "-g", "*.java",
            root.absolutePath,
        ).start()
        val bytes = proc.inputStream.readBytes()
        val code = proc.waitFor()
        // rg exit codes: 0 = matches, 1 = no matches, >=2 = error (fall back to a walk)
        if (code >= 2) null
        else String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotBlank() }.map(::File)
    } catch (e: IOException) {
        null // rg not installed
    }

    private fun walkFallback(root: File): Sequence<File> =
        root.walkTopDown().filter {
            it.isFile && it.extension in EXTENSIONS &&
                runCatching { MARKER_REGEX.containsMatchIn(it.readText()) }.getOrDefault(false)
        }
}
