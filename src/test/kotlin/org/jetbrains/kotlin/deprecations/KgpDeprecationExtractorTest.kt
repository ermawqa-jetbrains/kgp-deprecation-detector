package org.jetbrains.kotlin.deprecations

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures live in a KGP package: the index only keeps org.jetbrains.kotlin.* classes.
class KgpDeprecationExtractorTest {

    private val tmp: File = createTempDirectory("kgp-extract-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun extracts_class_level_deprecation() {
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/OldClass.class" to classBytes(
                internalName = "org/jetbrains/kotlin/gradle/OldClass",
                classAnnotation = DeprecationSpec("ERROR", "removed", "NewClass"),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)

        assertEquals(1, symbols.size)
        val s = symbols.single()
        assertEquals("org.jetbrains.kotlin.gradle.OldClass", s.className)
        assertNull(s.memberName)
        assertEquals(DeprecationLevel.ERROR, s.level)
        assertEquals("removed", s.message)
        assertEquals("NewClass", s.replaceWith)
    }

    @Test
    fun extracts_method_level_deprecation_with_replaceWith() {
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/KotlinCompilation.class" to classBytes(
                internalName = "org/jetbrains/kotlin/gradle/KotlinCompilation",
                methods = listOf(
                    MethodSpec(
                        name = "getDefaultSourceSetName",
                        descriptor = "()Ljava/lang/String;",
                        deprecation = DeprecationSpec("ERROR", "use defaultSourceSet.name", "defaultSourceSet.name"),
                    ),
                ),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)

        assertEquals(1, symbols.size)
        val s = symbols.single()
        assertEquals("org.jetbrains.kotlin.gradle.KotlinCompilation", s.className)
        assertEquals("getDefaultSourceSetName", s.memberName)
        assertEquals(DeprecationLevel.ERROR, s.level)
        assertEquals("defaultSourceSet.name", s.replaceWith)
        // Kotlin property name derived from JVM getter:
        assertEquals("defaultSourceSetName", s.searchName)
    }

    @Test
    fun extracts_all_deprecation_levels() {
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/MultiLevel.class" to classBytes(
                internalName = "org/jetbrains/kotlin/gradle/MultiLevel",
                methods = listOf(
                    MethodSpec("aMethod", "()V", DeprecationSpec("WARNING", "w", null)),
                    MethodSpec("bMethod", "()V", DeprecationSpec("ERROR", "e", null)),
                    MethodSpec("cMethod", "()V", DeprecationSpec("HIDDEN", "h", null)),
                ),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath).associateBy { it.memberName }

        assertEquals(DeprecationLevel.WARNING, symbols["aMethod"]?.level)
        assertEquals(DeprecationLevel.ERROR, symbols["bMethod"]?.level)
        assertEquals(DeprecationLevel.HIDDEN, symbols["cMethod"]?.level)
    }

    @Test
    fun ignores_non_deprecated_methods() {
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/Plain.class" to classBytes(
                internalName = "org/jetbrains/kotlin/gradle/Plain",
                methods = listOf(MethodSpec("doX", "()V", null)),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)
        assertTrue(symbols.isEmpty())
    }

    @Test
    fun excludes_internal_utils_impl_packages_and_android_classes() {
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/Public.class" to classBytes("org/jetbrains/kotlin/gradle/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            // Exclude both top-level and nested internal packages.
            "org/jetbrains/kotlin/gradle/internal/Hidden.class" to classBytes("org/jetbrains/kotlin/gradle/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/internal/sub/Hidden.class" to classBytes("org/jetbrains/kotlin/gradle/internal/sub/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/utils/Helper.class" to classBytes("org/jetbrains/kotlin/gradle/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/impl/Concrete.class" to classBytes("org/jetbrains/kotlin/gradle/impl/Concrete", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/AndroidThing.class" to classBytes("org/jetbrains/kotlin/gradle/AndroidThing", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val classNames = KgpDeprecationExtractor.extract(jar.absolutePath).map { it.className }.toSet()
        assertEquals(setOf("org.jetbrains.kotlin.gradle.Public"), classNames)
    }

    @Test
    fun fullIndex_keeps_excluded_packages_and_reports_no_skips() {
        // Filter must be opt-out because KGP ships public API in 'impl' or 'utils'.
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/Public.class" to classBytes("org/jetbrains/kotlin/gradle/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/internal/Hidden.class" to classBytes("org/jetbrains/kotlin/gradle/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/utils/Helper.class" to classBytes("org/jetbrains/kotlin/gradle/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/AndroidThing.class" to classBytes("org/jetbrains/kotlin/gradle/AndroidThing", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val full = KgpDeprecationExtractor.extractIndex(jar.absolutePath, fullIndex = true)
        assertEquals(
            setOf("org.jetbrains.kotlin.gradle.Public", "org.jetbrains.kotlin.gradle.internal.Hidden", "org.jetbrains.kotlin.gradle.utils.Helper", "org.jetbrains.kotlin.gradle.AndroidThing"),
            full.symbols.map { it.className }.toSet(),
        )
        assertEquals(0, full.skippedClasses)
    }

    @Test
    fun reports_how_many_classes_the_package_filter_dropped() {
        // Filtered classes must be reported to avoid silent partial runs.
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/Public.class" to classBytes("org/jetbrains/kotlin/gradle/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/internal/Hidden.class" to classBytes("org/jetbrains/kotlin/gradle/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "org/jetbrains/kotlin/gradle/utils/Helper.class" to classBytes("org/jetbrains/kotlin/gradle/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val filtered = KgpDeprecationExtractor.extractIndex(jar.absolutePath)
        assertEquals(1, filtered.symbols.size)
        assertEquals(2, filtered.skippedClasses)
    }

    @Test
    fun package_scope_drops_bundled_third_party_classes_and_reports_them() {
        // Third-party deprecations (e.g. kotlinx.coroutines 'merge') match common words everywhere.
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/dsl/KotlinCompile.class" to classBytes(
                "org/jetbrains/kotlin/gradle/dsl/KotlinCompile",
                classAnnotation = DeprecationSpec("ERROR", "x", null),
            ),
            "kotlinx/coroutines/flow/FlowKt.class" to classBytes(
                "kotlinx/coroutines/flow/FlowKt",
                classAnnotation = DeprecationSpec("ERROR", "merge", null),
            ),
            "org/gradle/api/Project.class" to classBytes(
                "org/gradle/api/Project",
                classAnnotation = DeprecationSpec("ERROR", "x", null),
            ),
        )

        val scoped = KgpDeprecationExtractor.extractIndex(jar.absolutePath)

        assertEquals(
            setOf("org.jetbrains.kotlin.gradle.dsl.KotlinCompile"),
            scoped.symbols.map { it.className }.toSet(),
        )
        assertEquals(2, scoped.outOfScopeClasses)
    }

    @Test
    fun defaults_to_WARNING_when_level_attribute_missing() {
        // Default to WARNING if level is missing.
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/jetbrains/kotlin/gradle/NoLevel", null, "java/lang/Object", null)
        val av = cw.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "default level")
        av.visitEnd()
        cw.visitEnd()

        val jar = jarOf("org/jetbrains/kotlin/gradle/NoLevel.class" to cw.toByteArray())
        val s = KgpDeprecationExtractor.extract(jar.absolutePath).single()
        assertEquals(DeprecationLevel.WARNING, s.level)
        assertEquals("default level", s.message)
    }

    @Test
    fun strips_dollar_annotations_suffix_from_kotlin_property_synthetic_method() {
        // Kotlin property annotations are on synthetic <name>$annotations method.
        val jar = jarOf(
            "org/jetbrains/kotlin/gradle/PropHolder.class" to classBytes(
                internalName = "org/jetbrains/kotlin/gradle/PropHolder",
                methods = listOf(
                    MethodSpec(
                        name = "getDefaultSourceSetName\$annotations",
                        descriptor = "()V",
                        deprecation = DeprecationSpec("ERROR", "use defaultSourceSet.name", "defaultSourceSet.name"),
                    ),
                ),
            ),
        )

        val s = KgpDeprecationExtractor.extract(jar.absolutePath).single()
        assertEquals("getDefaultSourceSetName", s.memberName)
        assertEquals("defaultSourceSetName", s.searchName)
    }

    @Test
    fun extracts_field_level_deprecation() {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/jetbrains/kotlin/gradle/WithField", null, "java/lang/Object", null)
        val fv = cw.visitField(Opcodes.ACC_PUBLIC, "oldFlag", "Z", null, null)
        val av = fv.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "gone")
        av.visitEnum("level", "Lkotlin/DeprecationLevel;", "ERROR")
        av.visitEnd()
        fv.visitEnd()
        cw.visitEnd()

        val jar = jarOf("org/jetbrains/kotlin/gradle/WithField.class" to cw.toByteArray())
        val s = KgpDeprecationExtractor.extract(jar.absolutePath).single()
        assertEquals("org.jetbrains.kotlin.gradle.WithField", s.className)
        assertEquals("oldFlag", s.memberName)
        assertEquals(DeprecationLevel.ERROR, s.level)
    }

    // --- helpers ---

    private data class DeprecationSpec(val level: String, val message: String, val replaceWith: String?)
    private data class MethodSpec(val name: String, val descriptor: String, val deprecation: DeprecationSpec?)

    private fun classBytes(
        internalName: String,
        classAnnotation: DeprecationSpec? = null,
        methods: List<MethodSpec> = emptyList(),
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        if (classAnnotation != null) {
            writeDeprecation(cw.visitAnnotation("Lkotlin/Deprecated;", true), classAnnotation)
        }
        for (m in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.name, m.descriptor, null, null)
            m.deprecation?.let { writeDeprecation(mv.visitAnnotation("Lkotlin/Deprecated;", true), it) }
            mv.visitCode()
            // Return appropriate value for the descriptor.
            if (m.descriptor.endsWith(")V")) {
                mv.visitInsn(Opcodes.RETURN)
            } else {
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ARETURN)
            }
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeDeprecation(av: AnnotationVisitor, spec: DeprecationSpec) {
        av.visit("message", spec.message)
        av.visitEnum("level", "Lkotlin/DeprecationLevel;", spec.level)
        if (spec.replaceWith != null) {
            val rw = av.visitAnnotation("replaceWith", "Lkotlin/ReplaceWith;")
            rw.visit("expression", spec.replaceWith)
            rw.visitArray("imports").visitEnd()
            rw.visitEnd()
        }
        av.visitEnd()
    }

    private fun jarOf(vararg entries: Pair<String, ByteArray>): File {
        val jar = File.createTempFile("kgp-fake-", ".jar", tmp)
        JarOutputStream(jar.outputStream()).use { jos ->
            for ((path, bytes) in entries) {
                jos.putNextEntry(JarEntry(path))
                jos.write(bytes)
                jos.closeEntry()
            }
        }
        return jar
    }
}
