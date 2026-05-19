package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleSourceMaskerTest {

    @Test
    fun keeps_plain_code_unchanged() {
        val src = "val x = defaultSourceSetName"
        assertEquals(src, maskCommentsAndStrings(src))
    }

    @Test
    fun masks_line_comment_contents_but_keeps_newline() {
        val src = "// defaultSourceSetName\nval x = 1"
        val out = maskCommentsAndStrings(src)
        assertEquals("                       \nval x = 1", out)
        assertTrue("defaultSourceSetName" !in out)
    }

    @Test
    fun masks_block_comment_across_lines_preserving_line_count() {
        val src = """
            /* line one
               line two with defaultSourceSetName
               line three */
            kotlin { }
        """.trimIndent()
        val out = maskCommentsAndStrings(src)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
        assertTrue("defaultSourceSetName" !in out)
        assertTrue("kotlin { }" in out, "Code after block comment must remain")
    }

    @Test
    fun masks_double_quoted_string() {
        val src = "val s = \"defaultSourceSetName\""
        val out = maskCommentsAndStrings(src)
        assertTrue("defaultSourceSetName" !in out)
    }

    @Test
    fun masks_triple_quoted_string_across_lines() {
        val src = "val s = \"\"\"foo\ndefaultSourceSetName\nbar\"\"\"\nval z = 1"
        val out = maskCommentsAndStrings(src)
        assertTrue("defaultSourceSetName" !in out)
        assertTrue("val z = 1" in out)
    }

    @Test
    fun masks_single_quoted_groovy_string() {
        val src = "println 'defaultSourceSetName'"
        val out = maskCommentsAndStrings(src)
        assertTrue("defaultSourceSetName" !in out)
    }

    @Test
    fun masks_triple_single_quoted_groovy_string() {
        val src = "def s = '''defaultSourceSetName'''"
        val out = maskCommentsAndStrings(src)
        assertTrue("defaultSourceSetName" !in out)
    }

    @Test
    fun escape_sequence_inside_string_does_not_terminate_early() {
        val src = "val s = \"a\\\"b defaultSourceSetName\"; val z = 1"
        val out = maskCommentsAndStrings(src)
        assertTrue("defaultSourceSetName" !in out)
        assertTrue("val z = 1" in out)
    }

    @Test
    fun code_after_inline_comment_on_same_logical_line_is_irrelevant() {
        // Line comments extend only to end-of-line; code on the NEXT line is untouched.
        val src = "kotlin { } // defaultSourceSetName\nval n = defaultSourceSetName"
        val out = maskCommentsAndStrings(src)
        val lines = out.lines()
        assertTrue("defaultSourceSetName" !in lines[0])
        assertTrue("defaultSourceSetName" in lines[1])
    }

    @Test
    fun length_preserved() {
        val src = "// foo\n/* bar */\n\"baz\"\nreal_code"
        val out = maskCommentsAndStrings(src)
        assertEquals(src.length, out.length, "Masker must preserve byte length to keep offsets stable")
    }
}
