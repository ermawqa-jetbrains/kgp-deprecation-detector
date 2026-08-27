package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflectiveCallArgScannerTest {

    private fun symbol(
        memberName: String,
        level: DeprecationLevel = DeprecationLevel.ERROR,
        className: String = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation",
    ) = DeprecatedSymbol(
        className = className,
        memberName = memberName,
        memberDescriptor = "()Ljava/lang/String;",
        level = level,
        message = "deprecated",
        replaceWith = null,
    )

    @Test
    fun matchesReflectiveCallArgByExactMemberName() {
        val scanner = ReflectiveCallArgScanner(listOf(symbol("getDefaultSourceSetName")))
        val findings = scanner.scan(listOf(ReflectiveCallArg("getDefaultSourceSetName", line = 5, column = 10)), "Foo.kt")
        assertEquals(1, findings.size)
        val f = findings.single()
        assertEquals("Foo.kt", f.file)
        assertEquals(5, f.line)
        assertEquals(10, f.column)
        assertEquals(DeprecationLevel.ERROR, f.level)
    }

    @Test
    fun doesNotMatchUnrelatedName() {
        val scanner = ReflectiveCallArgScanner(listOf(symbol("getDefaultSourceSetName")))
        val findings = scanner.scan(listOf(ReflectiveCallArg("getCompilation", line = 1, column = 1)), "Foo.kt")
        assertTrue(findings.isEmpty())
    }

    @Test
    fun matchesEveryOverloadSharingTheSameMemberName() {
        val scanner = ReflectiveCallArgScanner(
            listOf(
                symbol("kotlinOptions", className = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation"),
                symbol("kotlinOptions", className = "org.jetbrains.kotlin.gradle.tasks.KotlinCompile"),
                symbol("getKotlinOptions"),
            ),
        )
        val findings = scanner.scan(listOf(ReflectiveCallArg("kotlinOptions", line = 1, column = 1)), "Foo.kt")
        assertEquals(2, findings.size)
    }

    @Test
    fun reportsEveryCallSiteOnTheSameLineSeparately() {
        // Two reflective calls to the same member on one line are two usages.
        val scanner = ReflectiveCallArgScanner(listOf(symbol("getTarget")))
        val findings = scanner.scan(
            listOf(
                ReflectiveCallArg("getTarget", line = 1, column = 1),
                ReflectiveCallArg("getTarget", line = 1, column = 40),
            ),
            "Foo.kt",
        )
        assertEquals(listOf(1, 40), findings.map { it.column })
    }

    @Test
    fun deduplicatesSameSymbolAtTheSamePosition() {
        val scanner = ReflectiveCallArgScanner(listOf(symbol("getTarget")))
        val findings = scanner.scan(
            listOf(
                ReflectiveCallArg("getTarget", line = 1, column = 7),
                ReflectiveCallArg("getTarget", line = 1, column = 7),
            ),
            "Foo.kt",
        )
        assertEquals(1, findings.size)
    }
}
