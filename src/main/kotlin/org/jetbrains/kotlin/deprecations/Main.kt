package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.system.exitProcess

/**
 * Scans a monorepo of Kotlin Gradle build scripts (`.gradle.kts`) for usages of
 * `@Deprecated` APIs, resolving each script the way Gradle does.
 *
 * Usage: `<monorepo-dir> [<allowlist-file>] [<gradle-installation-dir>]`
 *  - allowlist-file: one deprecated-symbol signature per line; `#` starts a comment.
 *  - gradle-installation-dir: optional; defaults to each project's own Gradle wrapper.
 *
 * Exit 1 if any ERROR-level deprecation is found, else 0.
 */
fun main(args: Array<String>) {
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

    // Project build scripts only: settings/init scripts have a different receiver
    // (Settings/Gradle, not Project) and are not analysed.
    val scripts = monorepoRoot.walkTopDown()
        .filter {
            it.isFile && it.name.endsWith(".gradle.kts") &&
                it.name != "settings.gradle.kts" && it.name != "init.gradle.kts"
        }
        .toList()

    println("KGP deprecation check")
    println("  Scanning : ${monorepoRoot.path}")
    println("  Scripts  : ${scripts.size} .gradle.kts file(s)")
    println("  Allowlist: ${if (allowlist.isEmpty()) "(none)" else "${allowlist.size} entries"}")
    println()

    if (scripts.isEmpty()) {
        println("No .gradle.kts files found.")
        println("Result: OK")
        return
    }

    val analyzer = KgpDeprecationAnalyzer()
    val findings = mutableListOf<Finding>()
    var unresolved = 0
    for (script in scripts) {
        val projectDir = findGradleRoot(script, monorepoRoot)
        when (val model = GradleScriptModelProvider.fetch(projectDir, script, gradleInstallation)) {
            is ScriptModelResult.Resolved ->
                findings += analyzer.analyze(script, model.classPath, model.implicitImports)
            is ScriptModelResult.Failed -> {
                unresolved++
                System.err.println(
                    "  ! could not resolve ${script.relativeTo(monorepoRoot).path}: " +
                        model.message.lineSequence().first(),
                )
            }
        }
    }

    val reported = findings.filterNot { it.symbol in allowlist }
    report(reported, monorepoRoot, unresolved)

    if (reported.any { it.level == DeprecationLevel.ERROR }) exitProcess(1)
}

/** Nearest ancestor (up to [stopAt]) containing a settings script; else the script's own dir. */
private fun findGradleRoot(script: File, stopAt: File): File {
    var dir: File? = script.parentFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists() || File(dir, "settings.gradle").exists()) return dir
        if (dir.canonicalFile == stopAt) break
        dir = dir.parentFile
    }
    return script.parentFile
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

private fun report(findings: List<Finding>, monorepoRoot: File, unresolved: Int) {
    if (findings.isEmpty()) {
        println("No deprecated API usages found in resolved scripts.")
        if (unresolved > 0) println("UNRESOLVED: $unresolved script(s) could not be modelled (see warnings above).")
        println("Result: OK")
        return
    }

    val affected = findings.map { it.file }.toSet().size
    println("Found ${findings.size} deprecated usage(s) in $affected file(s):")
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
    val errors = findings.count { it.level == DeprecationLevel.ERROR }
    val warnings = findings.count { it.level == DeprecationLevel.WARNING }
    val hidden = findings.count { it.level == DeprecationLevel.HIDDEN }
    if (unresolved > 0) println("UNRESOLVED: $unresolved script(s) could not be modelled (see warnings above).")
    if (errors > 0) {
        System.err.println("Result: FAIL — $errors ERROR-level usage(s) ($warnings WARNING, $hidden HIDDEN).")
    } else {
        println("Result: OK — no ERROR-level usages ($warnings WARNING, $hidden HIDDEN noted).")
    }
}

private fun sourceLineWithCaret(finding: Finding): List<String>? {
    val line = runCatching { File(finding.file).readLines().getOrNull(finding.line - 1) }.getOrNull() ?: return null
    val caret = " ".repeat((finding.column - 1).coerceAtLeast(0)) + "^"
    return listOf(line, caret)
}

private fun printUsage() {
    System.err.println("Usage: kgp-deprecation-detector <monorepo-dir> [<allowlist-file>] [<gradle-installation-dir>]")
    System.err.println("  monorepo-dir            Root directory to scan for .gradle.kts files.")
    System.err.println("  allowlist-file          Optional. One deprecated-symbol signature per line; '#' starts a comment.")
    System.err.println("  gradle-installation-dir Optional. Defaults to each project's own Gradle wrapper.")
    System.err.println()
    System.err.println("As a Gradle task:")
    System.err.println("  ./gradlew checkKgpDeprecations [-PmonorepoDir=<path>] [-Pallowlist=<path>] [-PkgpEngineVersion=<ver>]")
}
