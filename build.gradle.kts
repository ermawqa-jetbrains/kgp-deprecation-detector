import java.net.URI

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// 1. Inputs

val defaultKgpVersion = "2.4.21"
val defaultScanRoot = "test-monorepo"

val knownProjectProperties = setOf(
    "monorepoDir",
    "kgpEngineVersion",
    "localRepoDir",
    "excludePatterns",
    "targetSymbols",
)

/** Non-blank value of `-P<name>`, if given. */
fun optionalProperty(name: String): Provider<String> =
    providers.gradleProperty(name).map { it.trim() }.filter { it.isNotEmpty() }

// Fail fast on typos like -PmonorepoDirr instead of silently scanning the default root.
gradle.startParameter.projectProperties.keys
    .filter { '.' !in it && it !in knownProjectProperties }
    .takeIf { it.isNotEmpty() }
    ?.let { unknown ->
        val details = unknown.joinToString("\n") { name ->
            "  -P$name" + (closestKnownProperty(name)?.let { " (did you mean -P$it?)" } ?: "")
        }
        throw GradleException(
            "Unknown project ${if (unknown.size == 1) "property" else "properties"}:\n$details\n" +
                "Known properties: ${knownProjectProperties.sorted().joinToString(", ") { "-P$it" }}",
        )
    }

fun closestKnownProperty(name: String): String? =
    knownProjectProperties
        .map { it to levenshtein(name.lowercase(), it.lowercase()) }
        .minByOrNull { (_, distance) -> distance }
        ?.takeIf { (_, distance) -> distance <= 4 }
        ?.first

fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitute)
        }
        previous = current
    }
    return previous[b.length]
}

// 2. Repositories

val localRepoDir = optionalProperty("localRepoDir").orNull
val engineVersion = optionalProperty("kgpEngineVersion").getOrElse(defaultKgpVersion)
val engineSource = if (localRepoDir != null) "TeamCity Artifacts ($localRepoDir)" else "Maven (Central / kt-dev)"

repositories {
    if (localRepoDir != null) {
        maven {
            name = "localArtifactsRepo"
            setUrl(File(localRepoDir).toURI())
        }
    }
    maven {
        name = "kotlinDevRepo"
        setUrl("https://packages.jetbrains.team/maven/p/kt/dev")
        // Only KGP/Kotlin artifacts live here; keeps other dependencies off this repo.
        content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..+)?") }
    }
    // Cache-redirector mirror, declared last so the Kotlin-specific repos are tried first.
    mavenCentral {
        url = URI("https://cache-redirector.jetbrains.com/maven-central")
    }
}

// 3. Dependencies

// The KGP jars are scan input (indexed via ASM), not part of the detector's own classpath.
val kgpJars by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    implementation("org.ow2.asm:asm:9.10.1")
    testImplementation(kotlin("test"))
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin:$engineVersion")
}

// 4. Verification task

tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans .kt/.java for embedded Gradle scripts using deprecated KGP APIs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    outputs.upToDateWhen { false }

    val scanRoot = projectDir.resolve(optionalProperty("monorepoDir").getOrElse(defaultScanRoot))
    if (!scanRoot.isDirectory) {
        throw GradleException("-PmonorepoDir does not point at a directory: ${scanRoot.path}")
    }
    val allowlist = layout.projectDirectory.file("config/allowlist-intellij.txt").asFile
    args = listOfNotNull(scanRoot.canonicalPath, allowlist.takeIf { it.exists() }?.path)

    systemProperty("kgp.engineVersion", engineVersion)
    systemProperty("kgp.engineSource", engineSource)
    // Resolved eagerly on purpose: JavaExec.systemProperty() does not unwrap a Provider.
    systemProperty("kgp.pluginJars", kgpJars.asPath)
    systemProperty("kgp.reportFile", layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path)
    optionalProperty("excludePatterns").orNull?.let { systemProperty("kgp.excludePatterns", it) }
    optionalProperty("targetSymbols").orNull?.let { systemProperty("kgp.targetSymbols", it) }

    val isTeamCity = providers.environmentVariable("TEAMCITY_VERSION")
        .orElse(providers.gradleProperty("teamcity.version"))
        .isPresent
    if (isTeamCity) {
        systemProperty("teamcity.version", "true")
    }

    // Exit code 1 = findings, 2 = setup failure; handled below instead of Gradle's generic failure.
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        val failure = when (val code = result.get().exitValue) {
            0 -> null
            // On TeamCity, Main.kt already emitted ##teamcity[buildProblem]; finishing cleanly fails the
            // build without a duplicate problem and a 100-line Tooling API stack trace.
            1 -> "KGP deprecation check FAILED: deprecated API usages found. See the report above.".takeUnless { isTeamCity }
            2 -> "KGP deprecation check DID NOT RUN (setup failure, exit 2). Fix the invocation and re-run."
            else -> "KGP deprecation check exited unexpectedly with code $code."
        }
        if (failure != null) throw GradleException(failure)
    }
}
