package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedGroovyScriptExtractorTest {

    private fun extract(text: String) = EmbeddedGroovyScriptExtractor.extractFromText(text)

    @Test
    fun extractsScriptLikeLiteralWithStartLine() {
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
}
