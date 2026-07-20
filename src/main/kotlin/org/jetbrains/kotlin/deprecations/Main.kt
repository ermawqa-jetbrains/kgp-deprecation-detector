package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Scans a monorepo of Kotlin Gradle build scripts (`.gradle.kts`) for usages of
 * `@Deprecated` APIs, resolving each script the way Gradle does.
 *
 * Usage: `<monorepo-dir> [<allowlist-file>] [<gradle-installation-dir>]`
 *  - allowlist-file: one deprecated-symbol signature per line; `#` starts a comment.
 *  - gradle-installation-dir: optional; defaults to each project's own Gradle wrapper.
 *
 * Exit 1 if any ERROR-level deprecation is found,
 * Exit 2 if any unresolved symbol
 * else 0.
 */
fun main(args: Array<String>) {
    // Mirrors everything printed to stdout/stderr into a file too, so a run is a self-contained
    // CI artifact. On by default (see build.gradle.kts default path); override with
    // -PreportFile=<path>, or pass an empty value to disable. Terminal output is unaffected.
    val reportFilePath = System.getProperty("kgp.reportFile")?.takeIf { it.isNotBlank() }
    reportFilePath?.let { path ->
        val reportFile = File(path)
        reportFile.parentFile?.mkdirs()
        val fileStream = FileOutputStream(reportFile)
        System.setOut(PrintStream(TeeOutputStream(System.out, fileStream), true))
        System.setErr(PrintStream(TeeOutputStream(System.err, fileStream), true))
        Runtime.getRuntime().addShutdownHook(Thread { fileStream.flush(); fileStream.close() })
    }

    if (args.isEmpty() || args[0].isBlank()) {
        printUsage()
        return
    }
    val monorepoRoot = File(args[0]).canonicalFile
    if (!monorepoRoot.isDirectory) {
        System.err.println("Not a directory: ${monorepoRoot.path}")
        return
    }
    val allowlist = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?.let { loadAllowlist(File(it)) ?: return } ?: emptySet()
    val gradleInstallation = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(::File)

    // Test-fixture build scripts (under test/resource directories) are never real,
    // standalone-buildable projects — they exist as inputs to other tests. Exclude them by
    // default; -PexcludePatterns adds more path substrings on top.
    val defaultExcludes = listOf(
        "/testData/", "/testdata/", "/testResources/", "/testSources/", "/testSrc/",
        "/tests/", "/integration-tests/", "/agpIntegrationTestSrc/", "/resources/",
    )
    val userExcludes = System.getProperty("kgp.excludePatterns").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotBlank() }
    val excludePatterns = (defaultExcludes + userExcludes).distinct()

    // Project build scripts only: settings/init scripts have a different receiver
    // (Settings/Gradle, not Project) and are not analysed.
    val scripts = monorepoRoot.walkTopDown()
        .filter {
            it.isFile && it.name.endsWith(".gradle.kts") &&
                it.name != "settings.gradle.kts" && it.name != "init.gradle.kts" &&
                excludePatterns.none { pat -> it.path.contains(pat) }
        }
        .toList()

    val engineVersion = System.getProperty("kgp.engineVersion")?.takeIf { it.isNotBlank() }
    println("KGP deprecation check")
    println("  Scanning : ${monorepoRoot.path}")
    println("  Scripts  : ${scripts.size} .gradle.kts file(s)")
    if (engineVersion != null) println("  Engine   : Kotlin $engineVersion (analysis compiler)")
    println("  Allowlist: ${if (allowlist.isEmpty()) "(none)" else "${allowlist.size} entries"}")
    if (excludePatterns.isNotEmpty()) println("  Excluded : ${excludePatterns.joinToString(", ")}")
    if (reportFilePath != null) println("  Report   : $reportFilePath")
    println()

    if (scripts.isEmpty()) {
        println("No .gradle.kts files found (resolution pass has nothing to do).")
    }

    val analyzer = KgpDeprecationAnalyzer()

    // Each script is resolved independently (own Tooling API connection, own scripting-host
    // compile), so the whole loop is embarrassingly parallel. Bounded to protect Gradle daemon
    // memory — each Tooling API connection can spin up its own daemon (~0.5-1 GB).
    val parallelism = minOf(Runtime.getRuntime().availableProcessors(), 8).coerceAtLeast(1)
    val pool = Executors.newFixedThreadPool(parallelism)
    val done = AtomicInteger()
    val outcomes = try {
        scripts.map { script ->
            pool.submit(
                Callable {
                    resolveOne(script, monorepoRoot, gradleInstallation, analyzer).also {
                        logProgress(done.incrementAndGet(), scripts.size)
                    }
                },
            )
        }.map { it.get() }
    } finally {
        pool.shutdown()
    }
    if (scripts.isNotEmpty()) System.err.println()

    val findings = outcomes.flatMap { it.findings }.toMutableList()
    val kgpVersions = outcomes.mapNotNull { it.kgpVersion }.toSortedSet()
    val unresolved = outcomes.count { it.unresolved }
    outcomes.mapNotNull { it.warning }.forEach(System.err::println)

    println("KGP version(s) in scanned scripts: ${if (kgpVersions.isEmpty()) "(none detected)" else kgpVersions.joinToString(", ")}")
    if (engineVersion != null && kgpVersions.any { versionLessThan(engineVersion, it) }) {
        System.err.println(
            "  ! engine $engineVersion is older than a scanned KGP version — results may be incomplete; " +
                "rerun with -PkgpEngineVersion >= ${kgpVersions.last()}",
        )
    }
    println()

    // Groovy heuristic pass (separate, non-gating by default). On by default; -PscanGroovy=false
    // turns it off. Groovy is dynamically typed and cannot be resolved, so this is name-matching.
    val groovyFindings =
        if (System.getProperty("kgp.scanGroovy", "true") == "true") runGroovyPass(monorepoRoot, excludePatterns)
        else emptyList()

    val reported = (findings + groovyFindings).filterNot { it.symbol in allowlist }
    report(reported, monorepoRoot)

    val resolved = reported.filter { it.source == FindingSource.RESOLVED }
    val heuristic = reported.filter { it.source == FindingSource.HEURISTIC }
    val errors = resolved.count { it.level == DeprecationLevel.ERROR }
    val warnings = resolved.count { it.level == DeprecationLevel.WARNING }
    val hidden = resolved.count { it.level == DeprecationLevel.HIDDEN }
    val heuristicErrors = heuristic.count { it.level == DeprecationLevel.ERROR }
    if (unresolved > 0) {
        println("UNRESOLVED: $unresolved script(s) could not be modelled (see warnings above).")
    }

    // Exit policy. The resolved pass owns the gate: ERROR-level deprecation -> 1; an unanalysable
    // script is not a silent pass -> 2 (unless -PallowUnresolved). Groovy heuristic findings are
    // NON-gating by default; -PgroovyGating makes a heuristic ERROR also fail (after resolved checks).
    val allowUnresolved = System.getProperty("kgp.allowUnresolved") == "true"
    val groovyGating = System.getProperty("kgp.groovyGating") == "true"
    val groovyNote = if (heuristic.isNotEmpty()) " — ${heuristic.size} Groovy heuristic finding(s) noted" else ""
    when {
        errors > 0 -> {
            System.err.println("Result: FAIL — $errors ERROR-level deprecation(s) ($warnings WARNING, $hidden HIDDEN).$groovyNote")
            exitProcess(1)
        }
        unresolved > 0 && !allowUnresolved -> {
            System.err.println(
                "Result: FAIL — $unresolved script(s) could not be analysed; coverage is incomplete. " +
                    "Fix the script(s), or pass -PallowUnresolved to treat this as a warning.$groovyNote",
            )
            exitProcess(2)
        }
        groovyGating && heuristicErrors > 0 -> {
            System.err.println("Result: FAIL — $heuristicErrors ERROR-level Groovy heuristic match(es) (-PgroovyGating on).")
            exitProcess(1)
        }
        else -> {
            val ignored = if (unresolved > 0) " ($unresolved UNRESOLVED ignored)" else ""
            println("Result: OK$ignored — no ERROR-level deprecations ($warnings WARNING, $hidden HIDDEN noted).$groovyNote")
        }
    }
}

