package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Scans for KGP API usages the compiler misses:
 *  - Embedded Gradle scripts (Groovy/Kotlin-DSL in strings)
 *  - Reflective calls (member name passed as string)
 *
 * Exit codes: 0 (clean), 1 (findings), 2 (setup failure).
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

internal const val EXIT_FINDINGS = 1

/**
 * Setup failure. Distinct from [EXIT_FINDINGS] so CI can tell
 * a broken run from real violations.
 */
internal const val EXIT_SETUP_FAILURE = 2

internal fun run(args: Array<String>): Int {
    // Mirror output to file if -PreportFile is set
    val reportFilePath = setUpReportFileTee()

    if (args.isEmpty() || args[0].isBlank()) {
        printHelpUsage()
        return EXIT_SETUP_FAILURE
    }

    val scanRoot = File(args[0]).canonicalFile
    if (!scanRoot.isDirectory) {
        System.err.println("Not a directory: ${scanRoot.path}")
        return EXIT_SETUP_FAILURE
    }

    val allowlistPath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    val allowlistFile = allowlistPath?.let(::File)
    val allowlist = if (allowlistFile == null) emptySet() else {
        loadAllowlist(allowlistFile) ?: return EXIT_SETUP_FAILURE
    }

    // Default excludes + user patterns
    val defaultExcludes = listOf(
        "/testData/", "/testdata/", "/testResources/", "/testSources/", "/testSrc/",
        "/test/", "/tests/", "/integration-tests/", "/agpIntegrationTestSrc/", "/resources/",
        // Notebook prompt (not a script)
        "/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt",
        // Documentation snippet (not a script)
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
        return EXIT_SETUP_FAILURE
    }
    // -PfullIndex includes internal/Android packages
    val fullIndex = System.getProperty("kgp.fullIndex").toBoolean()
    // Build index. Errors are reported to avoid silent partial runs.
    val jarIndexes = jars.map { jar ->
        runCatching { KgpDeprecationExtractor.extractIndex(jar, fullIndex) }
            .onFailure { System.err.println("Failed to read KGP jar '$jar': $it") }
            .getOrDefault(KgpDeprecationExtractor.JarIndex(emptyList(), 0))
    }
    val index = jarIndexes.flatMap { it.symbols }
    val skippedClasses = jarIndexes.sumOf { it.skippedClasses }
    if (index.isEmpty()) {
        System.err.println("No @Deprecated symbols found in the KGP jars.")
        return EXIT_SETUP_FAILURE
    }

    println("KGP DEPRECATION CHECK")
    println("  Scanning : ${scanRoot.path}")
    println("  KGP      : ${engineVersion ?: "(version unknown)"} (${index.size} deprecated symbol(s) indexed)")
    if (skippedClasses > 0) {
        println("  Index    : $skippedClasses class(es) skipped (internal/utils/impl/Android) - pass -PfullIndex to include them")
    }
    println("  Allowlist: ${if (allowlist.isEmpty()) "(none)" else "${allowlist.size} entries"}")
    if (excludePatterns.isNotEmpty()) println("  Excluded : ${excludePatterns.joinToString(", ")}")
    if (reportFilePath != null) println("  Report   : $reportFilePath")
    println()

    /** SCAN 1: Embedded scripts */
    val scanner = EmbeddedScriptScanner(index)
    val candidates = EmbeddedScriptFinder.candidates(scanRoot, excludePatterns).toList()
    val embeddedFindings = candidates.parallelStream().map { file ->
        EmbeddedScriptExtractor.extract(file).flatMap { s ->
            scanner.scanText(s.text, file.path, s.startLine, s.startColumn)
        }
    }.toList().flatten()

    /** SCAN 2: Reflective calls */
    val reflectiveScanner = ReflectiveCallArgScanner(index)
    val reflectiveCandidates = ReflectiveCallFinder.candidates(scanRoot, excludePatterns).toList()
    val reflectiveFindings = reflectiveCandidates.parallelStream().map { file ->
        reflectiveScanner.scan(ReflectiveCallArgExtractor.extract(file), file.path)
    }.toList().flatten()

    val findings = (embeddedFindings + reflectiveFindings).filterNot { it.symbol in allowlist }

    println("Scanned ${candidates.size} embedded-script candidate file(s), ${reflectiveCandidates.size} reflective-call candidate file(s).")
    println()
    report(findings)

    val errors = findings.count { it.level == DeprecationLevel.ERROR }
    val hidden = findings.count { it.level == DeprecationLevel.HIDDEN }

    // WARNING passes; ERROR/HIDDEN fails
    return if (errors > 0 || hidden > 0) {
        System.err.println("Result: FAIL")
        EXIT_FINDINGS
    } else {
        println("Result: OK")
        0
    }
}

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

private fun report(findings: List<Finding>) {
    if (findings.isEmpty()) {
        println("No deprecated API usages found in embedded scripts or reflective calls.")
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
                // Absolute path for IDE click-to-open
                println("    ${f.file}:${f.line}:${f.column}")
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

/** Mirrors output to two streams. */
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
    System.err.println("    [-PexcludePatterns=/foo/,/bar/] [-PreportFile=<path>] [-PfullIndex]")
}
