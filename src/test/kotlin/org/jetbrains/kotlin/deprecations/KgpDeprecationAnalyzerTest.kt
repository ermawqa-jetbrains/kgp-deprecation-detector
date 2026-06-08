package org.jetbrains.kotlin.deprecations

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-analyzer tests: drive [KgpDeprecationAnalyzer] against a synthetic classpath (this
 * module's own test classes — see [org.jetbrains.kotlin.deprecations.fixtures]) with no Gradle
 * involved. Fast and CI-safe; the Gradle-driven path is covered by the opt-in integration test.
 */
class KgpDeprecationAnalyzerTest {

    private val classpath: List<File> =
        System.getProperty("java.class.path").split(File.pathSeparator).map(::File)

    private fun analyze(scriptBody: String, receiver: String): List<Finding> {
        val dir = Files.createTempDirectory("kgp-ut").toFile()
        val script = File(dir, "build.gradle.kts").apply { writeText(scriptBody) }
        return KgpDeprecationAnalyzer().analyze(script, classpath, implicitImports = emptyList(), receiverClass = receiver)
    }

    @Test
    fun flagsDeprecatedMemberOnReceiver() {
        val findings = analyze("oldApi()\n", FAKE_EXTENSION)
        assertEquals(1, findings.size, "expected one finding, got $findings")
        assertEquals(DeprecationLevel.WARNING, findings[0].level)
        assertEquals(1, findings[0].line)
        assertTrue(findings[0].symbol.contains("oldApi"), "symbol was ${findings[0].symbol}")
    }

    @Test
    fun readsErrorLevel() {
        val findings = analyze("goneApi()\n", FAKE_EXTENSION)
        assertEquals(1, findings.size)
        assertEquals(DeprecationLevel.ERROR, findings[0].level)
    }

    @Test
    fun ignoresNonDeprecatedMember() {
        assertTrue(analyze("newApi()\n", FAKE_EXTENSION).isEmpty())
    }

    /** Load-bearing: a same-named method on a non-deprecated receiver must NOT be flagged. */
    @Test
    fun noFalsePositiveForSameNameOnOtherReceiver() {
        val findings = analyze("oldApi()\n", UNRELATED_RECEIVER)
        assertTrue(findings.isEmpty(), "same-named non-deprecated symbol must not be flagged: $findings")
    }

    private companion object {
        const val FAKE_EXTENSION = "org.jetbrains.kotlin.deprecations.fixtures.FakeKotlinExtension"
        const val UNRELATED_RECEIVER = "org.jetbrains.kotlin.deprecations.fixtures.UnrelatedReceiver"
    }
}