/** Outcome of resolving+analysing one script — carries no shared state, safe to merge later. */
private data class ScriptOutcome(
    val findings: List<Finding>,
    val kgpVersion: String?,
    val unresolved: Boolean,
    val warning: String?,
)

/**
 * Resolves and analyses a single script. Pure with respect to caller state — everything needed
 * is passed in, everything produced comes back in the returned [ScriptOutcome]. `analyzer` is
 * shared across concurrent calls: it holds no mutable instance state, building a fresh
 * compiler/host/classloader per [KgpDeprecationAnalyzer.analyze] call.
 */
private fun resolveOne(
    script: File,
    monorepoRoot: File,
    gradleInstallation: File?,
    analyzer: KgpDeprecationAnalyzer,
): ScriptOutcome {
    val projectDir = findGradleRoot(script, monorepoRoot)
    if (projectDir == null) {
        // No settings.gradle(.kts) anywhere above the script: not standalone-buildable.
        return ScriptOutcome(
            findings = emptyList(),
            kgpVersion = null,
            unresolved = true,
            warning = "  ! skipped ${script.relativeTo(monorepoRoot).path}: no Gradle settings root (not standalone-buildable)",
        )
    }
    if (gradleInstallation == null && !File(projectDir, "gradle/wrapper/gradle-wrapper.properties").exists()) {
        // Settings root exists but has no Gradle wrapper — Gradle will attempt to download a
        // distribution, which always fails or is slow. Skip without bootstrapping.
        return ScriptOutcome(
            findings = emptyList(),
            kgpVersion = null,
            unresolved = true,
            warning = "  ! skipped ${script.relativeTo(monorepoRoot).path}: no Gradle wrapper in ${projectDir.relativeTo(monorepoRoot).path}",
        )
    }
    return when (val model = GradleScriptModelProvider.fetch(projectDir, script, gradleInstallation)) {
        is ScriptModelResult.Resolved -> {
            try {
                ScriptOutcome(
                    findings = analyzer.analyze(script, model.classPath, model.implicitImports),
                    kgpVersion = model.kgpVersion,
                    unresolved = false,
                    warning = null,
                )
            } catch (e: Exception) {
                ScriptOutcome(
                    findings = emptyList(),
                    kgpVersion = model.kgpVersion,
                    unresolved = true,
                    warning = "  ! could not analyse ${script.relativeTo(monorepoRoot).path}: ${e.message?.lineSequence()?.first()}",
                )
            }
        }
        is ScriptModelResult.Failed -> ScriptOutcome(
            findings = emptyList(),
            kgpVersion = null,
            unresolved = true,
            warning = "  ! could not resolve ${script.relativeTo(monorepoRoot).path}: " +
                model.message.lineSequence().first(),
        )
    }
}

