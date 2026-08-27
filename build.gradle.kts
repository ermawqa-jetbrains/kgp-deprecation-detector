import java.io.File

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kt/dev")
}

/**
 * Project properties this build understands. Gradle silently accepts any `-P<name>=<value>`,
 * so a typo (`-PmnorepoDir=...`) would fall back to the default scan root and produce a green
 * build against the bundled fixture - the same silent-success failure mode the tool's exit
 * code 2 exists to prevent. Fail the build instead.
 */
val knownProjectProperties = setOf(
    "monorepoDir",
    "allowlist",
    "kgpEngineVersion",
    "excludePatterns",
    "reportFile",
    "fullIndex",
)

run {
    // Only command-line `-P` properties are validated; namespaced ones (org.gradle.*, kotlin.*,
    // systemProp.*) belong to Gradle/plugins and are left alone.
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

/** Plain Levenshtein distance, used only to suggest the closest known property name. */
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

// KGP version whose @Deprecated API set is indexed for name-matching
// override with -PkgpEngineVersion=<ver> to match the target monorepo's KGP version
val engineVersion = (findProperty("kgpEngineVersion") as String?) ?: "2.4.0"

dependencies {
    // ASM: reads @Deprecated members out of the KGP jars (no class loading) to build the name index
    implementation("org.ow2.asm:asm:9.10.1")

    testImplementation(kotlin("test"))
}

// KGP jars for the indexed engine version
val kgpJars = configurations.create("kgpJars")
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
    findProperty("excludePatterns")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.excludePatterns", it) }
    // -PfullIndex (value optional; `-PfullIndex` alone means true) keeps the internal/utils/impl/
    // Android classes in the deprecation index instead of filtering them out
    findProperty("fullIndex")?.let {
        systemProperty("kgp.fullIndex", it.toString().takeIf { v -> v.isNotBlank() } ?: "true")
    }

    // mirrors terminal output into a report file
    // override the path with -PreportFile=<path>
    val reportFile = findProperty("reportFile")?.toString()?.takeIf { it.isNotBlank() }
        ?: layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path
    systemProperty("kgp.reportFile", reportFile)

    // identify monorepo
    val monorepo = findProperty("monorepoDir")?.toString()?.takeIf { it.isNotBlank() }
        ?: "test-monorepo"
    // identify allowlist
    val allowlistArg = findProperty("allowlist")?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)
}
