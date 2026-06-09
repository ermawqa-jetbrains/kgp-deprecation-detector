package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The leading `plugins { }` block is blanked before analysis; these guard that it is done correctly. */
class StripPluginsBlockTest {

    private fun strip(s: String) = KgpDeprecationAnalyzer.stripPluginsBlock(s)

    @Test
    fun blanksBlockAndPreservesLineCount() {
        val src = "plugins {\n    id(\"x\")\n}\nval a = 1\n"
        val out = strip(src)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' }, "line count must be preserved")
        assertFalse(out.contains("id("), "block body must be blanked")
        assertTrue(out.contains("val a = 1"), "code after the block must be intact")
    }

    @Test
    fun braceInCommentDoesNotEndBlockEarly() {
        val src = "plugins {\n    // a stray } here\n    id(\"x\")\n}\nval a = 1\n"
        val out = strip(src)
        assertFalse(out.contains("id("), "everything up to the real closing brace must be blanked")
        assertTrue(out.contains("val a = 1"))
    }

    @Test
    fun braceInStringDoesNotEndBlockEarly() {
        val src = "plugins {\n    id(\"a}b\")\n}\nval a = 1\n"
        val out = strip(src)
        assertFalse(out.contains("a}b"))
        assertTrue(out.contains("val a = 1"))
    }

    @Test
    fun blockBraceOnNextLineIsHandled() {
        val src = "plugins\n{\n    id(\"x\")\n}\nval a = 1\n"
        val out = strip(src)
        assertFalse(out.contains("id("))
        assertTrue(out.contains("val a = 1"))
    }

    @Test
    fun noPluginsBlockLeavesTextUnchanged() {
        val src = "val a = 1\nkotlin { jvm() }\n"
        assertEquals(src, strip(src))
    }

    @Test
    fun doesNotMatchPluginManagement() {
        val src = "pluginManagement {\n    repositories {}\n}\n"
        assertEquals(src, strip(src), "pluginManagement is not a plugins block")
    }
}