/** Thread-safe single-line progress indicator; overwrites itself via carriage return. */
private fun logProgress(done: Int, total: Int) {
    System.err.print("\r  resolving $done/$total …")
}

/**
 * Name-matches deprecated KGP APIs in Groovy scripts: standalone `.gradle` files and Groovy
 * scripts embedded as string literals in `.kt`/`.java`. The deprecated-name index is built once
 * from the KGP jars passed via `kgp.pluginJars`. Skipped (with a notice) if no jars are provided.
 */
private fun runGroovyPass(monorepoRoot: File, excludePatterns: List<String> = emptyList()): List<Finding> {
    val jars = System.getProperty("kgp.pluginJars").orEmpty()
        .split(File.pathSeparator).filter { it.isNotBlank() }
    if (jars.isEmpty()) {
        System.err.println("  ! Groovy pass skipped: no KGP jars provided (kgp.pluginJars).")
        return emptyList()
    }
    val index = jars.flatMap { runCatching { KgpDeprecationExtractor.extract(it) }.getOrDefault(emptyList()) }
    if (index.isEmpty()) {
        System.err.println("  ! Groovy pass skipped: no @Deprecated symbols found in the KGP jars.")
        return emptyList()
    }
    val scanRoot = System.getProperty("kgp.groovyScanRoot")?.takeIf { it.isNotBlank() }?.let(::File) ?: monorepoRoot
    val scanner = GroovyDeprecationScanner(index)
    // Scanning is pure text matching over a read-only, pre-built index — safe to parallelize.
    val candidates = GroovySourceFinder.candidates(scanRoot, excludePatterns).toList()
    val findings = candidates.parallelStream().map { file ->
        when (file.extension) {
            "gradle" -> runCatching { scanner.scanText(file.readText(), file.path, 1, 1) }
                .getOrDefault(emptyList())
            "kt", "java" -> EmbeddedGroovyScriptExtractor.extract(file).flatMap { s ->
                scanner.scanText(s.text, file.path, s.startLine, s.startColumn)
            }
            else -> emptyList()
        }
    }.toList().flatten()
    println("Groovy heuristic pass: ${index.size} deprecated name(s), ${candidates.size} candidate file(s) scanned.")
    return findings
}

