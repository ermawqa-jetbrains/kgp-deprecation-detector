package org.jetbrains.kotlin.deprecations

/**
 * Encapsulates the indexed KGP deprecated symbols along with indexing statistics and banner generation.
 */
internal data class IndexData(
    val symbols: List<DeprecatedSymbol>,
    val skippedClasses: Int = 0,
    val outOfScopeClasses: Int = 0,
    val syntheticClasses: Int = 0,
) {
    /**
     * Builds the scan banner including target directories, KGP version, scope, exclusions, and allowlist.
     */
    fun buildBanner(config: DetectorConfig): String = buildString {
        appendLine("KGP DEPRECATION CHECK")
        appendLine("NOTE: MIGHT INCLUDE FALSE POSITIVES!")
        appendLine("  Scanning   : ${config.scanRoot.path}")
        appendLine("  KGP version: ${config.engineVersion ?: "(version unknown)"} (${symbols.size} deprecated symbol(s) indexed)")
        appendLine("  Allowlist: ${if (config.allowlist.isEmpty()) "(none)" else "${config.allowlist.size} entries"}")
        if (config.excludePatterns.isNotEmpty()) appendLine("  Excluded : ${config.excludePatterns.joinToString(", ")}")
        if (config.reportFilePath != null) appendLine("  Report in: ${config.reportFilePath}")
    }
}
