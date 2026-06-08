package org.jetbrains.kotlin.deprecations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end test through the real Gradle Tooling API. Opt-in: it only runs when
 * `KGP_IT_PROJECT` points at a buildable Gradle project whose `build.gradle.kts` uses a
 * WARNING-level deprecated KGP API. Optionally set `KGP_IT_GRADLE` to a Gradle installation
 * dir (otherwise the project's own wrapper is used). Skipped silently in CI without these.
 *
 * Example:
 *   KGP_IT_PROJECT=/tmp/kmp-test KGP_IT_GRADLE=/path/to/gradle-9.4.0 ./gradlew test
 */
class GradleIntegrationTest {

    @Test
    fun resolvesDeprecationThroughGradleClasspath() {
        val projectDir = System.getenv("KGP_IT_PROJECT")?.let(::File)
        if (projectDir == null || !File(projectDir, "build.gradle.kts").exists()) {
            println("SKIP GradleIntegrationTest: set KGP_IT_PROJECT to a buildable Gradle project")
            return
        }
        val script = File(projectDir, "build.gradle.kts")
        val gradleInstallation = System.getenv("KGP_IT_GRADLE")?.let(::File)

        val model = GradleScriptModelProvider.fetch(projectDir, script, gradleInstallation)
        check(model is ScriptModelResult.Resolved) { "model fetch failed: $model" }

        val findings = KgpDeprecationAnalyzer().analyze(script, model.classPath, model.implicitImports)
        println("integration findings: $findings")
        assertTrue(findings.isNotEmpty(), "expected at least one deprecation finding in $script")
    }
}
