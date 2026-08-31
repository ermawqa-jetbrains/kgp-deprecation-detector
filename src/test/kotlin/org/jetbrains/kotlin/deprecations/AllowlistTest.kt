package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ensures the allowlist is auditable: every entry must have a reason.
 */
class AllowlistTest {

    private val allowlist = File("config/allowlist-intellij.txt")

    @Test
    fun everyEntryIsPrecededByAReasonComment() {
        var lastComment: String? = null
        val unexplained = mutableListOf<String>()
        allowlist.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> lastComment = line
                else -> if (lastComment == null) unexplained += line
            }
        }
        assertTrue(unexplained.isEmpty(), "allowlist entries without a reason comment: $unexplained")
    }

    @Test
    fun allowlistingOneSiblingClassSuppressesTheWholeGroup() {
        // Same logical deprecation ('kotlinOptions'), declared in two different classes -
        // the report groups them as one section ('Declared in: A, B').
        val a = Finding(
            file = "Build.kt", line = 1, column = 1,
            symbol = "org.jetbrains.kotlin.gradle.dsl.KotlinCompile.kotlinOptions",
            level = DeprecationLevel.ERROR, message = "use compilerOptions",
            memberName = "kotlinOptions",
        )
        val b = Finding(
            file = "Build.kt", line = 5, column = 1,
            symbol = "org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile.kotlinOptions",
            level = DeprecationLevel.ERROR, message = "use compilerOptions",
            memberName = "kotlinOptions",
        )
        val allFindings = listOf(a, b)
        val allowlist = setOf(a.symbol) // only one sibling class is allowlisted

        val allowlistedGroups = allFindings
            .filter { it.symbol in allowlist }
            .map { it.deprecationId to it.message }
            .toSet()
        val findings = allFindings.filterNot { (it.deprecationId to it.message) in allowlistedGroups }

        assertTrue(findings.isEmpty(), "allowlisting one sibling class must suppress the whole group: $findings")
    }

    @Test
    fun unrelatedGroupsAreNotAffectedByAllowlisting() {
        val kotlinOptions = Finding(
            file = "Build.kt", line = 1, column = 1,
            symbol = "org.jetbrains.kotlin.gradle.dsl.KotlinCompile.kotlinOptions",
            level = DeprecationLevel.ERROR, message = "use compilerOptions",
            memberName = "kotlinOptions",
        )
        val other = Finding(
            file = "Build.kt", line = 9, column = 1,
            symbol = "org.jetbrains.kotlin.gradle.dsl.KotlinCompile.freeCompilerArgs",
            level = DeprecationLevel.WARNING, message = "use compilerOptions.freeCompilerArgs",
            memberName = "freeCompilerArgs",
        )
        val allFindings = listOf(kotlinOptions, other)
        val allowlist = setOf(kotlinOptions.symbol)

        val allowlistedGroups = allFindings
            .filter { it.symbol in allowlist }
            .map { it.deprecationId to it.message }
            .toSet()
        val findings = allFindings.filterNot { (it.deprecationId to it.message) in allowlistedGroups }

        assertEquals(listOf(other), findings)
    }
}
