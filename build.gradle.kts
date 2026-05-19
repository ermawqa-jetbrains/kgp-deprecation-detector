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

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    testImplementation(kotlin("test"))
}

fun resolveKgpJar(version: String): String {
    val dep = dependencies.create("org.jetbrains.kotlin:kotlin-gradle-plugin:$version")
    val config = configurations.detachedConfiguration(dep).apply { isTransitive = false }
    return config.resolvedConfiguration.resolvedArtifacts
        .first { it.name == "kotlin-gradle-plugin" }
        .file.absolutePath
}

tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans Gradle files for deprecated KGP API usages. " +
        "Pass -PkgpVersion=<ver> (or -PkgpJar=<path>); optional -PmonorepoDir=<path> (defaults to test-monorepo) " +
        "and -Pallowlist=<path>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")

    val kgpJarPath = when {
        project.hasProperty("kgpJar") -> project.property("kgpJar").toString()
        project.hasProperty("kgpVersion") -> resolveKgpJar(project.property("kgpVersion").toString())
        else -> ""
    }
    val monorepo = project.properties["monorepoDir"]?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    val allowlistArg = project.properties["allowlist"]?.toString().orEmpty()
    args = listOf(kgpJarPath, monorepo, allowlistArg)
}
