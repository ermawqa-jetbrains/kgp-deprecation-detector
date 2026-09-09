package org.jetbrains.kotlin.deprecations

import java.io.File
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
    val config = DetectorConfig.parse(args) ?: return EXIT_SETUP_FAILURE
    val indexData = buildIndex(config) ?: return EXIT_SETUP_FAILURE
    val scanResult = executeScan(config, indexData)
    publishReport(config, scanResult)
    return scanResult.exitCode
}

/**
 * Extracts deprecated symbols from the provided KGP jars using ASM bytecode inspection.
 * Returns null if jar extraction fails or if no symbols are found.
 */
internal fun buildIndex(config: DetectorConfig): IndexData? {
    // Build index. Errors are reported to avoid silent partial runs.
    val jarIndexes = config.pluginJars.map { jar ->
        runCatching { KgpDeprecationExtractor.extractIndex(jar, config.fullIndex) }
            .onFailure { System.err.println("Failed to read KGP jar '$jar': $it") }
            .getOrDefault(KgpDeprecationExtractor.JarIndex(emptyList(), 0))
    }
    val index = jarIndexes.flatMap { it.symbols }
    val skippedClasses = jarIndexes.sumOf { it.skippedClasses }
    val outOfScopeClasses = jarIndexes.sumOf { it.outOfScopeClasses }
    val syntheticClasses = jarIndexes.sumOf { it.syntheticClasses }
    if (index.isEmpty()) {
        System.err.println("No @Deprecated symbols found in the KGP jars.")
        return null
    }
    return IndexData(
        symbols = index,
        skippedClasses = skippedClasses,
        outOfScopeClasses = outOfScopeClasses,
        syntheticClasses = syntheticClasses,
    )
}

/**
 * Executes parallel scanning of embedded scripts and reflective calls, resolves allowlist suppressions,
 * and performs target symbol checks.
 */
internal fun executeScan(config: DetectorConfig, indexData: IndexData): ScanResult {
    val banner = indexData.buildBanner(config)

    /** SCAN 1: Embedded scripts */
    val scanner = EmbeddedScriptScanner(indexData.symbols)
    val candidates = EmbeddedScriptFinder.candidates(config.scanRoot, config.excludePatterns).toList()
    val embeddedFindings = candidates.parallelStream().map { file ->
        EmbeddedScriptExtractor.extract(file).flatMap { s ->
            scanner.scanText(s.text, file.path, s.startLine, s.startColumn)
        }
    }.toList().flatten()

    /** SCAN 2: Reflective calls */
    val reflectiveScanner = ReflectiveCallArgScanner(indexData.symbols)
    val reflectiveCandidates = ReflectiveCallFinder.candidates(config.scanRoot, config.excludePatterns).toList()
    val reflectiveFindings = reflectiveCandidates.parallelStream().map { file ->
        reflectiveScanner.scan(ReflectiveCallArgExtractor.extract(file), file.path)
    }.toList().flatten()

    val allFindings = embeddedFindings + reflectiveFindings
    // Allowlisting one declaring class of a hierarchy (e.g. only 'KotlinCompile.kotlinOptions')
    // suppresses the whole logical deprecation, not just that one sibling class
    val allowlistedGroups = allFindings
        .filter { it.symbol in config.allowlist }
        .map { it.deprecationId to it.message }
        .toSet()
    val filteredFindings = allFindings.filterNot { (it.deprecationId to it.message) in allowlistedGroups }
    // One call site of an inherited/default member matches every class of the hierarchy that
    // declares it; counts and the exit gate must see it as a single usage.
    val usages = filteredFindings.distinctBy { listOf(it.file, it.line, it.column, it.deprecationId, it.message) }

    // Printed whenever target symbols were requested, independently of whether there are any
    // (unrelated) findings elsewhere
    val targetCheck = targetSymbolCheck(config.targetSymbols, indexData.symbols, allFindings, allowlistedGroups)

    return ScanResult(
        index = indexData.symbols,
        embeddedCandidateCount = candidates.size,
        reflectiveCandidateCount = reflectiveCandidates.size,
        allFindings = allFindings,
        allowlistedGroups = allowlistedGroups,
        filteredFindings = filteredFindings,
        usages = usages,
        banner = banner,
        targetCheck = targetCheck,
    )
}

/**
 * Outputs the scan results to console/report file and emits TeamCity service messages.
 */
