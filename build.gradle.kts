import java.io.File

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kt/dev")
}

kotlin {
    // Pins toolchain to ensure consistent bytecode across environments.
    jvmToolchain(17)
}

/**
 * Validates -P properties to prevent typos.
 * Gradle silently ignores unknown ones.
 */
val knownProjectProperties = setOf(
    "monorepoDir",
    "buildScan",
    "allowlist",
    "kgpEngineVersion",
    "excludePatterns",
    "reportFile",
    "fullIndex",
)

run {
    // Only validate command-line -P properties (skip namespaced ones).
    val unknown = gradle.startParameter.projectProperties.keys
        .filter { "." !in it && it !in knownProjectProperties }
    if (unknown.isNotEmpty()) {
        val details = unknown.joinToString("\n") { name ->
            val suggestion = knownProjectProperties.minByOrNull { levenshtein(name.lowercase(), it.lowercase()) }
                ?.takeIf { levenshtein(name.lowercase(), it.lowercase()) <= 4 }
            "  -P$name" + (suggestion?.let { " (did you mean -P$it?)" } ?: "")
        }
        throw GradleException(
            "Unknown project ${if (unknown.size == 1) "property" else "properties"}:\n$details\n" +
                "Known properties: ${knownProjectProperties.sorted().joinToString(", ") { "-P$it" }}"
        )
    }
}

/** Levenshtein distance for property name suggestions. */
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

// KGP version to index. Override with -PkgpEngineVersion.
val engineVersion = (findProperty("kgpEngineVersion") as String?) ?: "2.4.10"

dependencies {
    // ASM: reads @Deprecated members from jars without class loading
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(kotlin("test"))
}

// KGP jars for the indexed version
val kgpJars = configurations.create("kgpJars")
dependencies {
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin:$engineVersion")
}


/**
 * Resolves scan root at configuration time to catch invalid paths early.
 */
fun resolveScanRoot(): File {
    val raw = findProperty("monorepoDir")?.toString()?.takeIf { it.isNotBlank() } ?: "test-monorepo"
    val given = File(raw)
    val resolved = if (given.isAbsolute) given else layout.projectDirectory.asFile.resolve(raw)
    if (!resolved.isDirectory) {
        throw GradleException(
            "-PmonorepoDir does not point at a directory:\n  given   : $raw\n  resolved: ${resolved.path}\n" +
                "Pass an existing directory (absolute, or relative to ${layout.projectDirectory.asFile.path})."
        )
    }
    return resolved.canonicalFile
}

/** Main verification task **/
tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans .kt/.java for embedded Gradle scripts using deprecated KGP APIs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    // Surface indexed KGP version in banner
    systemProperty("kgp.engineVersion", engineVersion)
    // Lazy resolution of KGP jars. Must not be a Provider for systemProperty compatibility.
    systemProperty("kgp.pluginJars", kgpJars.asPath)
    // Verification must always run
    outputs.upToDateWhen { false }
    findProperty("excludePatterns")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.excludePatterns", it) }
    // -PfullIndex includes internal/Android packages
    findProperty("fullIndex")?.let {
        systemProperty("kgp.fullIndex", it.toString().takeIf { v -> v.isNotBlank() } ?: "true")
    }

    // Mirror output to file if -PreportFile is set
    val reportFile = findProperty("reportFile")?.toString()?.takeIf { it.isNotBlank() }
        ?: layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path
    systemProperty("kgp.reportFile", reportFile)

    val monorepo = resolveScanRoot().path
    val allowlistArg = findProperty("allowlist")?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)

    // Gradle turns any non-zero exit into its own generic failure, hiding the
    // tool's 1 (findings) vs 2 (setup failure) distinction. Inspect it ourselves.
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        when (val code = result.get().exitValue) {
            0 -> Unit
            1 -> throw GradleException(
                "KGP deprecation check FAILED: deprecated API usages found. See the report above."
            )
            2 -> throw GradleException(
                "KGP deprecation check DID NOT RUN (setup failure, exit 2). " +
                    "The check produced no verdict - fix the invocation and re-run."
            )
            else -> throw GradleException("KGP deprecation check exited unexpectedly with code $code.")
        }
    }
}
