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

class KgpDeprecationExtractorTest {

    private val tmp: File = createTempDirectory("kgp-extract-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun extracts_class_level_deprecation() {
        val jar = jarOf(
            "foo/OldClass.class" to classBytes(
                internalName = "foo/OldClass",
                classAnnotation = DeprecationSpec("ERROR", "removed", "NewClass"),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)

        assertEquals(1, symbols.size)
        val s = symbols.single()
        assertEquals("foo.OldClass", s.className)
        assertNull(s.memberName)
        assertEquals(DeprecationLevel.ERROR, s.level)
        assertEquals("removed", s.message)
        assertEquals("NewClass", s.replaceWith)
    }

    @Test
    fun extracts_method_level_deprecation_with_replaceWith() {
        val jar = jarOf(
            "foo/KotlinCompilation.class" to classBytes(
                internalName = "foo/KotlinCompilation",
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
        assertEquals("foo.KotlinCompilation", s.className)
        assertEquals("getDefaultSourceSetName", s.memberName)
        assertEquals(DeprecationLevel.ERROR, s.level)
        assertEquals("defaultSourceSet.name", s.replaceWith)
        // Kotlin property name derived from JVM getter:
        assertEquals("defaultSourceSetName", s.searchName)
    }

    @Test
    fun extracts_all_deprecation_levels() {
        val jar = jarOf(
            "foo/MultiLevel.class" to classBytes(
                internalName = "foo/MultiLevel",
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
            "foo/Plain.class" to classBytes(
                internalName = "foo/Plain",
                methods = listOf(MethodSpec("doX", "()V", null)),
            ),
        )

        val symbols = KgpDeprecationExtractor.extract(jar.absolutePath)
        assertTrue(symbols.isEmpty())
    }

    @Test
    fun excludes_internal_utils_impl_packages_and_android_classes() {
        val jar = jarOf(
            "foo/Public.class" to classBytes("foo/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            // Exclude both top-level and nested internal packages.
            "foo/internal/Hidden.class" to classBytes("foo/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/internal/sub/Hidden.class" to classBytes("foo/internal/sub/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/utils/Helper.class" to classBytes("foo/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/impl/Concrete.class" to classBytes("foo/impl/Concrete", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/AndroidThing.class" to classBytes("foo/AndroidThing", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val classNames = KgpDeprecationExtractor.extract(jar.absolutePath).map { it.className }.toSet()
        assertEquals(setOf("foo.Public"), classNames)
    }

    @Test
    fun fullIndex_keeps_excluded_packages_and_reports_no_skips() {
        // Filter must be opt-out because KGP ships public API in 'impl' or 'utils'.
        val jar = jarOf(
            "foo/Public.class" to classBytes("foo/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/internal/Hidden.class" to classBytes("foo/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/utils/Helper.class" to classBytes("foo/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/AndroidThing.class" to classBytes("foo/AndroidThing", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val full = KgpDeprecationExtractor.extractIndex(jar.absolutePath, fullIndex = true)
        assertEquals(
            setOf("foo.Public", "foo.internal.Hidden", "foo.utils.Helper", "foo.AndroidThing"),
            full.symbols.map { it.className }.toSet(),
        )
        assertEquals(0, full.skippedClasses)
    }

    @Test
    fun reports_how_many_classes_the_package_filter_dropped() {
        // Filtered classes must be reported to avoid silent partial runs.
        val jar = jarOf(
            "foo/Public.class" to classBytes("foo/Public", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/internal/Hidden.class" to classBytes("foo/internal/Hidden", classAnnotation = DeprecationSpec("ERROR", "x", null)),
            "foo/utils/Helper.class" to classBytes("foo/utils/Helper", classAnnotation = DeprecationSpec("ERROR", "x", null)),
        )

        val filtered = KgpDeprecationExtractor.extractIndex(jar.absolutePath)
        assertEquals(1, filtered.symbols.size)
        assertEquals(2, filtered.skippedClasses)
    }

    @Test
    fun defaults_to_WARNING_when_level_attribute_missing() {
        // Default to WARNING if level is missing.
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "foo/NoLevel", null, "java/lang/Object", null)
        val av = cw.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "default level")
        av.visitEnd()
        cw.visitEnd()

        val jar = jarOf("foo/NoLevel.class" to cw.toByteArray())
        val s = KgpDeprecationExtractor.extract(jar.absolutePath).single()
        assertEquals(DeprecationLevel.WARNING, s.level)
        assertEquals("default level", s.message)
    }

    @Test
    fun strips_dollar_annotations_suffix_from_kotlin_property_synthetic_method() {
        // Kotlin property annotations are on synthetic <name>$annotations method.
        val jar = jarOf(
            "foo/PropHolder.class" to classBytes(
                internalName = "foo/PropHolder",
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
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "foo/WithField", null, "java/lang/Object", null)
        val fv = cw.visitField(Opcodes.ACC_PUBLIC, "oldFlag", "Z", null, null)
        val av = fv.visitAnnotation("Lkotlin/Deprecated;", true)
        av.visit("message", "gone")
        av.visitEnum("level", "Lkotlin/DeprecationLevel;", "ERROR")
        av.visitEnd()
        fv.visitEnd()
        cw.visitEnd()

        val jar = jarOf("foo/WithField.class" to cw.toByteArray())
        val s = KgpDeprecationExtractor.extract(jar.absolutePath).single()
        assertEquals("foo.WithField", s.className)
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
