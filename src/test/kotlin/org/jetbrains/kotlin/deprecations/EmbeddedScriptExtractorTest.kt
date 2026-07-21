package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedScriptExtractorTest {

    private fun extract(text: String) = EmbeddedScriptExtractor.extractFromText(text)

    @Test
    fun extractsGroovyScriptLikeLiteralWithStartLine() {
        val text = """
            class Provider {
                val initScript = ${'"'}""
                    allprojects {
                        afterEvaluate { }
                    }
                ${'"'}"".trimIndent()
            }
        """.trimIndent()

        val scripts = extract(text)
        assertEquals(1, scripts.size)
        val s = scripts.single()
        assertTrue(s.text.contains("allprojects"), "content captured")
        // Opening `"""` is on the `val initScript = """` line (line 2 here); content begins there.
        assertEquals(2, s.startLine)
    }

    @Test
    fun extractsKotlinDslScriptLikeLiteral() {
        val text = """
            val kts = ${'"'}""
                plugins {
                    kotlin("jvm")
                }
                tasks.register("run") { }
            ${'"'}"".trimIndent()
        """.trimIndent()

        val scripts = extract(text)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().text.contains("kotlin(\"jvm\")"))
    }

    @Test
    fun handlesMultiDollarRawString() {
        val text = "val s = ${'$'}${'$'}\"\"\"\n  tasks.register('x')\n\"\"\"\n"
        val scripts = extract(text)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().text.contains("tasks.register"))
    }

    @Test
    fun ignoresNonScriptProseLiteral() {
        val text = "val doc = \"\"\"\n  This is plain documentation text, not a build script.\n\"\"\"\n"
        assertTrue(extract(text).isEmpty(), "prose triple-quote must not be treated as a script")
    }

    @Test
    fun returnsOnlyTheScriptLiteralWhenBothPresent() {
        val text =
            "val doc = \"\"\"\n  just prose here\n\"\"\"\n" +
            "val init = \"\"\"\n  allprojects { afterEvaluate { } }\n\"\"\"\n"
        val scripts = extract(text)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().text.contains("allprojects"))
    }

    @Test
    fun skipsDisallowedLanguageEvenWhenTripleQuoteIsOnItsOwnLine() {
        // Real JewelReadme.kt shape: annotation, then the declaration line, then `"""` alone
        // on the next line — two lines separate the annotation from the opening quote.
        val text = """
            @Language("Markdown")
            internal val readme: String =
                ${'"'}""
            some docs showing `plugins {
                kotlin("jvm")
            }` as an example
                ${'"'}""
        """.trimIndent()
        assertTrue(extract(text).isEmpty(), "Markdown-tagged literal must not be extracted")
    }

    @Test
    fun skipsLiteralTaggedWithADisallowedLanguage() {
        // Regression: a @Language("Markdown") README-as-a-string that happens to show a
        // `plugins { }` code sample must not be treated as a real embedded script.
        val text = """
            @Language("Markdown")
            internal val readme = ${'"'}""
            some docs showing `plugins {
                kotlin("jvm")
            }` as an example
            ${'"'}""
        """.trimIndent()
        assertTrue(extract(text).isEmpty(), "Markdown-tagged literal must not be extracted")
    }

    @Test
    fun keepsLiteralTaggedGroovyEvenAsACommentAnnotation() {
        // Real IntelliJ pattern: `// @Language("Groovy")` (comment form, used on a local val).
        val text = """
            // @formatter:off
            // @Language("Groovy")
            val initScript = ${'"'}""
                allprojects { afterEvaluate { } }
            ${'"'}""
        """.trimIndent()
        val scripts = extract(text)
        assertEquals(1, scripts.size)
        assertTrue(scripts.single().text.contains("allprojects"))
    }

    @Test
    fun keepsLiteralTaggedKotlin() {
        val text = """
            @Language("kotlin")
            val kts = ${'"'}""
                plugins { kotlin("jvm") }
            ${'"'}""
        """.trimIndent()
        val scripts = extract(text)
        assertEquals(1, scripts.size)
    }

    @Test
    fun untaggedLiteralStillUsesTheMarkerFallback() {
        // No @Language annotation at all: falls back to the existing marker-based check.
        val text = "val init = \"\"\"\n  allprojects { afterEvaluate { } }\n\"\"\"\n"
        val scripts = extract(text)
        assertEquals(1, scripts.size)
    }
}
