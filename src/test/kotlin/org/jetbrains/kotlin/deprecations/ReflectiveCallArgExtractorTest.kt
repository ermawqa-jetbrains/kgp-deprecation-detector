package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals

class ReflectiveCallArgExtractorTest {

    private fun extract(text: String) = ReflectiveCallArgExtractor.extractFromText(text)

    @Test
    fun extractsTargetNameFromCallReflectiveGetter() {
        val text = """instance.callReflectiveGetter("getBaseName", logger)"""
        val args = extract(text)
        assertEquals(1, args.size)
        assertEquals("getBaseName", args.single().name)
        assertEquals(1, args.single().line)
    }

    @Test
    fun extractsFromAnyGetterAndBareVariant() {
        val text = """
            val a = x.callReflectiveAnyGetter("getCompilation", logger)
            val b = x.callReflective("getOutputFile", parameters(), returnType<Any>(), logger)
        """.trimIndent()
        val args = extract(text)
        assertEquals(listOf("getCompilation", "getOutputFile"), args.map { it.name })
    }

    @Test
    fun recordsCorrectLineNumberForLaterCallSite() {
        val text = "val a = 1\nval b = 2\nx.callReflectiveGetter(\"getTarget\", logger)\n"
        val args = extract(text)
        assertEquals(1, args.size)
        assertEquals(3, args.single().line)
    }

    @Test
    fun ignoresCallSiteInsideLineComment() {
        val text = "// x.callReflectiveGetter(\"getTarget\", logger)\nval y = 1\n"
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun ignoresCallSiteInsideBlockComment() {
        val text = "/* x.callReflectiveGetter(\"getTarget\", logger) */\nval y = 1\n"
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun ignoresIdentifierThatMerelyContainsCallReflectiveAsASubstring() {
        // `\b` requires a boundary before "callReflective" - a prefixed identifier like this
        // must not match just because it contains the marker text.
        val text = """xcallReflectiveGetter("getTarget", logger)"""
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun doesNotMatchPlainMethodCallWithoutReflectivePrefix() {
        val text = """instance.getCompilation("getTarget", logger)"""
        assertEquals(emptyList(), extract(text))
    }
}
