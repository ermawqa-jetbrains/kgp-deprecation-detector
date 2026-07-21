package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Scans a monorepo for **embedded Gradle scripts** - Groovy or Kotlin-DSL scripts hardcoded as
 * string literals inside `.kt`/`.java` (IDE-injected init/build scripts) - for usages of
 * `@Deprecated` KGP APIs. Motivation: KT-85590
 *
 * Real `.gradle.kts` files are already resolved in-editor by IntelliJ, so their deprecation
 * warnings are already visible. Embedded scripts are not: they are strings, never compiled, and
 * Groovy is dynamically typed so no frontend could resolve them anyway. This tool restores that
 * missing signal by name-matching against the deprecated-API index read from the KGP jars.
 *
 * Usage: `<scan-root> [<allowlist-file>]`
 *  - allowlist-file: one deprecated-symbol qualified name per line; `#` starts a comment.
 *
 * Exit 1 if any `ERROR`- or `HIDDEN`-level match is found, else 0.
 */
fun main(args: Array<String>) {
    // create report file
    val reportFilePath = setUpReportFileTee()

    if (args.isEmpty() || args[0].isBlank()) {
        printHelpUsage()
        return
    }

    val scanRoot = File(args[0]).canonicalFile
    if (!scanRoot.isDirectory) {
        System.err.println("Not a directory: ${scanRoot.path}")
        return
    }

    val allowlist = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        ?.let { loadAllowlist(File(it)) ?: return } ?: emptySet()

    // exclude test fixtures + known non-script false positives; -PexcludePatterns adds more on top
    val defaultExcludes = listOf(
        "/testData/", "/testdata/", "/testResources/", "/testSources/", "/testSrc/",
        "/test/", "/tests/", "/integration-tests/", "/agpIntegrationTestSrc/", "/resources/",
        // LLM system-prompt content, not a Gradle script (matches markers by coincidence):
        "/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt",
        // A `plugins { }` snippet inside an error-message string (documentation example, not
        // a real script; untagged so the @Language filter can't catch it):
        "/fleet/buildtool/bundles/helpers.kt",
    )
    val userExcludes = System.getProperty("kgp.excludePatterns").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotBlank() }
    val excludePatterns = (defaultExcludes + userExcludes).distinct()

    val engineVersion = System.getProperty("kgp.engineVersion")?.takeIf { it.isNotBlank() }
    val jars = System.getProperty("kgp.pluginJars").orEmpty()
        .split(File.pathSeparator).filter { it.isNotBlank() }
    if (jars.isEmpty()) {
        System.err.println("No KGP jars provided (kgp.pluginJars) - nothing to match against.")
        return
    }
    val index = jars.flatMap { runCatching { KgpDeprecationExtractor.extract(it) }.getOrDefault(emptyList()) }
    if (index.isEmpty()) {
        System.err.println("No @Deprecated symbols found in the KGP jars.")
        return
    }

    // print key info about current detection
    println("KGP deprecation check (embedded scripts)")
    println("  Scanning : ${scanRoot.path}")
    if (engineVersion != null) println("  KGP      : $engineVersion (${index.size} deprecated symbol(s) indexed)")
    println("  Allowlist: ${if (allowlist.isEmpty()) "(none)" else "${allowlist.size} entries"}")
    if (excludePatterns.isNotEmpty()) println("  Excluded : ${excludePatterns.joinToString(", ")}")
    if (reportFilePath != null) println("  Report   : $reportFilePath")
    println()

    val scanner = EmbeddedScriptScanner(index)
    val candidates = EmbeddedScriptFinder.candidates(scanRoot, excludePatterns).toList()
    val findings = candidates.parallelStream().map { file ->
        EmbeddedScriptExtractor.extract(file).flatMap { s ->
            scanner.scanText(s.text, file.path, s.startLine, s.startColumn)
        }
    }.toList().flatten().filterNot { it.symbol in allowlist }

    println("Scanned ${candidates.size} candidate file(s).")
    println()
    report(findings, scanRoot)

    val errors = findings.count { it.level == DeprecationLevel.ERROR }
    val hidden = findings.count { it.level == DeprecationLevel.HIDDEN }

    // Gate: ERROR or HIDDEN fails the run; WARNING-only (or clean) passes.
    if (errors > 0 || hidden > 0) {
        System.err.println("Result: FAIL")
        exitProcess(1)
    } else {
        println("Result: OK")
    }
}

