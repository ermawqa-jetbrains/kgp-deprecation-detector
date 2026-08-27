package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceFileFinderTest {

    private val marker = "callReflective"
    private val markerRegex = Regex(marker)
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File.createTempFile("finder", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, text: String = """x.callReflectiveGetter("getTarget", logger)""") {
        File(root, relative).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun walk(): Set<String> = relativize(SourceFileFinder.walkCandidates(root, markerRegex).toList())

    private fun ripgrep(): Set<String>? =
        SourceFileFinder.ripgrepCandidates(root, marker, fixedString = true)?.let(::relativize)

    private fun relativize(files: List<File>): Set<String> =
        files.map { it.canonicalFile.relativeTo(root.canonicalFile).path }.toSet()

    @Test
    fun bothPathsFindTheSameFiles() {
        // ripgrep and walk paths must return the same files.
        write("src/Plain.kt")
        write("src/Plain.java")
        write("src/NoMarker.kt", "val x = 1\n")
        write("src/notSource.txt")
        write(".gitignore", "ignored/\n")
        write("ignored/Ignored.kt")
        write(".hidden/Hidden.kt")
        write("src/.HiddenFile.kt")
        write(".git/objects/Fake.kt")

        val expected = setOf(
            "src/Plain.kt",
            "src/Plain.java",
            "ignored/Ignored.kt",
            ".hidden/Hidden.kt",
            "src/.HiddenFile.kt",
        )
        assertEquals(expected, walk())
        ripgrep()?.let { assertEquals(expected, it) }
    }

    @Test
    fun gitignoredSourcesAreScanned() {
        // .gitignore is ignored to ensure consistency.
        write(".gitignore", "generated/\n")
        write("generated/Generated.kt")
        assertEquals(setOf("generated/Generated.kt"), walk())
        ripgrep()?.let { assertEquals(setOf("generated/Generated.kt"), it) }
    }

    @Test
    fun gitDirectoryIsSkippedByBothPaths() {
        write(".git/objects/Fake.kt")
        write("src/Real.kt")
        assertEquals(setOf("src/Real.kt"), walk())
        ripgrep()?.let { assertEquals(setOf("src/Real.kt"), it) }
    }

    @Test
    fun excludePatternsFilterTheResult() {
        write("src/main/Real.kt")
        write("src/test/Fixture.kt")
        val found = relativize(
            SourceFileFinder.candidates(root, marker, markerRegex, fixedString = true, excludePatterns = listOf("/test/"))
                .toList()
        )
        assertEquals(setOf("src/main/Real.kt"), found)
    }

    @Test
    fun ripgrepIsUsedWhenAvailableAndIsNotRequired() {
        write("src/Real.kt")
        val rg = ripgrep()
        if (rg != null) assertTrue(rg.contains("src/Real.kt"))
    }
}
