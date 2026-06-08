package org.jetbrains.kotlin.deprecations

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.configurationDependencies
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/**
 * Resolves a single `.gradle.kts` against a Gradle-provided classpath and reports
 * deprecated-API usages as the compiler's own DEPRECATION diagnostics.
 *
 * The script is compiled the way Gradle compiles it — an implicit `Project` receiver
 * plus the sam-with-receiver plugin — so implicit accessor chains like
 * `kotlin { jvm { withJava() } }` bind to their real declarations. Every reported
 * finding is therefore compiler-verified: there are no false positives from
 * same-named symbols. Scope is every deprecation the compiler resolves in the script
 * body (overwhelmingly KGP in Kotlin build scripts), not only KGP-package symbols.
 */
class KgpDeprecationAnalyzer(
    private val samPluginJar: File = locateSamPluginJar(),
) {
    fun analyze(
        script: File,
        classPath: List<File>,
        implicitImports: List<String>,
        receiverClass: String = "org.gradle.api.Project",
    ): List<Finding> {
        val configuration = ScriptCompilationConfiguration {
            implicitReceivers(KotlinType(receiverClass))
            defaultImports.append(implicitImports)
            jvm { updateClasspath(classPath) }
            compilerOptions.append(
                "-Xplugin=${samPluginJar.absolutePath}",
                "-P", "plugin:org.jetbrains.kotlin.samWithReceiver:annotation=org.gradle.api.HasImplicitReceiver",
            )
        }

        // Gradle compiles the `plugins { }` block in a separate stage; blank it (preserving
        // line numbers) so it does not resolve to the deprecated `Project.plugins(): Nothing`.
        val source = stripPluginsBlock(script.readText()).toScriptSource(script.name)

        // The host loads the implicit-receiver class (org.gradle.api.Project) by building a
        // classloader from configurationDependencies, so the script's classpath must be set here
        // as well as on the compilation config.
        val host = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            configurationDependencies(JvmDependency(classPath))
        }
        val compiler = JvmScriptCompiler(host)
        val result = runBlocking { compiler(source, configuration) }
        return result.reports.mapNotNull { it.toFindingOrNull(script) }
    }

    private fun ScriptDiagnostic.toFindingOrNull(script: File): Finding? {
        val level = when (severity) {
            ScriptDiagnostic.Severity.ERROR, ScriptDiagnostic.Severity.FATAL -> DeprecationLevel.ERROR
            ScriptDiagnostic.Severity.WARNING -> DeprecationLevel.WARNING
            else -> return null
        }
        if (!message.contains("is deprecated", ignoreCase = true)) return null
        val loc: SourceCode.Location = location ?: return null
        return Finding(
            file = script.path,
            line = loc.start.line,
            column = loc.start.col,
            symbol = extractSymbol(message),
            level = level,
            message = message.substringAfter("is deprecated.", "").trim().ifBlank { message },
        )
    }

    companion object {
        /** Pulls the leading `'…'`-quoted declaration signature out of the diagnostic message. */
        private fun extractSymbol(message: String): String {
            val start = message.indexOf('\'')
            val end = message.indexOf('\'', start + 1)
            return if (start in 0 until end) message.substring(start + 1, end) else message.substringBefore('.')
        }

        /** Blanks the leading top-level `plugins { … }` block, preserving newlines (line numbers). */
        internal fun stripPluginsBlock(text: String): String {
            val match = Regex("(?m)^plugins\\s*\\{").find(text) ?: return text
            val open = text.indexOf('{', match.range.first)
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) break
                }
                i++
            }
            if (depth != 0) return text
            val sb = StringBuilder(text)
            for (j in match.range.first..i) {
                if (sb[j] != '\n') sb.setCharAt(j, ' ')
            }
            return sb.toString()
        }

        private fun locateSamPluginJar(): File =
            System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .map(::File)
                .firstOrNull { it.name.contains("sam-with-receiver") }
                ?: error("kotlin-sam-with-receiver compiler plugin jar not found on the classpath")
    }
}