/**
 * Mirrors everything printed to stdout/stderr into `-PreportFile` (enabled by default, see
 * build.gradle.kts default path; pass an empty value to disable). Returns the report path, or
 * null if disabled.
 */
private fun setUpReportFileTee(): String? {
    val reportFilePath = System.getProperty("kgp.reportFile")?.takeIf { it.isNotBlank() }
    reportFilePath?.let { path ->
        val reportFile = File(path)
        reportFile.parentFile?.mkdirs()
        val fileStream = FileOutputStream(reportFile)
        System.setOut(PrintStream(TeeOutputStream(System.out, fileStream), true))
        System.setErr(PrintStream(TeeOutputStream(System.err, fileStream), true))
        Runtime.getRuntime().addShutdownHook(Thread { fileStream.flush(); fileStream.close() })
    }
    return reportFilePath
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

private fun report(findings: List<Finding>, scanRoot: File) {
    if (findings.isEmpty()) {
        println("No deprecated API usages found in embedded scripts.")
        return
    }
    val affected = findings.map { it.file }.toSet().size
    val breakdown = listOf(DeprecationLevel.ERROR, DeprecationLevel.HIDDEN, DeprecationLevel.WARNING)
        .associateWith { level -> findings.count { it.level == level } }
        .filterValues { it > 0 }
        .entries.joinToString(", ") { (level, count) -> "$count ${level.name}" }
    println("${findings.size} usage(s) in $affected file(s): $breakdown match(es).")
    println("------------------------------------------------------------")

    for (level in listOf(DeprecationLevel.ERROR, DeprecationLevel.HIDDEN, DeprecationLevel.WARNING)) {
        val bucket = findings.filter { it.level == level }
        if (bucket.isEmpty()) continue
        bucket.groupBy { it.symbol }.forEach { (symbol, usages) ->
            println()
            println("[${level.name}] $symbol")
            println("  Reason: ${usages.first().message}")
            println("  Hits  : ${usages.size}")
            usages.forEach { f ->
                val rel = File(f.file).relativeToOrSelf(scanRoot).path
                println("    $rel:${f.line}:${f.column}")
                sourceLineWithCaret(f)?.forEach { println("      $it") }
            }
        }
    }
    println("------------------------------------------------------------")
}

// arrow under deprecated API
private fun sourceLineWithCaret(finding: Finding): List<String>? {
    val line = runCatching { File(finding.file).readLines().getOrNull(finding.line - 1) }.getOrNull() ?: return null
    val caret = " ".repeat((finding.column - 1).coerceAtLeast(0)) + "^"
    return listOf(line, caret)
}

/** Writes every byte to both [a] and [b]; used to mirror console output into a report file */
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

private fun printHelpUsage() {
    System.err.println("Usage: kgp-deprecation-detector <scan-root> [<allowlist-file>]")
    System.err.println("  scan-root       Root directory to scan for embedded Gradle scripts in .kt/.java.")
    System.err.println("  allowlist-file  Optional. One deprecated-symbol qualified name per line; '#' starts a comment.")
    System.err.println()
    System.err.println("As a Gradle task:")
    System.err.println("  ./gradlew checkKgpDeprecations [-PmonorepoDir=<path>] [-Pallowlist=<path>] [-PkgpEngineVersion=<ver>]")
    System.err.println("    [-PexcludePatterns=/foo/,/bar/] [-PreportFile=<path>]")
}
