package org.jetbrains.kotlin.deprecations

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Scans a monorepo for two kinds of blind spot the compiler can't see through, both matched
 * against the same `@Deprecated` KGP name index read from the KGP jars:
 *  - **Embedded Gradle scripts** - Groovy or Kotlin-DSL scripts hardcoded as string literals
 *    inside `.kt`/`.java` (IDE-injected init/build scripts). Motivation: KT-85590
 *  - **Reflective calls** - a member name passed as a string literal to a `callReflective*`
 *    helper (cross-KGP-version compat dispatch), resolved only at runtime.
 *
 * Exit 1 if any `ERROR`- or `HIDDEN`-level match is found, 2 if the tool could not run at all
 * (bad arguments, missing allowlist, unusable KGP jars), else 0.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

/** Exit code meaning "the check ran and found blocking usages". */
internal const val EXIT_FINDINGS = 1

/**
 * Exit code meaning "the check never ran" - a setup failure. Deliberately distinct from
 * [EXIT_FINDINGS] so CI can tell a broken invocation from real violations; a silent success
 * on a mistyped path would hide exactly the signal this tool exists to restore.
 */
internal const val EXIT_SETUP_FAILURE = 2

internal fun run(args: Array<String>): Int {
    // create report file. Mirrors everything printed to stdout/stderr into `-PreportFile`
    val reportFilePath = setUpReportFileTee()

    // make sure agrs not empty
    if (args.isEmpty() || args[0].isBlank()) {
        printHelpUsage()
        return EXIT_SETUP_FAILURE
    }

    // make sure root is directory
    val scanRoot = File(args[0]).canonicalFile
    if (!scanRoot.isDirectory) {
        System.err.println("Not a directory: ${scanRoot.path}")
        return EXIT_SETUP_FAILURE
    }

    // get allowlist
    val allowlistPath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    val allowlist = if (allowlistPath == null) emptySet() else {
        loadAllowlist(File(allowlistPath)) ?: return EXIT_SETUP_FAILURE
    }

    // exclude test fixtures + known non-script false positives;
    // -PexcludePatterns adds more on top
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

    //check availability of JAR for given (or default) version
    val engineVersion = System.getProperty("kgp.engineVersion")?.takeIf { it.isNotBlank() }
    val jars = System.getProperty("kgp.pluginJars").orEmpty()
        .split(File.pathSeparator).filter { it.isNotBlank() }
    if (jars.isEmpty()) {
        System.err.println("No KGP jars provided (kgp.pluginJars) - nothing to match against.")
        return EXIT_SETUP_FAILURE
    }
    // `kgp.fullIndex` (-PfullIndex) disables the extractor's internal/utils/impl/Android package
    // filter: it trades noise for coverage when a hit is expected in one of those packages.
    val fullIndex = System.getProperty("kgp.fullIndex").toBoolean()
    // get list of deprecated APIs from JAR. A jar that fails to open is reported, never swallowed:
    // a silently partial index looks exactly like a clean run.
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

    // print key info about the current session at the beginning
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

    /** SCAN 1: Scan for embedded scripts **/
    // scan monorepo on candidate files and get list
    val scanner = EmbeddedScriptScanner(index)
    val candidates = EmbeddedScriptFinder.candidates(scanRoot, excludePatterns).toList()
    // extract embedded scripts from candidate files and return a list
    val embeddedFindings = candidates.parallelStream().map { file ->
        EmbeddedScriptExtractor.extract(file).flatMap { s ->
            scanner.scanText(s.text, file.path, s.startLine, s.startColumn)
        }
    }.toList().flatten()

    /** SCAN 2: Scan for reflective calls **/
    // scan monorepo on candidate files and get list
    val reflectiveScanner = ReflectiveCallArgScanner(index)
    val reflectiveCandidates = ReflectiveCallFinder.candidates(scanRoot, excludePatterns).toList()
    //extract reflective calls from candidate files and return a list
    val reflectiveFindings = reflectiveCandidates.parallelStream().map { file ->
        reflectiveScanner.scan(ReflectiveCallArgExtractor.extract(file), file.path)
    }.toList().flatten()

    // merge findings from both scans
    val findings = (embeddedFindings + reflectiveFindings).filterNot { it.symbol in allowlist }

    println("Scanned ${candidates.size} embedded-script candidate file(s), ${reflectiveCandidates.size} reflective-call candidate file(s).")
    println()
    report(findings)

    val errors = findings.count { it.level == DeprecationLevel.ERROR }
    val hidden = findings.count { it.level == DeprecationLevel.HIDDEN }

    // Gate: WARNING-only (or clean) passes; ERROR or HIDDEN fails the run
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
                // absolute path, not relative-to-scanRoot: keeps IDE/terminal file:line:column
                // click-to-open working regardless of the terminal's own cwd.
                println("    ${f.file}:${f.line}:${f.column}")
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
    System.err.println("    [-PexcludePatterns=/foo/,/bar/] [-PreportFile=<path>] [-PfullIndex]")
}
