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
}

// KGP version whose @Deprecated API set is indexed for name-matching.
// override with -PkgpEngineVersion=<ver> to match the target monorepo's KGP version
val engineVersion = (findProperty("kgpEngineVersion") as String?) ?: "2.4.0"

dependencies {
    // ASM: reads @Deprecated members out of the KGP jars (no class loading) to build the name index
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(kotlin("test"))
}

// KGP jars for the indexed engine version
val kgpJars by configurations.creating
dependencies {
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin:$engineVersion")
}

/** Main task that checks for deprecation **/
tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans .kt/.java under -PmonorepoDir (default test-monorepo) for embedded " +
        "Gradle scripts using deprecated KGP APIs. Optional -Pallowlist=<file>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    // Surface the indexed KGP version in the tool's banner
    systemProperty("kgp.engineVersion", engineVersion)
    systemProperty("kgp.pluginJars", kgpJars.files.joinToString(File.pathSeparator))
    project.properties["excludePatterns"]?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.excludePatterns", it) }

    // mirrors terminal output into a report file
    // override the path with -PreportFile=<path>
    val reportFile = project.properties["reportFile"]?.toString()?.takeIf { it.isNotBlank() }
        ?: layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path
    systemProperty("kgp.reportFile", reportFile)

    // identify monorepo
    val monorepo = project.properties["monorepoDir"]?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    //identify allowlist
    val allowlistArg = project.properties["allowlist"]?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)
}

// secondary task to print out all deprecated APIs from KGP JAR
tasks.register<JavaExec>("printKgpDeprecations") {
    group = "verification"
    description = "extracts & prints all deprecated APIs from given KGP jar"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.PrintDeprecationsKt")
    systemProperty("kgp.engineVersion", engineVersion)
    systemProperty("kgp.pluginJars", kgpJars.files.joinToString(File.pathSeparator))
}
