package org.jetbrains.kotlin.deprecations

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexDataTest {

    private val tmp: File = createTempDirectory("index-data-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun buildBanner_formats_all_header_information() {
        val symbol = DeprecatedSymbol(
            className = "org.jetbrains.kotlin.gradle.dsl.KotlinCompile",
            memberName = "kotlinOptions",
            memberDescriptor = null,
            level = DeprecationLevel.ERROR,
            message = "Use compilerOptions",
            replaceWith = null,
        )
        val indexData = IndexData(
            symbols = listOf(symbol),
            skippedClasses = 5,
            outOfScopeClasses = 12,
            syntheticClasses = 3,
        )
        val config = DetectorConfig(
            scanRoot = File(tmp, "scanRoot"),
            allowlist = setOf("org.jetbrains.kotlin.gradle.dsl.KotlinCompile.kotlinOptions"),
            excludePatterns = listOf("/testData/"),
            pluginJars = listOf("dummy.jar"),
            engineVersion = "2.5.0-dev-1234",
            engineSource = "TeamCity KotlinDev",
            targetSymbols = listOf("kotlinOptions"),
            reportFilePath = "/tmp/report.txt",
            fullIndex = false,
        )
        val banner = indexData.buildBanner(config)
        assertContains(banner, "KGP DEPRECATION CHECK")
        assertContains(banner, "Scanning : ${config.scanRoot.path}")
        assertContains(banner, "KGP      : 2.5.0-dev-1234 (1 deprecated symbol(s) indexed)")
        assertContains(banner, "Jars from: TeamCity KotlinDev")
        assertContains(banner, "5 class(es) skipped (internal/utils/impl/Android)")
        assertContains(banner, "12 non-KGP class(es) ignored")
        assertContains(banner, "3 \$DefaultImpls class(es) ignored")
        assertContains(banner, "Allowlist: 1 entries")
        assertContains(banner, "Excluded : /testData/")
        assertContains(banner, "Report   : /tmp/report.txt")
    }

    @Test
    fun buildIndex_returns_null_when_no_deprecated_symbols_exist() {
        val jar = createJar("foo/Plain.class" to plainClassBytes())
        val config = DetectorConfig(
            scanRoot = tmp,
            allowlist = emptySet(),
            excludePatterns = emptyList(),
            pluginJars = listOf(jar.absolutePath),
            engineVersion = null,
            engineSource = null,
            targetSymbols = emptyList(),
            reportFilePath = null,
            fullIndex = false,
        )
        val indexData = buildIndex(config)
        assertNull(indexData)
    }

    @Test
    fun buildIndex_extracts_deprecated_symbols_and_statistics() {
        val jar = createJar(
            FAKE_KGP_CLASS_FILE to deprecatedClassBytes(FAKE_KGP_CLASS),
        )
        val config = DetectorConfig(
            scanRoot = tmp,
            allowlist = emptySet(),
            excludePatterns = emptyList(),
            pluginJars = listOf(jar.absolutePath),
            engineVersion = "2.4.10",
            engineSource = null,
            targetSymbols = emptyList(),
            reportFilePath = null,
            fullIndex = false,
        )
        val indexData = buildIndex(config)
        assertNotNull(indexData)
        assertEquals(1, indexData.symbols.size)
        assertEquals(FAKE_KGP_CLASS.replace('/', '.'), indexData.symbols.first().className)
    }

    @Test
    fun executeScan_and_publishReport_pipeline_integration() {
        val jar = createJar(
            FAKE_KGP_CLASS_FILE to deprecatedClassBytes(FAKE_KGP_CLASS),
        )
        val scanDir = File(tmp, "scanDir").apply { mkdirs() }
        File(scanDir, "Build.kt").writeText(
            """
            val initScript = ""${'"'}
                gradle.rootProject {
                    println(defaultSourceSetName)
                }
            ""${'"'}
            """.trimIndent(),
        )
        val reportFile = File(tmp, "report.txt")
        val config = DetectorConfig(
            scanRoot = scanDir,
            allowlist = emptySet(),
            excludePatterns = emptyList(),
            pluginJars = listOf(jar.absolutePath),
            engineVersion = "2.4.10",
            engineSource = null,
            targetSymbols = listOf("getDefaultSourceSetName"),
            reportFilePath = reportFile.absolutePath,
            fullIndex = false,
        )

        val indexData = buildIndex(config)
        assertNotNull(indexData)
        val scanResult = executeScan(config, indexData)
        assertTrue(scanResult.hasFailures)
        assertEquals(EXIT_FINDINGS, scanResult.exitCode)
        assertEquals(1, scanResult.usages.size)

        publishReport(config, scanResult)
        assertTrue(reportFile.exists())
        val reportContent = reportFile.readText()
        assertContains(reportContent, "TARGET SYMBOL CHECK")
        assertContains(reportContent, "[FOUND] getDefaultSourceSetName")
        assertContains(reportContent, "EXECUTIVE SUMMARY")
        assertContains(reportContent, "QUICK INDEX")
        assertContains(reportContent, "DETAILS")
    }

    private fun createJar(vararg entries: Pair<String, ByteArray>): File {
        val jar = File(tmp, "test-${System.nanoTime()}.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun plainClassBytes(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "foo/Plain", null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()

    private fun deprecatedClassBytes(internalName: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC, "getDefaultSourceSetName", "()Ljava/lang/String;", null, null).apply {
            visitAnnotation("Lkotlin/Deprecated;", true).apply {
                visit("message", "Use defaultSourceSet instead")
                visitEnum("level", "Lkotlin/DeprecationLevel;", "ERROR")
                visitEnd()
            }
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    companion object {
        private const val FAKE_KGP_CLASS = "org/jetbrains/kotlin/gradle/plugin/FakeKgpTarget"
        private const val FAKE_KGP_CLASS_FILE = "$FAKE_KGP_CLASS.class"
    }
}
