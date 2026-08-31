package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Scans for files containing a marker using ripgrep or an in-process walk.
 * Both paths must produce identical results. Scans all .kt/.java files
 * under root except .git, ignoring .gitignore and hidden file status.
 */
internal object SourceFileFinder {

    private val EXTENSIONS = setOf("kt", "java")

    /**
     * Files under [root] whose text matches [marker].
     *
     * @param marker regex source, also handed to `rg`; must be a POSIX-compatible subset.
     * @param fixedString pass the marker to `rg` as a literal (`-F`) rather than a pattern.
     */
    fun candidates(
        root: File,
        marker: String,
        markerRegex: Regex,
        fixedString: Boolean,
        excludePatterns: List<String> = emptyList(),
    ): Sequence<File> {
        val results = ripgrepCandidates(root, marker, fixedString)?.asSequence() ?: walkCandidates(root, markerRegex)
        return if (excludePatterns.isEmpty()) results
        else results.filter { f -> excludePatterns.none { pat -> f.path.contains(pat) } }
    }

    /**
     * Path/name of the `rg` executable to invoke. Defaults to bare `rg` resolved via PATH, but can
     * be pinned to an explicit binary via `-Dkgp.rgPath=...` - relying on PATH alone is fragile in
     * CI, since some runners (e.g. TeamCity's Gradle step with `jdkHome` set) recompute PATH
     * internally and can silently drop custom PATH prepends.
     */
    private val rgExecutable: String
        get() = System.getProperty("kgp.rgPath")?.takeIf { it.isNotBlank() } ?: "rg"

    /** Returns matched files, or null to trigger a walk fallback. */
    internal fun ripgrepCandidates(root: File, marker: String, fixedString: Boolean): List<File>? = try {
        val proc = ProcessBuilder(
            rgExecutable, "-l", "-0", "--no-messages",
            "--no-ignore", "--hidden",
            "-g", "!.git/",
            if (fixedString) "-F" else "-e", marker,
            "-g", "*.kt", "-g", "*.java",
            root.absolutePath,
        ).redirectErrorStream(true).start()
        val bytes = proc.inputStream.readBytes()
        val code = proc.waitFor()
        if (code >= 2) null
        else String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotBlank() }.map(::File)
    } catch (_: Exception) {
        RipgrepDetector.reportMissing()
        null
    }

    internal fun walkCandidates(root: File, markerRegex: Regex): Sequence<File> =
        root.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter {
                it.isFile && it.extension in EXTENSIONS &&
                    runCatching { markerRegex.containsMatchIn(it.readText()) }.getOrDefault(false)
            }
}
