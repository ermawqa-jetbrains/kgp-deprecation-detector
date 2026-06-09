import java.io.File

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kt/dev")
    // Gradle's shaded Tooling API client lives in Gradle's own repository.
    maven("https://repo.gradle.org/gradle/libs-releases")
}

// The analysis engine compiles each build script with the same Kotlin frontend +
// compiler plugins Gradle uses. Its version MUST be >= the KGP metadata version in
// the scanned monorepo, otherwise KGP classes "compiled with a newer Kotlin" cannot
// be read. Override with -PkgpEngineVersion=<ver> to match the target monorepo.
val engineVersion = (findProperty("kgpEngineVersion") as String?) ?: "2.4.0"

dependencies {
    // Kotlin scripting host: compiles each .gradle.kts the way Gradle does and surfaces
    // the compiler's own DEPRECATION diagnostics (message + exact location).
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$engineVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$engineVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$engineVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // sam-with-receiver compiler plugin: rewrites Action<T> accessor params into
    // T.() -> Unit receivers, which is what makes `kotlin { jvm { … } }` resolve.
    // Passed to the script compile via -Xplugin; the jar is located on the runtime classpath.
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable:$engineVersion")

    // Gradle Tooling API: fetches each script's real classpath (incl. generated accessors)
    // and implicit imports via the KotlinBuildScriptModel tooling model.
    implementation("org.gradle:gradle-tooling-api:9.4.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
}

// Each script's KGP classpath is obtained from Gradle itself (the KotlinBuildScriptModel
// tooling model), so the tool no longer resolves KGP jars — it only needs the monorepo to
// scan. The analysis engine version is fixed at build time via -PkgpEngineVersion (see above);
// it must be >= the KGP version used in the scanned monorepo.
tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Resolves every .gradle.kts under -PmonorepoDir (default test-monorepo) and " +
        "reports deprecated API usages. Optional -Pallowlist=<file>, -PgradleInstallation=<dir>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    // Surface the analysis engine version in the tool's banner.
    systemProperty("kgp.engineVersion", engineVersion)
    // -PallowUnresolved downgrades unanalysable scripts from a failure to a warning.
    if (project.hasProperty("allowUnresolved")) systemProperty("kgp.allowUnresolved", "true")

    val monorepo = project.properties["monorepoDir"]?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    val allowlistArg = project.properties["allowlist"]?.toString().orEmpty()
    // Default to the Gradle running this build, so the task works against the bundled
    // test-monorepo fixture with no extra flags and no wrapper in the fixture.
    val gradleInstallationArg = project.properties["gradleInstallation"]?.toString()?.takeIf { it.isNotBlank() }
        ?: gradle.gradleHomeDir?.absolutePath.orEmpty()
    args = listOf(monorepo, allowlistArg, gradleInstallationArg)
}
