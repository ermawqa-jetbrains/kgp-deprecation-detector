package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Shared candidate-file search used by both passes: a ripgrep fast path with an in-process walk
 * fallback, over `.kt`/`.java` files matching a per-pass marker.
 *
 * **The two paths must agree.** Whether `rg` happens to be installed may change how long a scan
 * takes, never which files it reports - otherwise CI and local runs are not comparable. The
 * pinned semantic is "scan everything under the root except `.git`":
 * - `--no-ignore` - a monorepo's `.gitignore` can hide generated-but-shipped sources; path
 *   filtering is `excludePatterns`' job, not the VCS's.
 * - `--hidden` - the walk fallback has no notion of hidden files, so `rg` must not either.
 * - `-g !.git/` / the `.git` skip below - `--hidden --no-ignore` would otherwise drag `rg`
 *   through the object store, which the walk never enters either.
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

    /** returns matched files, or null if `rg` could not be run (falls back to a walk) */
    internal fun ripgrepCandidates(root: File, marker: String, fixedString: Boolean): List<File>? = try {
        val proc = ProcessBuilder(
            "rg", "-l", "-0", "--no-messages",
            "--no-ignore", "--hidden",
            "-g", "!.git/",
            if (fixedString) "-F" else "-e", marker,
            "-g", "*.kt", "-g", "*.java",
            root.absolutePath,
        ).redirectErrorStream(true).start()
        val bytes = proc.inputStream.readBytes()
        val code = proc.waitFor()
        // rg exit codes: 0 = matches, 1 = no matches, >=2 = error (fall back to a walk)
        if (code >= 2) null
        else String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotBlank() }.map(::File)
    } catch (_: Exception) {
        // IOException when `rg` is absent, but a SecurityException or an interrupted `waitFor`
        // must degrade to the walk too rather than killing the whole run.
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
