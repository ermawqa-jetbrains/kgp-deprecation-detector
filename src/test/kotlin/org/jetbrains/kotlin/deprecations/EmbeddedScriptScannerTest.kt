package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedScriptScannerTest {

    // Index with one deprecated property for testing.
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
    private val scanner = EmbeddedScriptScanner(index)
    private val qname = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.getDefaultSourceSetName"

    @Test
    fun flagsDeprecatedNameInGroovyCode() {
        val text = "allprojects {\n  if (compilation.defaultSourceSetName == ssn) { }\n}\n"
        val findings = scanner.scanText(text, "init.gradle", 1, 1)

        assertEquals(1, findings.size)
        val f = findings.single()
        assertEquals(qname, f.symbol)
        assertEquals(DeprecationLevel.ERROR, f.level)
        assertEquals(2, f.line)
    }

    @Test
    fun flagsDeprecatedNameInKotlinDslCode() {
        val text = "plugins {\n  kotlin(\"jvm\")\n}\nval x = compilation.defaultSourceSetName\n"
        val findings = scanner.scanText(text, "build.kts", 1, 1)

        assertEquals(1, findings.size)
        assertEquals(qname, findings.single().symbol)
        assertEquals(4, findings.single().line)
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
    fun reportsEveryOccurrenceOnTheSameLine() {
        // Two usages on one line should yield two findings.
        val text = "allprojects {\n  x(compilation.defaultSourceSetName, other.defaultSourceSetName)\n}\n"
        val findings = scanner.scanText(text, "init.gradle", 1, 1)
        assertEquals(2, findings.size)
        assertEquals(listOf(2, 2), findings.map { it.line })
        assertEquals(findings.map { it.column }.sorted(), findings.map { it.column })
        assertEquals(2, findings.map { it.column }.distinct().size)
    }

    @Test
    fun columnOnLinesAfterTheFirstIsAlreadyAbsolute() {
        // Only the first line needs column offsetting. Subsequent lines
        // carry host indentation.
        val text = "allprojects {\n    compilation.defaultSourceSetName\n}\n"
        val f = scanner.scanText(text, "Provider.kt", 10, 30).single()
        assertEquals(11, f.line)
        assertEquals(17, f.column) // 4 spaces + "compilation." (12), 1-based
    }

    @Test
    fun columnOnTheFirstLineIsShiftedByTheLiteralStart() {
        val text = "compilation.defaultSourceSetName; allprojects { }\n"
        val f = scanner.scanText(text, "Provider.kt", 10, 30).single()
        assertEquals(10, f.line)
        assertEquals(30 + 12, f.column)
    }

    @Test
    fun mapsLineThroughTheOffset() {
        // Script embedded at file line 50; the deprecated name is on the script's 3rd line.
        val text = "allprojects {\n  afterEvaluate {\n    compilation.defaultSourceSetName\n  }\n}\n"
        val f = scanner.scanText(text, "Provider.kt", 50, 1).single()
        assertEquals(52, f.line) // 50 + 3 - 1
    }
}
