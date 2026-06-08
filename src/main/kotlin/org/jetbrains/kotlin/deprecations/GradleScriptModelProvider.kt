package org.jetbrains.kotlin.deprecations

import org.gradle.kotlin.dsl.tooling.models.KotlinBuildScriptModel
import org.gradle.tooling.GradleConnector
import java.io.File

/** Per-script classpath + imports obtained from Gradle, or the reason it could not be obtained. */
sealed interface ScriptModelResult {
    data class Resolved(
        val classPath: List<File>,
        val implicitImports: List<String>,
    ) : ScriptModelResult

    /**
     * Gradle could not produce the model — typically because the script itself fails to
     * compile (e.g. it already uses an ERROR-level deprecated API, which breaks Gradle's
     * own script compilation). The message usually carries the deprecation text + location.
     */
    data class Failed(val message: String) : ScriptModelResult
}

/**
 * Fetches the [KotlinBuildScriptModel] for one build script via the Gradle Tooling API,
 * using the project's own Gradle distribution (its wrapper). This is the same mechanism
 * IntelliJ uses to resolve build scripts, so the returned classpath contains the
 * per-project generated accessors that a static classpath cannot provide.
 */
object GradleScriptModelProvider {
    // The Kotlin DSL provider reads this system property to decide which script to model.
    private const val SCRIPT_PROPERTY = "org.gradle.kotlin.dsl.provider.script"

    fun fetch(projectDir: File, script: File, gradleInstallation: File? = null): ScriptModelResult =
        try {
            val connector = GradleConnector.newConnector().forProjectDirectory(projectDir)
            if (gradleInstallation != null) connector.useInstallation(gradleInstallation)
            connector.connect().use { connection ->
                val model = connection.model(KotlinBuildScriptModel::class.java)
                    .withArguments("-D$SCRIPT_PROPERTY=${script.absolutePath}")
                    .get()
                ScriptModelResult.Resolved(model.classPath, model.implicitImports)
            }
        } catch (e: Exception) {
            ScriptModelResult.Failed(e.message ?: e.toString())
        }
}
