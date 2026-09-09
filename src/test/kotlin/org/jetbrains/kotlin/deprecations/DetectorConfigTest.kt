package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectorConfigTest {

    private val tmp: File = createTempDirectory("detector-config-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun parse_returns_null_when_args_are_empty_or_blank() {
        assertNull(DetectorConfig.parse(emptyArray()))
        assertNull(DetectorConfig.parse(arrayOf("   ")))
    }

    @Test
    fun parse_returns_null_when_scan_root_does_not_exist_or_is_file() {
        val nonExistent = File(tmp, "does-not-exist")
        val properties = mapOf("kgp.pluginJars" to "/dummy.jar")
        assertNull(DetectorConfig.parse(arrayOf(nonExistent.path), properties::get))

        val file = File(tmp, "file.txt").apply { writeText("hello") }
        assertNull(DetectorConfig.parse(arrayOf(file.path), properties::get))
    }

    @Test
    fun parse_returns_null_when_allowlist_file_does_not_exist() {
        val root = File(tmp, "root").apply { mkdirs() }
        val properties = mapOf("kgp.pluginJars" to "/dummy.jar")
        val nonExistentAllowlist = File(tmp, "missing-allowlist.txt")

        assertNull(DetectorConfig.parse(arrayOf(root.path, nonExistentAllowlist.path), properties::get))
    }

    @Test
    fun parse_returns_null_when_plugin_jars_are_missing() {
        val root = File(tmp, "root").apply { mkdirs() }
        val properties = mapOf<String, String>()

        assertNull(DetectorConfig.parse(arrayOf(root.path), properties::get))
    }

    @Test
    fun parse_populates_valid_defaults_and_properties() {
        val root = File(tmp, "root").apply { mkdirs() }
        val allowlistFile = File(tmp, "allowlist.txt").apply {
            writeText(
                """
                # Comment
                org.example.Foo.bar
                org.example.Baz.qux # inline comment
                """.trimIndent()
            )
        }
        val properties = mapOf(
            "kgp.pluginJars" to "/path/to/jar1.jar${File.pathSeparator}/path/to/jar2.jar",
            "kgp.engineVersion" to "2.5.0-dev-1234",
            "kgp.engineSource" to "TeamCity KotlinDev",
            "kgp.excludePatterns" to "/custom1/, /custom2/",
            "kgp.targetSymbols" to "symbolA, symbolB, symbolA",
            "kgp.reportFile" to "/tmp/report.txt",
            "kgp.fullIndex" to "true",
        )

        val config = DetectorConfig.parse(arrayOf(root.path, allowlistFile.path), properties::get)
        assertNotNull(config)

        assertEquals(root.canonicalFile, config.scanRoot)
        assertEquals(setOf("org.example.Foo.bar", "org.example.Baz.qux"), config.allowlist)
        assertEquals(listOf("/path/to/jar1.jar", "/path/to/jar2.jar"), config.pluginJars)
        assertEquals("2.5.0-dev-1234", config.engineVersion)
        assertEquals("TeamCity KotlinDev", config.engineSource)
        assertEquals(listOf("symbolA", "symbolB"), config.targetSymbols)
        assertEquals("/tmp/report.txt", config.reportFilePath)
        assertTrue(config.fullIndex)

        assertTrue(config.excludePatterns.contains("/custom1/"))
        assertTrue(config.excludePatterns.contains("/custom2/"))
        assertTrue(config.excludePatterns.contains("/testData/"))
    }

    @Test
    fun parse_without_optional_properties_uses_sensible_defaults() {
        val root = File(tmp, "root").apply { mkdirs() }
        val properties = mapOf("kgp.pluginJars" to "/path/to/jar1.jar")

        val config = DetectorConfig.parse(arrayOf(root.path), properties::get)
        assertNotNull(config)

        assertEquals(root.canonicalFile, config.scanRoot)
        assertTrue(config.allowlist.isEmpty())
        assertEquals(listOf("/path/to/jar1.jar"), config.pluginJars)
        assertNull(config.engineVersion)
        assertNull(config.engineSource)
        assertTrue(config.targetSymbols.isEmpty())
        assertNull(config.reportFilePath)
        assertFalse(config.fullIndex)
        assertEquals(DetectorConfig.DEFAULT_EXCLUDE_PATTERNS, config.excludePatterns)
    }
}
