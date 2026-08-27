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
        // Handles @Language annotation when separated by several lines.
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
        // Markdown snippets in READMEs must not be extracted.
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
        // Supports // @Language("Groovy") comment form.
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
        // Untagged literals fall back to marker check.
        val text = "val init = \"\"\"\n  allprojects { afterEvaluate { } }\n\"\"\"\n"
        val scripts = extract(text)
        assertEquals(1, scripts.size)
    }
}
