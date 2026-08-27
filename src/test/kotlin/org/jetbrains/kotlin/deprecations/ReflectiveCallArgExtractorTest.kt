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
        // Must have boundary before callReflective.
        val text = """xcallReflectiveGetter("getTarget", logger)"""
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun extractsTargetNameWhenTheCallIsWrappedOverSeveralLines() {
        // Handles calls wrapped over several lines.
        val text = """
            val x = instance.callReflectiveGetter(
                "getDefaultSourceSetName",
                logger,
            )
        """.trimIndent()
        val arg = extract(text).single()
        assertEquals("getDefaultSourceSetName", arg.name)
        assertEquals(2, arg.line)
        assertEquals(6, arg.column) // 4 spaces of indent + the opening quote
    }

    @Test
    fun reportsColumnRelativeToItsOwnLine() {
        val text = "val a = 1\nval b = x.callReflectiveGetter(\"getTarget\", logger)\n"
        val arg = extract(text).single()
        assertEquals(2, arg.line)
        assertEquals(33, arg.column)
    }

    @Test
    fun extractsBothCallSitesOnTheSameLine() {
        val text = """a.callReflectiveGetter("getOne", logger); b.callReflectiveGetter("getTwo", logger)"""
        assertEquals(listOf("getOne", "getTwo"), extract(text).map { it.name })
    }

    @Test
    fun resolvesTargetHeldInASameFileConstant() {
        val text = """
            private const val GETTER = "getDefaultSourceSetName"

            fun read(instance: Any) = instance.callReflectiveGetter(GETTER, logger)
        """.trimIndent()
        val arg = extract(text).single()
        assertEquals("getDefaultSourceSetName", arg.name)
        // Position stays on call site, not declaration.
        assertEquals(3, arg.line)
        assertEquals(57, arg.column)
    }

    @Test
    fun resolvesTargetHeldInATypedVal() {
        val text = """
            val getter: String = "getTarget"
            val x = instance.callReflectiveAnyGetter(getter, logger)
        """.trimIndent()
        assertEquals(listOf("getTarget"), extract(text).map { it.name })
    }

    @Test
    fun resolvesTargetHeldInAJavaStringConstant() {
        val text = """
            private static final String GETTER = "getTarget";
            Object x = instance.callReflectiveGetter(GETTER, logger);
        """.trimIndent()
        assertEquals(listOf("getTarget"), extract(text).map { it.name })
    }

    @Test
    fun resolvesQualifiedConstantReferenceBySimpleName() {
        val text = """
            object Names { const val GETTER = "getTarget" }
            val x = instance.callReflectiveGetter(Names.GETTER, logger)
        """.trimIndent()
        assertEquals(listOf("getTarget"), extract(text).map { it.name })
    }

    @Test
    fun ignoresIdentifierArgumentThatIsNotAKnownConstant() {
        // Unresolvable names yield nothing.
        val text = """instance.callReflectiveGetter(someName, logger)"""
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun ignoresConstantDeclaredTwiceWithDifferentValues() {
        // Ambiguous constants yield nothing.
        val text = """
            object A { const val GETTER = "getOne" }
            object B { const val GETTER = "getTwo" }
            val x = instance.callReflectiveGetter(GETTER, logger)
        """.trimIndent()
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun ignoresConcatenatedTargetName() {
        val text = """instance.callReflectiveGetter("get" + name, logger)"""
        assertEquals(emptyList(), extract(text))
    }

    @Test
    fun doesNotMatchPlainMethodCallWithoutReflectivePrefix() {
        val text = """instance.getCompilation("getTarget", logger)"""
        assertEquals(emptyList(), extract(text))
    }
}
