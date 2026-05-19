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

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    testImplementation(kotlin("test"))
}

// KGP ships its public interfaces in `kotlin-gradle-plugin-api`, while the
// implementation lives in `kotlin-gradle-plugin`. Many deprecated public APIs
// (e.g. `KotlinCompilation.defaultSourceSetName`) are annotated only in the
// `-api` jar, so both jars must be inspected.
fun resolveKgpJars(version: String): List<String> {
    val coords = listOf(
        "org.jetbrains.kotlin:kotlin-gradle-plugin:$version",
        "org.jetbrains.kotlin:kotlin-gradle-plugin-api:$version",
    )
    return coords.map { coord ->
        val dep = dependencies.create(coord)
        val config = configurations.detachedConfiguration(dep).apply { isTransitive = false }
        val artifactName = coord.substringAfter(':').substringBeforeLast(':')
        config.resolvedConfiguration.resolvedArtifacts
            .first { it.name == artifactName }
            .file.absolutePath
    }
}

tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans Gradle files for deprecated KGP API usages. " +
        "Pass -PkgpVersion=<ver> (or -PkgpJar=<path1${File.pathSeparator}path2…>); " +
        "optional -PmonorepoDir=<path> (defaults to test-monorepo) and -Pallowlist=<path>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")

    val kgpJarArg: String = when {
        project.hasProperty("kgpJar") -> project.property("kgpJar").toString()
        project.hasProperty("kgpVersion") ->
            resolveKgpJars(project.property("kgpVersion").toString())
                .joinToString(File.pathSeparator)
        else -> ""
    }
    val monorepo = project.properties["monorepoDir"]?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    val allowlistArg = project.properties["allowlist"]?.toString().orEmpty()
    args = listOf(kgpJarArg, monorepo, allowlistArg)
}