internal fun publishReport(config: DetectorConfig, result: ScanResult) {
    print(result.banner)
    println()
    println(result.scannedLine)
    println()

    if (config.reportFilePath != null) {
        println("Generating report...")
        writeReportFile(config.reportFilePath, result.buildReportContent())
        println(result.summaryLine)
        println("Report is ready, check in: ${config.reportFilePath}")
    } else {
        if (result.targetCheck.isNotEmpty()) {
            print(result.targetCheck)
            println()
        }
        if (result.usages.isEmpty()) {
            println(result.summaryLine)
        } else {
            print(executiveSummary(result.usages))
            println()
            print(quickIndex(result.filteredFindings))
            println()
            print(reportDetails(result.filteredFindings))
        }
    }

    if (isRunningOnTeamCity()) {
        println("##teamcity[buildStatus text='${escapeTc(result.summaryLine)}']")
        if (result.hasFailures) {
            val reportFileName = config.reportFilePath?.let { File(it).name }
            val problemDescription = if (reportFileName != null) {
                "KGP deprecation check FAILED: ${result.summaryLine} Check artifact '$reportFileName'."
            } else {
                "KGP deprecation check FAILED: ${result.summaryLine}"
            }
            println("##teamcity[buildProblem description='${escapeTc(problemDescription)}' identity='kgpDeprecations']")
        }
    }

    // WARNING passes; ERROR/HIDDEN fails
    if (result.hasFailures) {
        System.err.println("Result: FAIL")
    } else {
        println("Result: OK")
    }
}

internal fun isRunningOnTeamCity(): Boolean =
    !System.getenv("TEAMCITY_VERSION").isNullOrBlank() ||
        !System.getProperty("teamcity.version").isNullOrBlank()

internal fun escapeTc(text: String): String = text.replace(Regex("""[|'\[\]\n\r]""")) {
    when (it.value) {
        "\n" -> "|n"
        "\r" -> "|r"
        else -> "|${it.value}"
    }
}

private fun writeReportFile(path: String, content: String) {
    val reportFile = File(path)
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(content)
}

internal fun reportSummaryLine(findings: List<Finding>): String {
    if (findings.isEmpty()) {
        return "No deprecated API usages found in embedded scripts or reflective calls."
    }
    val affected = findings.map { it.file }.toSet().size
    val breakdown = listOf(DeprecationLevel.ERROR, DeprecationLevel.HIDDEN, DeprecationLevel.WARNING)
        .associateWith { level -> findings.count { it.level == level } }
        .filterValues { it > 0 }
        .entries.joinToString(", ") { (level, count) -> "$count ${level.name}" }
    return "${findings.size} usage(s) in $affected file(s): $breakdown match(es)."
}

internal fun executiveSummary(usages: List<Finding>): String = buildString {
    appendLine("============================================================")
    appendLine("EXECUTIVE SUMMARY")
    appendLine("============================================================")
    val affected = usages.map { it.file }.toSet().size
    appendLine("Total Usages: ${usages.size} across $affected file(s)")
    val errors = usages.count { it.level == DeprecationLevel.ERROR }
    val hidden = usages.count { it.level == DeprecationLevel.HIDDEN }
    val warnings = usages.count { it.level == DeprecationLevel.WARNING }
    if (errors > 0) appendLine("  - ERROR  : $errors (fails build / action required)")
    if (hidden > 0) appendLine("  - HIDDEN : $hidden (removed API / action required)")
    if (warnings > 0) appendLine("  - WARNING: $warnings (advisory / future removal)")
}

internal data class QuickIndexEntry(
    val level: DeprecationLevel,
    val symbol: String,
    val usages: Int,
    val declaredIn: String,
)

internal fun quickIndex(findings: List<Finding>): String = buildString {
    val entries = mutableListOf<QuickIndexEntry>()
    for (level in listOf(DeprecationLevel.ERROR, DeprecationLevel.HIDDEN, DeprecationLevel.WARNING)) {
        val bucket = findings.filter { it.level == level }
        if (bucket.isEmpty()) continue
        bucket.groupBy { it.deprecationId to it.message }
            .map { (key, group) ->
                val (deprecationId, _) = key
                val classes = group.map { it.className }.distinct().sorted()
                val hits = group.distinctBy { listOf(it.file, it.line, it.column) }.size
                val declaredIn = classes.first() + if (classes.size > 1) " (+${classes.size - 1} more)" else ""
                QuickIndexEntry(level, deprecationId, hits, declaredIn)
            }
            .sortedWith(compareByDescending<QuickIndexEntry> { it.usages }.thenBy { it.symbol })
            .forEach { entries.add(it) }
    }

    if (entries.isEmpty()) return@buildString

    val colSeverity = "SEVERITY"
    val colSymbol = "API SYMBOL"
    val colUsages = "USAGES"
    val colDeclaredIn = "DECLARED IN"

    val maxSeverityWidth = (entries.map { it.level.name } + colSeverity).maxOf { it.length }
    val maxSymbolWidth = (entries.map { it.symbol } + colSymbol).maxOf { it.length }
    val maxUsagesWidth = (entries.map { it.usages.toString() } + colUsages).maxOf { it.length }

    appendLine("============================================================")
    appendLine("QUICK INDEX")
    appendLine("============================================================")
    appendLine(
        "${colSeverity.padEnd(maxSeverityWidth)} | ${colSymbol.padEnd(maxSymbolWidth)} | " +
            "${colUsages.padEnd(maxUsagesWidth)} | $colDeclaredIn"
    )
    appendLine(
        "${"-".repeat(maxSeverityWidth)}-+-" +
            "${"-".repeat(maxSymbolWidth)}-+-" +
            "${"-".repeat(maxUsagesWidth)}-+-" +
            "-".repeat(30)
    )
    for (entry in entries) {
        appendLine(
            "${entry.level.name.padEnd(maxSeverityWidth)} | " +
                "${entry.symbol.padEnd(maxSymbolWidth)} | " +
                "${entry.usages.toString().padEnd(maxUsagesWidth)} | " +
                entry.declaredIn
        )
    }
}

