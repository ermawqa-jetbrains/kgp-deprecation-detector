package org.jetbrains.kotlin.deprecations

/**
 * Encapsulates the results, statistics, and findings of a KGP deprecation scan.
 */
internal data class ScanResult(
    val index: List<DeprecatedSymbol>,
    val embeddedCandidateCount: Int,
    val reflectiveCandidateCount: Int,
    val allFindings: List<Finding>,
    val allowlistedGroups: Set<Pair<String, String>>,
    val filteredFindings: List<Finding>,
    val usages: List<Finding>,
    val banner: String,
    val targetCheck: String = "",
) {
    val scannedLine: String
        get() = "Scanned $embeddedCandidateCount embedded-script candidate file(s), " +
            "$reflectiveCandidateCount reflective-call candidate file(s)."

    val summaryLine: String
        get() = reportSummaryLine(usages)

    val errors: Int
        get() = usages.count { it.level == DeprecationLevel.ERROR }

    val hidden: Int
        get() = usages.count { it.level == DeprecationLevel.HIDDEN }

    val warnings: Int
        get() = usages.count { it.level == DeprecationLevel.WARNING }

    val hasFailures: Boolean
        get() = errors > 0 || hidden > 0

    val exitCode: Int
        get() = if (hasFailures) EXIT_FINDINGS else 0

    /**
     * Builds the full report text containing banner, candidate counts, target symbol checks,
     * executive summary, quick index, and detailed findings breakdown.
     */
    fun buildReportContent(): String = buildString {
        append(banner)
        appendLine()
        appendLine(scannedLine)
        appendLine()
        if (targetCheck.isNotEmpty()) {
            append(targetCheck)
            appendLine()
        }
        if (usages.isEmpty()) {
            appendLine(summaryLine)
        } else {
            append(executiveSummary(usages))
            appendLine()
            append(quickIndex(filteredFindings))
            appendLine()
            append(reportDetails(filteredFindings))
        }
    }
}
