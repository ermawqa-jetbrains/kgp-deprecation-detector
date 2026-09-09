package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanResultTest {

    @Test
    fun scannedLine_formats_candidate_counts() {
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 42,
            reflectiveCandidateCount = 7,
            allFindings = emptyList(),
            allowlistedGroups = emptySet(),
            filteredFindings = emptyList(),
            usages = emptyList(),
            banner = "BANNER",
        )
        assertEquals("Scanned 42 embedded-script candidate file(s), 7 reflective-call candidate file(s).", result.scannedLine)
    }

    @Test
    fun clean_scan_result_has_no_failures_and_zero_exit_code() {
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 10,
            reflectiveCandidateCount = 2,
            allFindings = emptyList(),
            allowlistedGroups = emptySet(),
            filteredFindings = emptyList(),
            usages = emptyList(),
            banner = "BANNER",
        )
        assertEquals(0, result.errors)
        assertEquals(0, result.hidden)
        assertEquals(0, result.warnings)
        assertFalse(result.hasFailures)
        assertEquals(0, result.exitCode)
        assertEquals("No deprecated API usages found in embedded scripts or reflective calls.", result.summaryLine)
    }

    @Test
    fun warnings_only_has_no_failures_and_zero_exit_code() {
        val warningFinding = Finding(
            file = "fileA.kt",
            line = 10,
            column = 5,
            symbol = "org.example.Foo.fooMember",
            level = DeprecationLevel.WARNING,
            message = "warning message",
            className = "org.example.Foo",
            memberName = "fooMember",
        )
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 5,
            reflectiveCandidateCount = 1,
            allFindings = listOf(warningFinding),
            allowlistedGroups = emptySet(),
            filteredFindings = listOf(warningFinding),
            usages = listOf(warningFinding),
            banner = "BANNER",
        )
        assertEquals(0, result.errors)
        assertEquals(0, result.hidden)
        assertEquals(1, result.warnings)
        assertFalse(result.hasFailures)
        assertEquals(0, result.exitCode)
        assertContains(result.summaryLine, "1 usage(s) in 1 file(s): 1 WARNING match(es).")
    }

    @Test
    fun errors_or_hidden_trigger_failure_and_exit_findings_code() {
        val errorFinding = Finding(
            file = "fileA.kt",
            line = 10,
            column = 5,
            symbol = "org.example.Foo.fooMember",
            level = DeprecationLevel.ERROR,
            message = "error message",
            className = "org.example.Foo",
            memberName = "fooMember",
        )
        val hiddenFinding = Finding(
            file = "fileB.kt",
            line = 20,
            column = 1,
            symbol = "org.example.Bar.barMember",
            level = DeprecationLevel.HIDDEN,
            message = "hidden message",
            className = "org.example.Bar",
            memberName = "barMember",
        )
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 5,
            reflectiveCandidateCount = 1,
            allFindings = listOf(errorFinding, hiddenFinding),
            allowlistedGroups = emptySet(),
            filteredFindings = listOf(errorFinding, hiddenFinding),
            usages = listOf(errorFinding, hiddenFinding),
            banner = "BANNER",
        )
        assertEquals(1, result.errors)
        assertEquals(1, result.hidden)
        assertTrue(result.hasFailures)
        assertEquals(EXIT_FINDINGS, result.exitCode)
    }

    @Test
    fun buildReportContent_formats_empty_usages_report() {
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 3,
            reflectiveCandidateCount = 1,
            allFindings = emptyList(),
            allowlistedGroups = emptySet(),
            filteredFindings = emptyList(),
            usages = emptyList(),
            banner = "KGP DEPRECATION CHECK BANNER",
            targetCheck = "[TARGET CHECK SECTION]",
        )
        val content = result.buildReportContent()
        assertContains(content, "KGP DEPRECATION CHECK BANNER")
        assertContains(content, "Scanned 3 embedded-script candidate file(s), 1 reflective-call candidate file(s).")
        assertContains(content, "[TARGET CHECK SECTION]")
        assertContains(content, "No deprecated API usages found in embedded scripts or reflective calls.")
    }

    @Test
    fun buildReportContent_formats_findings_report_with_executive_summary_and_index() {
        val finding = Finding(
            file = "src/Foo.kt",
            line = 12,
            column = 4,
            symbol = "org.example.PluginExtension.kotlinOptions",
            level = DeprecationLevel.ERROR,
            message = "Use compilerOptions instead",
            className = "org.example.PluginExtension",
            memberName = "kotlinOptions",
        )
        val result = ScanResult(
            index = emptyList(),
            embeddedCandidateCount = 10,
            reflectiveCandidateCount = 2,
            allFindings = listOf(finding),
            allowlistedGroups = emptySet(),
            filteredFindings = listOf(finding),
            usages = listOf(finding),
            banner = "KGP DEPRECATION CHECK BANNER",
            targetCheck = "[TARGET CHECK SECTION]",
        )
        val content = result.buildReportContent()
        assertContains(content, "KGP DEPRECATION CHECK BANNER")
        assertContains(content, "Scanned 10 embedded-script candidate file(s), 2 reflective-call candidate file(s).")
        assertContains(content, "[TARGET CHECK SECTION]")
        assertContains(content, "EXECUTIVE SUMMARY")
        assertContains(content, "Total Usages: 1 across 1 file(s)")
        assertContains(content, "QUICK INDEX")
        assertContains(content, "kotlinOptions")
        assertContains(content, "DETAILS")
        assertContains(content, "[ERROR] kotlinOptions")
    }
}
