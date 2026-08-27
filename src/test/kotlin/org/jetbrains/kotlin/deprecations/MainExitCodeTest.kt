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

/**
 * Pins the exit-code contract: 0 = clean, [EXIT_FINDINGS] = blocking usages,
 * [EXIT_SETUP_FAILURE] = the check never ran. A setup failure must never look like success -
 * a mistyped scan root used to produce a green build with zero findings.
 */
class MainExitCodeTest {

    private val tmp: File = createTempDirectory("kgp-exit-code").toFile()
    private val properties =
        listOf("kgp.pluginJars", "kgp.engineVersion", "kgp.excludePatterns", "kgp.fullIndex")
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
        // The call is formatted across lines, so the literal sits below `callReflectiveGetter(`.
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
    fun allowlisted_finding_succeeds() {
        System.setProperty("kgp.pluginJars", jarWithDeprecation().absolutePath)
        val allowlist = File(tmp, "allowlist.txt").apply {
            writeText("# generic name collision\nfoo.KotlinCompilation.getDefaultSourceSetName\n")
        }
        val code = runSilently(arrayOf(rootWithDeprecatedUsage().path, allowlist.path)).first
        assertEquals(0, code)
    }

    // --- helpers ---

    /** Runs [run] with stdout/stderr captured; returns the exit code and the captured stderr. */
    private fun runSilently(args: Array<String>): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(ByteArrayOutputStream()))
        System.setErr(PrintStream(err))
        try {
            return run(args) to err.toString()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    /** A scan root holding one embedded Groovy script that uses the deprecated member. */
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
        "foo/KotlinCompilation.class" to deprecatedMemberClassBytes(),
    )

    private fun deprecatedMemberClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "foo/KotlinCompilation", null, "java/lang/Object", null)
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
}