/**
 * One section per logical deprecation. Grouping by member and message instead of by declaring class
 * collapses the sub-interface hierarchy (`KotlinCompile`, `KotlinJvmCompile`, ...), which used to
 * repeat the very same call site 20+ times.
 */
internal fun reportDetails(findings: List<Finding>): String = buildString {
    appendLine("============================================================")
    appendLine("DETAILS")
    appendLine("============================================================")
    for (level in listOf(DeprecationLevel.ERROR, DeprecationLevel.HIDDEN, DeprecationLevel.WARNING)) {
        val bucket = findings.filter { it.level == level }
        if (bucket.isEmpty()) continue
        bucket.groupBy { it.deprecationId to it.message }.forEach { (key, group) ->
            val (deprecationId, message) = key
            // Qualified names: an allowlist entry is '<class>.<member>'.
            val classes = group.map { it.className }.distinct().sorted()
            val hits = group.distinctBy { listOf(it.file, it.line, it.column) }
                .sortedWith(compareBy({ it.file }, { it.line }, { it.column }))
            appendLine()
            appendLine("[${level.name}] $deprecationId")
            appendLine("  Declared in: ${describeClasses(classes)}")
            appendLine("  Reason: $message")
            appendLine("  Hits  : ${hits.size}")
            hits.forEach { f ->
                // Absolute path for IDE click-to-open
                appendLine("    ${f.file}:${f.line}:${f.column}")
                sourceLineWithCaret(f)?.forEach { appendLine("      $it") }
            }
        }
    }
    appendLine("------------------------------------------------------------")
}

/**
 * Checks requested target symbols against indexed deprecations and findings.
 * Distinguishes not-indexed symbols, genuinely unused APIs (0 usages), and allowlisted usages.
 * Matches both raw member names and getter/setter-normalized [DeprecatedSymbol.searchName].
 */
internal fun targetSymbolCheck(
    requestedSymbols: List<String>,
    index: List<DeprecatedSymbol>,
    allFindings: List<Finding>,
    allowlistedGroups: Set<Pair<String, String>>,
): String {
    if (requestedSymbols.isEmpty()) return ""
    return buildString {
        appendLine("============================================================")
        appendLine("TARGET SYMBOL CHECK")
        appendLine("============================================================")
        appendLine("Requested (from ticket): ${requestedSymbols.joinToString(", ")}")
        for (requested in requestedSymbols) {
            appendLine()
            val simple = requested.substringAfterLast('.').lowercase()
            val matches = index.filter { s ->
                simple == s.searchName.lowercase() || simple == (s.memberName ?: s.className.substringAfterLast('.')).lowercase()
            }
            if (matches.isEmpty()) {
                appendLine("[NOT FOUND] $requested")
                appendLine("  No symbol with this name exists in the indexed KGP jar(s).")
                appendLine("  Check spelling/qualification, or it may already be fully removed - use an earlier baseline KGP version.")
                continue
            }
            val groups = matches.groupBy { (it.memberName ?: it.className.substringAfterLast('.')) to it.message }
            groups.entries.forEachIndexed { i, (key, group) ->
                val (memberKey, message) = key
                val classes = group.map { it.className }.distinct().sorted()
                val usageCount = allFindings
                    .filter { it.deprecationId == memberKey && it.message == message }
                    .distinctBy { listOf(it.file, it.line, it.column) }
                    .size
                val isAllowlisted = key in allowlistedGroups
                if (i > 0) appendLine()
                appendLine("[FOUND] $requested -> $memberKey")
                appendLine("  Level      : ${group.first().level}")
                appendLine("  Declared in: ${describeClasses(classes)}")
                appendLine("  Reason     : $message")
                appendLine("  Allowlisted: ${if (isAllowlisted) "yes (config/allowlist-intellij.txt)" else "no"}")
                appendLine(
                    "  Usages     : $usageCount" + when {
                        usageCount == 0 -> " (indexed, no call sites found in monorepo)"
                        isAllowlisted -> " (all suppressed by the allowlist, not an active breakage)"
                        else -> " (active call site(s) in monorepo, not allowlisted)"
                    }
                )
            }
        }
        appendLine("------------------------------------------------------------")
    }
}

/** Lists declaring classes, truncated so a 20-interface hierarchy stays readable. */
internal fun describeClasses(classes: List<String>): String {
    val shown = classes.take(3)
    val rest = classes.size - shown.size
    return shown.joinToString(", ") + if (rest > 0) ", +$rest more" else ""
}

private fun sourceLineWithCaret(finding: Finding): List<String>? {
    val line = runCatching { File(finding.file).readLines().getOrNull(finding.line - 1) }.getOrNull() ?: return null
    val caret = " ".repeat((finding.column - 1).coerceAtLeast(0)) + "^"
    return listOf(line, caret)
}
