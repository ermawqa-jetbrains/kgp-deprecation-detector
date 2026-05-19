package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 2 || args[0].isBlank() || args[1].isBlank()) {
        printUsage()
        return
    }

    val jarPath = args[0]
    val monorepoRoot = File(args[1]).canonicalFile
    val allowlistPath = args.getOrNull(2)?.takeIf { it.isNotBlank() }

    val allowlist: Set<String> = if (allowlistPath != null) {
        val file = File(allowlistPath)
        if (!file.exists()) {
            System.err.println("Allowlist file not found: ${file.path}")
            return
        }
        file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    } else emptySet()

    println("KGP deprecation check")
    println("  Inspecting: $jarPath")
    println("  Scanning  : ${monorepoRoot.path}")
    println("  Allowlist : ${if (allowlistPath != null) "${allowlist.size} entries from $allowlistPath" else "(none)"}")
    println()

    val deprecated = KgpDeprecationExtractor.extract(jarPath)
    if (deprecated.isEmpty()) {
        println("No deprecated symbols found in jar.")
        println("Result: OK")
        return
    }

    val errSym = deprecated.count { it.level == DeprecationLevel.ERROR }
    val warnSym = deprecated.count { it.level == DeprecationLevel.WARNING }
    val hidSym = deprecated.count { it.level == DeprecationLevel.HIDDEN }
    println("Deprecated symbols in KGP: ${deprecated.size}  (ERROR=$errSym  WARNING=$warnSym  HIDDEN=$hidSym)")
    println()

    val matches = GradleFileScanner.scan(monorepoRoot.path, deprecated, allowlist)
    if (matches.isEmpty()) {
        println("No usages of deprecated KGP APIs found in scanned Gradle files.")
        println("Result: OK")
        return
    }

    val affectedFiles = matches.map { it.file }.toSet().size
    println("Found ${matches.size} deprecated usage(s) in $affectedFiles file(s):")
    println("------------------------------------------------------------")

    val levelOrder = listOf(DeprecationLevel.ERROR, DeprecationLevel.WARNING, DeprecationLevel.HIDDEN)
    for (level in levelOrder) {
        val bucket = matches.filter { it.symbol.level == level }
        if (bucket.isEmpty()) continue

        bucket.groupBy { it.symbol.qualifiedName }.forEach { (qName, usages) ->
            val sym = usages.first().symbol
            println()
            println("[${level.name}] $qName")
            if (sym.message.isNotBlank()) println("  Reason : ${sym.message}")
            sym.replaceWith?.let { println("  Replace: $it") }
            println("  Hits   : ${usages.size}")
            usages.forEach { m ->
                val rel = File(m.file).relativeToOrSelf(monorepoRoot).path
                println("    $rel:${m.lineNumber}")
                println("      ${m.line}")
                underline(m.line, sym)?.let { println("      $it") }
            }
        }
    }

    println("------------------------------------------------------------")
    val errorUsages = matches.count { it.symbol.level == DeprecationLevel.ERROR }
    val warnUsages = matches.count { it.symbol.level == DeprecationLevel.WARNING }
    val hidUsages = matches.count { it.symbol.level == DeprecationLevel.HIDDEN }

    if (errorUsages > 0) {
        System.err.println("Result: FAIL — $errorUsages ERROR-level usage(s) detected ($warnUsages WARNING, $hidUsages HIDDEN).")
        exitProcess(1)
    }
    println("Result: OK — no ERROR-level usages ($warnUsages WARNING, $hidUsages HIDDEN noted).")
}

private fun underline(line: String, symbol: DeprecatedSymbol): String? {
    val candidates = listOfNotNull(symbol.searchName, symbol.memberName).filter { it.isNotBlank() }
    for (term in candidates) {
        val idx = line.indexOf(term)
        if (idx >= 0) return " ".repeat(idx) + "^".repeat(term.length)
    }
    return null
}

private fun printUsage() {
    System.err.println("Usage: kgp-deprecation-checker <kgp-jar-path> <monorepo-dir> [<allowlist-file>]")
    System.err.println("  kgp-jar-path   Path to kotlin-gradle-plugin JAR to inspect")
    System.err.println("  monorepo-dir   Root directory to scan for .gradle.kts / .gradle files")
    System.err.println("  allowlist-file Optional. Text file: one symbol qualifiedName per line; '#' starts a comment.")
    System.err.println()
    System.err.println("As a Gradle task:")
    System.err.println("  ./gradlew checkKgpDeprecations -PkgpVersion=<ver> [-PmonorepoDir=<path>] [-Pallowlist=<path>]")
    System.err.println("  ./gradlew checkKgpDeprecations -PkgpJar=<abs-path> [-PmonorepoDir=<path>] [-Pallowlist=<path>]")
}
