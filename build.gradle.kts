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
    // The two compiler plugins Gradle applies to .gradle.kts; both are passed to the script
    // compile via -Xplugin (jars located on the runtime classpath):
    //  - sam-with-receiver: Action<T> accessor params become T.() -> Unit receivers
    //    (makes `kotlin { jvm { … } }` resolve);
    //  - assignment: enables lazy-property assignment (`jvmTarget = JvmTarget.JVM_11`).
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable:$engineVersion")
    implementation("org.jetbrains.kotlin:kotlin-assignment-compiler-plugin-embeddable:$engineVersion")

    // Gradle Tooling API: fetches each script's real classpath (incl. generated accessors)
    // and implicit imports via the KotlinBuildScriptModel tooling model.
    implementation("org.gradle:gradle-tooling-api:9.4.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.13")

    // ASM: reads @Deprecated members out of the KGP jars (no class loading) to build the
    // name index for the Groovy heuristic pass. Groovy is dynamically typed and cannot be
    // resolved by a frontend, so that pass is name-matching, not resolution.
    implementation("org.ow2.asm:asm:9.7")

    testImplementation(kotlin("test"))
}

// The Groovy heuristic pass needs the actual KGP jars (for the engine version) to know which
// API names are deprecated. Resolve them as a separate configuration and hand their paths to
// the tool; mavenCentral + kt/dev (above) cover stable and dev versions.
val kgpJars by configurations.creating
dependencies {
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin:$engineVersion")
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin-api:$engineVersion")
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

    // Groovy heuristic pass: KGP jars for the name index; on by default, non-gating by default.
    systemProperty("kgp.pluginJars", kgpJars.files.joinToString(File.pathSeparator))
    if (project.findProperty("scanGroovy") == "false") systemProperty("kgp.scanGroovy", "false")
    if (project.hasProperty("groovyGating")) systemProperty("kgp.groovyGating", "true")
    project.properties["groovyScanRoot"]?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.groovyScanRoot", it) }

    val monorepo = project.properties["monorepoDir"]?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    val allowlistArg = project.properties["allowlist"]?.toString().orEmpty()
    // Default to the Gradle running this build, so the task works against the bundled
    // test-monorepo fixture with no extra flags and no wrapper in the fixture.
    val gradleInstallationArg = project.properties["gradleInstallation"]?.toString()?.takeIf { it.isNotBlank() }
        ?: gradle.gradleHomeDir?.absolutePath.orEmpty()
    args = listOf(monorepo, allowlistArg, gradleInstallationArg)
}
