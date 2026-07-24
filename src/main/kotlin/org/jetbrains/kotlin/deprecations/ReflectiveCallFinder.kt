package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.IOException

/**
 * Finds `.kt`/`.java` files that call a `callReflective*` helper - the reflective-dispatch
 * convention used for cross-KGP-version compatibility (e.g. `callReflectiveGetter("getCompilation", logger)`).
 */
object ReflectiveCallFinder {

    private const val MARKER = "callReflective"
    private val MARKER_REGEX = Regex(MARKER)
    private val EXTENSIONS = setOf("kt", "java")

    fun candidates(root: File, excludePatterns: List<String> = emptyList()): Sequence<File> {
        val results = runRipgrep(root)?.asSequence() ?: walkFallback(root)
        return if (excludePatterns.isEmpty()) results
        else results.filter { f -> excludePatterns.none { pat -> f.path.contains(pat) } }
    }

    // returns matched files, or null if `rg` could not be run (falls back to a walk)
    private fun runRipgrep(root: File): List<File>? = try {
        val proc = ProcessBuilder(
            "rg", "-l", "-0", "--no-messages",
            "-F", MARKER,
            "-g", "*.kt", "-g", "*.java",
            root.absolutePath,
        ).start()
        val bytes = proc.inputStream.readBytes()
        val code = proc.waitFor()
        if (code >= 2) null
        else String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotBlank() }.map(::File)
    } catch (_: IOException) {
        RipgrepDetector.reportMissing()
        null // rg not installed
    }

    private fun walkFallback(root: File): Sequence<File> =
        root.walkTopDown().filter {
            it.isFile && it.extension in EXTENSIONS &&
                runCatching { MARKER_REGEX.containsMatchIn(it.readText()) }.getOrDefault(false)
        }
}
