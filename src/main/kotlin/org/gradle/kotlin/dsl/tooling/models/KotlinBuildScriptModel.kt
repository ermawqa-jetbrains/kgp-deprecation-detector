package org.gradle.kotlin.dsl.tooling.models

import java.io.File

/**
 * Client-side copy of Gradle's Kotlin DSL tooling model. The Gradle Tooling API
 * adapts the provider's model to this interface by its fully-qualified name and
 * matches getters by name, so we do not need to depend on the (unpublished)
 * `gradle-kotlin-dsl-tooling-models` jar — only the package + FQN must match.
 *
 * `classPath` includes the per-project generated accessor classes (the source of
 * `kotlin { }`, `compilations`, etc.); `implicitImports` are the default imports
 * Gradle applies to every build script.
 */
interface KotlinBuildScriptModel {
    val classPath: List<File>
    val sourcePath: List<File>
    val implicitImports: List<String>
}