/** Nearest ancestor (up to [stopAt]) containing a settings script; null if there is none. */
private fun findGradleRoot(script: File, stopAt: File): File? {
    var dir: File? = script.parentFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) return dir
        if (dir.canonicalFile == stopAt) break
        dir = dir.parentFile
    }
    return null
}

/** Leading numeric components of a version ("2.4.0-dev-8644" -> [2,4,0]). */
private fun numericParts(v: String): List<Int> =
    v.takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() }

private fun versionLessThan(a: String, b: String): Boolean {
    val pa = numericParts(a)
    val pb = numericParts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

private fun loadAllowlist(file: File): Set<String>? {
    if (!file.exists()) {
        System.err.println("Allowlist file not found: ${file.path}")
        return null
    }
    return file.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

private fun report(findings: List<Finding>, monorepoRoot: File) {
    val resolved = findings.filter { it.source == FindingSource.RESOLVED }
    val heuristic = findings.filter { it.source == FindingSource.HEURISTIC }

    if (resolved.isEmpty()) {
        println("No deprecated API usages found in resolved scripts.")
    } else {
        printSection("Resolved (.gradle.kts) — compiler-verified", resolved, monorepoRoot)
    }

    if (heuristic.isNotEmpty()) {
        println()
        printSection(
            "HEURISTIC — Groovy scripts (name-match only, review required; not gating)",
            heuristic, monorepoRoot,
        )
    }
}

private fun printSection(title: String, findings: List<Finding>, monorepoRoot: File) {
    val affected = findings.map { it.file }.toSet().size
    println("$title — ${findings.size} usage(s) in $affected file(s):")
    println("------------------------------------------------------------")

    for (level in listOf(DeprecationLevel.ERROR, DeprecationLevel.WARNING, DeprecationLevel.HIDDEN)) {
        val bucket = findings.filter { it.level == level }
        if (bucket.isEmpty()) continue
        bucket.groupBy { it.symbol }.forEach { (symbol, usages) ->
            println()
            println("[${level.name}] $symbol")
            println("  Reason: ${usages.first().message}")
            println("  Hits  : ${usages.size}")
            usages.forEach { f ->
                val rel = File(f.file).relativeToOrSelf(monorepoRoot).path
                println("    $rel:${f.line}:${f.column}")
                sourceLineWithCaret(f)?.forEach { println("      $it") }
            }
        }
    }
    println("------------------------------------------------------------")
}

private fun sourceLineWithCaret(finding: Finding): List<String>? {
    val line = runCatching { File(finding.file).readLines().getOrNull(finding.line - 1) }.getOrNull() ?: return null
    val caret = " ".repeat((finding.column - 1).coerceAtLeast(0)) + "^"
    return listOf(line, caret)
}

/** Writes every byte to both [a] and [b]; used to mirror console output into a report file. */
private class TeeOutputStream(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
    override fun write(byte: Int) {
        a.write(byte)
        b.write(byte)
    }

    override fun write(buf: ByteArray, off: Int, len: Int) {
        a.write(buf, off, len)
        b.write(buf, off, len)
    }

    override fun flush() {
        a.flush()
        b.flush()
    }
}

private fun printUsage() {
    System.err.println("Usage: kgp-deprecation-detector <monorepo-dir> [<allowlist-file>] [<gradle-installation-dir>]")
    System.err.println("  monorepo-dir            Root directory to scan for .gradle.kts files.")
    System.err.println("  allowlist-file          Optional. One deprecated-symbol signature per line; '#' starts a comment.")
    System.err.println("  gradle-installation-dir Optional. Defaults to each project's own Gradle wrapper.")
    System.err.println()
    System.err.println("As a Gradle task:")
    System.err.println("  ./gradlew checkKgpDeprecations [-PmonorepoDir=<path>] [-Pallowlist=<path>] [-PkgpEngineVersion=<ver>]")
    System.err.println("    Groovy heuristic pass (separate, non-gating): [-PscanGroovy=false] [-PgroovyGating] [-PgroovyScanRoot=<path>]")
    System.err.println("    Report file (mirrors terminal output): [-PreportFile=<path>]")
}
