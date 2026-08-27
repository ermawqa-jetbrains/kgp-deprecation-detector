package org.jetbrains.kotlin.deprecations

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.jar.JarFile

/**
 * Reads every `@Deprecated` (Kotlin) declaration out of a KGP jar via ASM - without
 * loading the classes. Produces the deprecated-API index that [EmbeddedScriptScanner] matches
 * against by name, for embedded Gradle scripts that are never compiled.
 */
object KgpDeprecationExtractor {

    /** Index of one jar plus how many classes [isExcluded] kept out of it (0 when `fullIndex`). */
    data class JarIndex(val symbols: List<DeprecatedSymbol>, val skippedClasses: Int)

    fun extract(jarPath: String, fullIndex: Boolean = false): List<DeprecatedSymbol> =
        extractIndex(jarPath, fullIndex).symbols

    /**
     * Same as [extract], but also reports how many classes the [isExcluded] package filter
     * dropped - that filter shrinks the search space silently, so the count is surfaced in the
     * banner and can be disabled entirely with `fullIndex`.
     */
    fun extractIndex(jarPath: String, fullIndex: Boolean = false): JarIndex {
        val results = mutableListOf<DeprecatedSymbol>()
        var skipped = 0

        JarFile(jarPath).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .filter { entry ->
                    val excluded = !fullIndex && entry.name.isExcluded()
                    if (excluded) skipped++
                    !excluded
                }
                .forEach { entry ->
                    jar.getInputStream(entry).use { input ->
                        val reader = ClassReader(input)
                        val visitor = DeprecationClassVisitor(entry.name) { results.add(it) }
                        reader.accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
                    }
                }
        }

        return JarIndex(results, skipped)
    }
}

private class DeprecationClassVisitor(
    entryName: String,
    private val onDeprecated: (DeprecatedSymbol) -> Unit,
) : ClassVisitor(Opcodes.ASM9) {

    private val className = entryName.removeSuffix(".class").replace('/', '.')

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (descriptor == KOTLIN_DEPRECATED_DESC) {
            return DeprecationAnnotationVisitor { level, message, replaceWith ->
                onDeprecated(DeprecatedSymbol(className, null, null, level, message, replaceWith))
            }
        }
        return null
    }

    override fun visitMethod(
        access: Int, name: String, descriptor: String,
        signature: String?, exceptions: Array<out String>?,
    ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(annDescriptor: String, visible: Boolean): AnnotationVisitor? {
            if (annDescriptor == KOTLIN_DEPRECATED_DESC) {
                // Kotlin emits property-level @Deprecated on a synthetic
                // `<name>$annotations` method, not on the real getter. Strip
                // the suffix so the recorded memberName matches the JVM
                // accessor that user code actually invokes
                val canonicalName = name.removeSuffix("\$annotations")
                return DeprecationAnnotationVisitor { level, message, replaceWith ->
                    onDeprecated(DeprecatedSymbol(className, canonicalName, descriptor, level, message, replaceWith))
                }
            }
            return null
        }
    }

    override fun visitField(
        access: Int, name: String, descriptor: String,
        signature: String?, value: Any?,
    ): FieldVisitor = object : FieldVisitor(Opcodes.ASM9) {
        override fun visitAnnotation(annDescriptor: String, visible: Boolean): AnnotationVisitor? {
            if (annDescriptor == KOTLIN_DEPRECATED_DESC) {
                return DeprecationAnnotationVisitor { level, message, replaceWith ->
                    onDeprecated(DeprecatedSymbol(className, name, descriptor, level, message, replaceWith))
                }
            }
            return null
        }
    }
}

private class DeprecationAnnotationVisitor(
    private val onComplete: (DeprecationLevel, String, String?) -> Unit,
) : AnnotationVisitor(Opcodes.ASM9) {

    private var level = DeprecationLevel.WARNING
    private var message = ""
    private var replaceWith: String? = null

    override fun visit(name: String?, value: Any?) {
        if (name == "message") message = value as? String ?: ""
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        if (name == "level" && descriptor == KOTLIN_DEPRECATION_LEVEL_DESC) {
            level = when (value) {
                "ERROR" -> DeprecationLevel.ERROR
                "HIDDEN" -> DeprecationLevel.HIDDEN
                else -> DeprecationLevel.WARNING
            }
        }
    }

    override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor? {
        if (name == "replaceWith" && descriptor == KOTLIN_REPLACE_WITH_DESC) {
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(n: String?, value: Any?) {
                    if (n == "expression") replaceWith = value as? String
                }
            }
        }
        return null
    }

    override fun visitEnd() = onComplete(level, message, replaceWith)
}

// Сlasses assumed never to be used directly in Gradle build files. Aggressive by nature - KGP
// ships public API in packages containing `impl`/`utils`, and an `Android*` symbol is exactly
// what an AGP-injected init script may touch - so it is opt-out via `fullIndex` and the number
// of classes it drops is reported rather than hidden.
private fun String.isExcluded(): Boolean {
    val segments = substringBeforeLast('/').split('/')
    val simpleName = substringAfterLast('/').removeSuffix(".class")
    return "internal" in segments ||
        "utils" in segments ||
        "impl" in segments ||
        simpleName.contains("Android")
}

private const val KOTLIN_DEPRECATED_DESC = "Lkotlin/Deprecated;"
private const val KOTLIN_DEPRECATION_LEVEL_DESC = "Lkotlin/DeprecationLevel;"
private const val KOTLIN_REPLACE_WITH_DESC = "Lkotlin/ReplaceWith;"
