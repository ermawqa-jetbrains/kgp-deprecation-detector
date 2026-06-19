package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroovyDeprecationScannerTest {

    // Index with a single deprecated property: KotlinCompilation.defaultSourceSetName (ERROR).
    private val index = listOf(
        DeprecatedSymbol(
            className = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation",
            memberName = "getDefaultSourceSetName",
            memberDescriptor = "()Ljava/lang/String;",
            level = DeprecationLevel.ERROR,
            message = "use defaultSourceSet.name",
            replaceWith = "defaultSourceSet.name",
        ),
    )
    private val scanner = GroovyDeprecationScanner(index)
    private val qname = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.getDefaultSourceSetName"

    @Test
    fun flagsDeprecatedNameInGroovyCode() {
        val text = "allprojects {\n  if (compilation.defaultSourceSetName == ssn) { }\n}\n"
        val findings = scanner.scanText(text, "init.gradle", 1, 1)

        assertEquals(1, findings.size)
        val f = findings.single()
        assertEquals(qname, f.symbol)
        assertEquals(DeprecationLevel.ERROR, f.level)
        assertEquals(FindingSource.HEURISTIC, f.source)
        assertEquals(2, f.line)
    }

    @Test
    fun doesNotFlagInsideLineComment() {
        val text = "allprojects {\n  // compilation.defaultSourceSetName is deprecated\n}\n"
        assertTrue(scanner.scanText(text, "init.gradle", 1, 1).isEmpty())
    }

    @Test
    fun doesNotFlagInsideStringLiteral() {
        val text = "allprojects {\n  def x = 'defaultSourceSetName'\n}\n"
        assertTrue(scanner.scanText(text, "init.gradle", 1, 1).isEmpty())
    }

    @Test
    fun doesNotFlagUnindexedIdentifier() {
        val text = "allprojects {\n  project.tasks.create('foo')\n}\n"
        assertTrue(scanner.scanText(text, "init.gradle", 1, 1).isEmpty())
    }

    @Test
    fun mapsLineThroughTheOffset() {
        // Script embedded at file line 50; the deprecated name is on the script's 3rd line.
        val text = "allprojects {\n  afterEvaluate {\n    compilation.defaultSourceSetName\n  }\n}\n"
        val f = scanner.scanText(text, "Provider.kt", 50, 1).single()
        assertEquals(52, f.line) // 50 + 3 - 1
    }
}
