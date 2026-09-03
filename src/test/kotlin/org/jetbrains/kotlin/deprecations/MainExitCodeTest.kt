package org.jetbrains.kotlin.deprecations

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the exit-code contract: 0 (clean), 1 (findings), 2 (setup failure).
 */
class MainExitCodeTest {

    private val tmp: File = createTempDirectory("kgp-exit-code").toFile()
    private val properties =
        listOf("kgp.pluginJars", "kgp.engineVersion", "kgp.excludePatterns", "kgp.fullIndex", "kgp.reportFile", "teamcity.version")
    private val savedProperties = properties.associateWith { System.getProperty(it) }

    @AfterTest
    fun cleanup() {
        savedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        tmp.deleteRecursively()
    }

    @Test
    fun no_arguments_fails_setup() {
        val (code, err) = runSilently(emptyArray())
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "Usage:")
    }

    @Test
    fun blank_scan_root_fails_setup() {
        assertEquals(EXIT_SETUP_FAILURE, runSilently(arrayOf("   ")).first)
    }

    @Test
    fun missing_scan_root_directory_fails_setup() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val (code, err) = runSilently(arrayOf(File(tmp, "does-not-exist").path))
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "Not a directory:")
    }

    @Test
    fun scan_root_pointing_at_a_file_fails_setup() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val file = File(tmp, "not-a-dir.txt").apply { writeText("x") }
        assertEquals(EXIT_SETUP_FAILURE, runSilently(arrayOf(file.path)).first)
    }

    @Test
    fun missing_allowlist_file_fails_setup() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val root = File(tmp, "empty").apply { mkdirs() }
        val (code, err) = runSilently(arrayOf(root.path, File(tmp, "no-such-allowlist.txt").path))
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "Allowlist file not found:")
    }

    @Test
    fun no_kgp_jars_fails_setup() {
        System.clearProperty("kgp.pluginJars")
        val root = File(tmp, "empty").apply { mkdirs() }
        val (code, err) = runSilently(arrayOf(root.path))
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "No KGP jars provided")
    }

    @Test
    fun empty_index_fails_setup() {
        System.setProperty("kgp.pluginJars", jarOf("foo/Plain.class" to plainClassBytes()).absolutePath)
        val root = File(tmp, "empty").apply { mkdirs() }
        val (code, err) = runSilently(arrayOf(root.path))
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "No @Deprecated symbols found")
    }

    @Test
    fun unreadable_jar_is_reported_and_fails_setup() {
        val broken = File(tmp, "broken.jar").apply { writeText("not a jar") }
        val root = File(tmp, "empty").apply { mkdirs() }
        System.setProperty("kgp.pluginJars", broken.absolutePath)
        val (code, err) = runSilently(arrayOf(root.path))
        assertEquals(EXIT_SETUP_FAILURE, code)
        assertContains(err, "Failed to read KGP jar")
    }

    @Test
    fun clean_scan_succeeds() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val root = File(tmp, "clean").apply { mkdirs() }
        File(root, "Nothing.kt").writeText("val greeting = \"hello\"\n")
        assertEquals(0, runSilently(arrayOf(root.path)).first)
    }

    @Test
    fun error_level_finding_exits_with_findings_code() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        assertEquals(EXIT_FINDINGS, runSilently(arrayOf(rootWithDeprecatedUsage().path)).first)
    }

    @Test
    fun wrapped_reflective_call_is_found_end_to_end() {
        // Call wraps across lines.
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val root = File(tmp, "reflect/src").apply { mkdirs() }
        File(root, "Reflection.kt").writeText(
            """
            fun read(instance: Any) = instance.callReflectiveGetter(
                "getDefaultSourceSetName",
                logger,
            )
            """.trimIndent() + "\n"
        )
        assertEquals(EXIT_FINDINGS, runSilently(arrayOf(root.path)).first)
    }

    @Test
    fun reflective_call_via_same_file_constant_is_found_end_to_end() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val root = File(tmp, "const/src").apply { mkdirs() }
        File(root, "Reflection.kt").writeText(
            """
            private const val GETTER = "getDefaultSourceSetName"

            fun read(instance: Any) = instance.callReflectiveGetter(GETTER, logger)
            """.trimIndent() + "\n"
        )
        assertEquals(EXIT_FINDINGS, runSilently(arrayOf(root.path)).first)
    }

    @Test
    fun allowlisted_finding_succeeds() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val allowlist = File(tmp, "allowlist.txt").apply {
            writeText("# generic name collision\n$FAKE_KGP_CLASS.getDefaultSourceSetName\n")
        }
        val code = runSilently(arrayOf(rootWithDeprecatedUsage().path, allowlist.path)).first
        assertEquals(0, code)
    }

    @Test
    fun teamcity_environment_emits_build_status_and_problem_on_findings() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        System.setProperty("teamcity.version", "2024.1")
        val reportFile = File(tmp, "kgp-deprecations-report.txt")
        System.setProperty("kgp.reportFile", reportFile.path)

        val (code, out, _) = runCapturingOutput(arrayOf(rootWithDeprecatedUsage().path))
        assertEquals(EXIT_FINDINGS, code)
        assertContains(out, "##teamcity[buildStatus text='1 usage(s) in 1 file(s): 1 ERROR match(es).']")
        assertContains(
            out,
            "##teamcity[buildProblem description='KGP deprecation check FAILED: 1 usage(s) in 1 file(s): 1 ERROR match(es). Check artifact |'kgp-deprecations-report.txt|'.' identity='kgpDeprecations']",
        )
    }

    @Test
    fun teamcity_environment_emits_build_status_on_clean_run_without_build_problem() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        System.setProperty("teamcity.version", "2024.1")
        val root = File(tmp, "clean").apply { mkdirs() }
        File(root, "Nothing.kt").writeText("val greeting = \"hello\"\n")

        val (code, out, _) = runCapturingOutput(arrayOf(root.path))
        assertEquals(0, code)
        assertContains(out, "##teamcity[buildStatus text='No deprecated API usages found in embedded scripts or reflective calls.']")
        assertFalse(out.contains("##teamcity[buildProblem"), "Clean run must not emit buildProblem")
    }

    @Test
    fun local_environment_does_not_emit_teamcity_messages() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        System.clearProperty("teamcity.version")

        val (code, out, _) = runCapturingOutput(arrayOf(rootWithDeprecatedUsage().path))
        assertEquals(EXIT_FINDINGS, code)
        assertFalse(out.contains("##teamcity["), "Local run must not emit TeamCity service messages")
    }

    @Test
    fun teamcity_escaping_handles_special_characters() {
        val raw = "Test [with] 'quotes' and |pipes|\nnewline\rreturn"
        val expected = "Test |[with|] |'quotes|' and ||pipes||\nnewline\rreturn"
            .replace("\n", "|n")
            .replace("\r", "|r")
        assertEquals(expected, escapeTc(raw))
    }

    @Test
    fun report_file_contains_executive_summary_and_quick_index_on_findings() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val reportFile = File(tmp, "kgp-deprecations-report.txt")
        System.setProperty("kgp.reportFile", reportFile.path)

        val (code, _, _) = runCapturingOutput(arrayOf(rootWithDeprecatedUsage().path))
        assertEquals(EXIT_FINDINGS, code)
        val reportContent = reportFile.readText()
        assertContains(reportContent, "EXECUTIVE SUMMARY")
        assertContains(reportContent, "Total Usages: 1 across 1 file(s)")
        assertContains(reportContent, "- ERROR  : 1 (fails build / action required)")
        assertContains(reportContent, "QUICK INDEX")
        assertContains(reportContent, "SEVERITY | API SYMBOL")
        assertContains(reportContent, "ERROR    | getDefaultSourceSetName")
        assertContains(reportContent, "DETAILS")
        assertContains(reportContent, "[ERROR] getDefaultSourceSetName")
    }

    @Test
    fun executive_summary_formats_all_severities() {
        val findings = listOf(
            Finding("fileA.kt", 1, 1, "use defaultSourceSet.name", DeprecationLevel.ERROR, "org.example.Foo", "fooMember"),
            Finding("fileB.kt", 2, 1, "removed API", DeprecationLevel.HIDDEN, "org.example.Bar", "barMember"),
            Finding("fileC.kt", 3, 1, "will be removed", DeprecationLevel.WARNING, "org.example.Baz", "bazMember"),
        )
        val summary = executiveSummary(findings)
        assertContains(summary, "Total Usages: 3 across 3 file(s)")
        assertContains(summary, "- ERROR  : 1 (fails build / action required)")
        assertContains(summary, "- HIDDEN : 1 (removed API / action required)")
        assertContains(summary, "- WARNING: 1 (advisory / future removal)")
    }

    @Test
    fun quick_index_sorts_by_severity_and_usages() {
        val findings = listOf(
            Finding("fileA.kt", 1, 1, "warning message", DeprecationLevel.WARNING, "org.example.Warn", "warnApi"),
            Finding("fileB.kt", 2, 1, "error message 1", DeprecationLevel.ERROR, "org.example.ErrLow", "errLow"),
            Finding("fileC.kt", 3, 1, "error message 2", DeprecationLevel.ERROR, "org.example.ErrHigh", "errHigh"),
            Finding("fileD.kt", 4, 1, "error message 2", DeprecationLevel.ERROR, "org.example.ErrHigh", "errHigh"),
        )
        val index = quickIndex(findings)
        assertContains(index, "QUICK INDEX")
        assertContains(index, "SEVERITY | API SYMBOL")
        val lines = index.lines().filter { it.startsWith("ERROR") || it.startsWith("WARNING") }
        assertEquals(3, lines.size)
        // errHigh (2 usages) before errLow (1 usage)
        assertContains(lines[0], "errHigh")
        assertContains(lines[0], "2")
        assertContains(lines[1], "errLow")
        assertContains(lines[1], "1")
        assertContains(lines[2], "warnApi")
    }

    // --- helpers ---

    /** Runs [run] and captures output for assertions. */
    private fun runSilently(args: Array<String>): Pair<Int, String> {
        val (_, _, err) = runCapturingOutput(args)
        return run(args) to err
    }

    private fun runCapturingOutput(args: Array<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        try {
            val code = run(args)
            return Triple(code, out.toString(), err.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    /** A scan root holding one embedded script that uses the deprecated member. */
    private fun rootWithDeprecatedUsage(): File {
        val root = File(tmp, "monorepo/src").apply { mkdirs() }
        File(root, "InitScript.kt").writeText(
            """
            val initScript = ${"\"\"\""}
                gradle.rootProject {
                    println(defaultSourceSetName)
                }
            ${"\"\"\""}
            """.trimIndent() + "\n"
        )
        return root
    }

    private fun jarWithDeprecation(): File = jarOf(
        "$FAKE_KGP_INTERNAL_NAME.class" to deprecatedMemberClassBytes(),
    )

    private fun deprecatedMemberClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, FAKE_KGP_INTERNAL_NAME, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getDefaultSourceSetName", "()Ljava/lang/String;", null, null)
        val av = mv.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "use defaultSourceSet.name")
        av.visitEnum("level", "Lkotlin/DeprecationLevel;", "ERROR")
        av.visitEnd()
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun plainClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "foo/Plain", null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun jarOf(vararg entries: Pair<String, ByteArray>): File {
        val jar = File.createTempFile("kgp-fake-", ".jar", tmp)
        JarOutputStream(jar.outputStream()).use { jos ->
            for ((path, bytes) in entries) {
                jos.putNextEntry(JarEntry(path))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        // Must live in a KGP package: the index is scoped to the plugin's own packages by default.
        const val FAKE_KGP_INTERNAL_NAME = "org/jetbrains/kotlin/gradle/plugin/KotlinCompilation"
        const val FAKE_KGP_CLASS = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation"
    }
}
