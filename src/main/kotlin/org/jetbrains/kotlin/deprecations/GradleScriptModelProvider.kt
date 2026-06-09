package org.jetbrains.kotlin.deprecations

import org.gradle.kotlin.dsl.tooling.models.KotlinBuildScriptModel
import org.gradle.tooling.GradleConnector
import java.io.File

/** Per-script classpath + imports obtained from Gradle, or the reason it could not be obtained. */
sealed interface ScriptModelResult {
    data class Resolved(
        val classPath: List<File>,
        val implicitImports: List<String>,
        /** KGP version this script resolves against, parsed from the classpath (null if absent). */
        val kgpVersion: String?,
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
                ScriptModelResult.Resolved(model.classPath, model.implicitImports, kgpVersionOf(model.classPath))
            }
        } catch (e: Exception) {
            ScriptModelResult.Failed(e.message ?: e.toString())
        }

    // Matches e.g. kotlin-gradle-plugin-2.2.20-gradle813.jar or
    // kotlin-gradle-plugin-api-2.4.0-dev-8644-gradle813.jar -> version is the capture group.
    private val KGP_JAR = Regex("""kotlin-gradle-plugin(?:-api)?-(\d.+?)(?:-gradle\d+)?\.jar""")

    private fun kgpVersionOf(classPath: List<File>): String? =
        classPath.firstNotNullOfOrNull { KGP_JAR.find(it.name)?.groupValues?.get(1) }
}
