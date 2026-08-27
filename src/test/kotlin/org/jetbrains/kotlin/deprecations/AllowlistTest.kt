package org.jetbrains.kotlin.deprecations

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The allowlist is the tool's only mechanism for suppressing findings, so an unexplained or
 * stale entry silences a real violation with no trace. These tests pin the two properties that
 * make it auditable: every entry carries a reason, and the file records the KGP index version it
 * was curated against (a bump can turn a false positive into a genuine hit).
 */
class AllowlistTest {

    private val allowlist = File("test-monorepo/allowlist-intellij.txt")

    @Test
    fun everyEntryIsPrecededByAReasonComment() {
        var lastComment: String? = null
        val unexplained = mutableListOf<String>()
        allowlist.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> lastComment = line
                else -> if (lastComment == null) unexplained += line
            }
        }
        assertTrue(unexplained.isEmpty(), "allowlist entries without a reason comment: $unexplained")
    }

    @Test
    fun theCuratedKgpVersionIsDeclared() {
        val declared = allowlist.readLines().any { Regex("""^\s*#\s*kgp-version:\s*\S+""").containsMatchIn(it) }
        assertTrue(declared, "allowlist must declare '# kgp-version: <ver>' so drift can be detected")
    }

    @Test
    fun aVersionMismatchIsReported() {
        val note = capture { warnOnAllowlistDrift(allowlist, engineVersion = "9.9.9") }
        assertTrue(note.contains("re-review"), note)
    }

    @Test
    fun aMatchingVersionIsSilent() {
        val declared = allowlist.readLines()
            .firstNotNullOf { Regex("""^\s*#\s*kgp-version:\s*(\S+)""").find(it) }
            .groupValues[1]
        assertTrue(capture { warnOnAllowlistDrift(allowlist, declared) }.isBlank())
    }

    @Test
    fun anUndeclaredVersionIsReported() {
        val file = File.createTempFile("allowlist", ".txt").apply {
            deleteOnExit()
            writeText("# some reason\nsome.Symbol.member\n")
        }
        val note = capture { warnOnAllowlistDrift(file, engineVersion = "2.4.10") }
        assertTrue(note.contains("kgp-version"), note)
    }

    private fun capture(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(out, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return out.toString().trim()
    }
}
