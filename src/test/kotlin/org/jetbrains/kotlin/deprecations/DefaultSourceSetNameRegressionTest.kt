package org.jetbrains.kotlin.deprecations

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reproduces the IntelliJ regression where `defaultSourceSetName` was escalated
 * from WARNING to ERROR by KGP, surfacing only as a runtime task-creation
 * failure ("cannot create task MainKt.main() due to missing defaultSourceSetName").
 *
 * This test wires the full pipeline (extractor + scanner) against a synthetic
 * KGP-shaped jar plus a fixture build script. If the detector ever stops
 * flagging this regression, this test must fail.
 */
class DefaultSourceSetNameRegressionTest {

    private val tmp: File = createTempDirectory("default-source-set-name-regression").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun detector_flags_defaultSourceSetName_at_ERROR_level() {
        val jar = synthesizeKgpJar()
        val monorepoRoot = writeFixtureMonorepo()

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)
        val matches = GradleFileScanner.scan(monorepoRoot.absolutePath, symbols)

        val errorMatches = matches.filter { it.symbol.level == DeprecationLevel.ERROR }
        assertTrue(errorMatches.isNotEmpty(), "Pipeline must flag at least one ERROR-level usage")

        val match = errorMatches.single { it.symbol.searchName == "defaultSourceSetName" }
        assertEquals(File(monorepoRoot, "intellij-style-module/build.gradle.kts").absolutePath, match.file)
        assertTrue(match.line.contains("defaultSourceSetName"))
        assertEquals("Use defaultSourceSet.name", match.symbol.message)
    }

    private fun writeFixtureMonorepo(): File {
        val root = File(tmp, "monorepo")
        val sub = File(root, "intellij-style-module")
        sub.mkdirs()
        File(sub, "build.gradle.kts").writeText("""
            // Pattern that triggered the IntelliJ regression: read of a
            // soon-to-be-removed KGP property at configuration time.
            kotlin {
                targets.all {
                    compilations.all {
                        val resolvedName = defaultSourceSetName
                        logger.lifecycle(resolvedName)
                    }
                }
            }
        """.trimIndent())
        return root
    }

    private fun synthesizeKgpJar(): File {
        val jar = File(tmp, "kgp-fake.jar")
        val classInternalName = "org/jetbrains/kotlin/gradle/plugin/KotlinCompilation"

        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, classInternalName, null, "java/lang/Object", null)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getDefaultSourceSetName", "()Ljava/lang/String;", null, null)
        val av = mv.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "Use defaultSourceSet.name")
        av.visitEnum("level", "Lkotlin/DeprecationLevel;", "ERROR")
        val rw = av.visitAnnotation("replaceWith", "Lkotlin/ReplaceWith;")
        rw.visit("expression", "defaultSourceSet.name")
        rw.visitArray("imports").visitEnd()
        rw.visitEnd()
        av.visitEnd()
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()

        cw.visitEnd()

        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("$classInternalName.class"))
            jos.write(cw.toByteArray())
            jos.closeEntry()
        }
        return jar
    }
}
