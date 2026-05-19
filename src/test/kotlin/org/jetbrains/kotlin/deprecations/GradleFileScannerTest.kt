package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleFileScannerTest {

    private val tmp: File = createTempDirectory("scanner-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun write(relativePath: String, content: String): File {
        val f = File(tmp, relativePath)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f
    }

    private val defaultSourceSetNameSymbol = DeprecatedSymbol(
        className = "org.jetbrains.kotlin.gradle.plugin.KotlinCompilation",
        memberName = "getDefaultSourceSetName",
        memberDescriptor = "()Ljava/lang/String;",
        level = DeprecationLevel.ERROR,
        message = "Use defaultSourceSet.name",
        replaceWith = "defaultSourceSet.name"
    )

    private val targetHierarchySymbol = DeprecatedSymbol(
        className = "org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension",
        memberName = "getTargetHierarchy",
        memberDescriptor = "()Lorg/jetbrains/kotlin/gradle/dsl/KotlinTargetHierarchyDsl;",
        level = DeprecationLevel.WARNING,
        message = "Use applyDefaultHierarchyTemplate()",
        replaceWith = null
    )

    @Test
    fun detects_defaultSourceSetName_in_build_kts() {
        val file = write("build.gradle.kts", """
            kotlin {
                targets.all {
                    compilations.all {
                        val n = defaultSourceSetName
                    }
                }
            }
        """.trimIndent())

        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))

        assertEquals(1, matches.size)
        val m = matches.single()
        assertEquals(DeprecationLevel.ERROR, m.symbol.level)
        assertEquals(file.absolutePath, m.file)
        assertTrue(m.line.contains("defaultSourceSetName"))
        assertEquals(4, m.lineNumber)
    }

    @Test
    fun detects_inside_groovy_gradle_file() {
        write("legacy/build.gradle", "println defaultSourceSetName")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertEquals(1, matches.size)
    }

    @Test
    fun ignores_non_gradle_files() {
        write("Main.kt", "val defaultSourceSetName = 1")
        write("README.md", "Some doc about defaultSourceSetName")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty(), "Scanner must restrict to .gradle / .gradle.kts")
    }

    @Test
    fun no_match_when_identifier_absent() {
        write("build.gradle.kts", """
            kotlin { jvmToolchain(17) }
        """.trimIndent())
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun aggregates_matches_across_files() {
        write("a/build.gradle.kts", "val x = defaultSourceSetName")
        write("b/build.gradle.kts", "val y = defaultSourceSetName")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertEquals(2, matches.size)
    }

    @Test
    fun matches_property_form_for_getter_symbol() {
        // Symbol carries JVM getter name; scanner must also try the Kotlin property name.
        write("build.gradle.kts", "val n = defaultSourceSetName")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertEquals(1, matches.size)
    }

    @Test
    fun matches_raw_jvm_name_too() {
        write("build.gradle.kts", "compilation.getDefaultSourceSetName()")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        // Both "defaultSourceSetName" and "getDefaultSourceSetName" patterns can match
        // the same line. We require at least one hit on this line.
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it.lineNumber == 1 })
    }

    @Test
    fun detects_targetHierarchy_default_DSL() {
        write("build.gradle.kts", """
            kotlin {
                targetHierarchy.default()
            }
        """.trimIndent())
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(targetHierarchySymbol))
        assertEquals(1, matches.size)
        assertEquals(DeprecationLevel.WARNING, matches.single().symbol.level)
    }

    @Test
    fun skips_identifier_inside_line_comment() {
        write("build.gradle.kts", "// historical note: defaultSourceSetName was removed")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty(), "Identifier inside // comment must be skipped")
    }

    @Test
    fun skips_identifier_inside_block_comment() {
        write("build.gradle.kts", """
            /* mentions defaultSourceSetName
               across multiple lines */
            kotlin { jvmToolchain(17) }
        """.trimIndent())
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty(), "Identifier inside /* ... */ must be skipped")
    }

    @Test
    fun skips_identifier_inside_double_quoted_string() {
        write("build.gradle.kts", "val s = \"defaultSourceSetName\"")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty(), "Identifier inside \"...\" must be skipped")
    }

    @Test
    fun skips_identifier_inside_triple_quoted_string() {
        write("build.gradle.kts", "val s = \"\"\"defaultSourceSetName\"\"\"")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertTrue(matches.isEmpty(), "Identifier inside \"\"\"...\"\"\" must be skipped")
    }

    @Test
    fun still_detects_real_usage_when_comments_and_strings_present() {
        write("build.gradle.kts", """
            // defaultSourceSetName is the deprecated property
            val docString = "see defaultSourceSetName"
            kotlin {
                compilations.all {
                    val n = defaultSourceSetName
                }
            }
        """.trimIndent())
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertEquals(1, matches.size, "Only the real code usage must match")
        assertEquals(5, matches.single().lineNumber)
    }

    @Test
    fun deduplicates_same_symbol_on_same_line() {
        // A symbol with both searchName ("defaultSourceSetName") and raw ("getDefaultSourceSetName")
        // patterns can match the same line; only one GradleMatch should be returned.
        write("build.gradle.kts", "compilation.getDefaultSourceSetName()")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol))
        assertEquals(1, matches.size)
    }

    @Test
    fun allowlist_excludes_symbol_by_qualifiedName() {
        write("build.gradle.kts", "val n = defaultSourceSetName")
        val allow = setOf(defaultSourceSetNameSymbol.qualifiedName)
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol), allow)
        assertTrue(matches.isEmpty())
    }

    @Test
    fun allowlist_unrelated_entry_does_not_affect_match() {
        write("build.gradle.kts", "val n = defaultSourceSetName")
        val allow = setOf("some.other.Class.unrelated")
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(defaultSourceSetNameSymbol), allow)
        assertEquals(1, matches.size)
    }

    @Test
    fun knownFP_no_receiver_scope_check() {
        // Unrelated `name` access on a different DSL is still matched whenever the
        // symbol identifier happens to be short and common. Documents need for
        // receiver-aware filtering — out of scope for the comment/string masker.
        val nameSymbol = DeprecatedSymbol(
            className = "org.jetbrains.kotlin.gradle.plugin.KotlinTarget",
            memberName = "getName",
            memberDescriptor = "()Ljava/lang/String;",
            level = DeprecationLevel.WARNING,
            message = "x",
            replaceWith = null
        )
        write("build.gradle.kts", """
            application {
                mainClass.set(project.name)
            }
        """.trimIndent())
        val matches = GradleFileScanner.scan(tmp.absolutePath, listOf(nameSymbol))
        assertTrue(
            matches.isNotEmpty(),
            "Known FP: 'name' identifier matches regardless of receiver type."
        )
    }
}
