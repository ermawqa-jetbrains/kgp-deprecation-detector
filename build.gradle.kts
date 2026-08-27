import java.io.File

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/kt/dev")
}

kotlin {
    // Pinned, not "whatever JDK is on PATH": the tool reads bytecode with ASM and its behavior
    // surface (and the bytecode it compiles to) must not differ between a developer's local JDK
    // and CI. 17 is the lowest LTS this build needs.
    jvmToolchain(17)
}

/**
 * Project properties this build understands. Gradle silently accepts any `-P<name>=<value>`,
 * so a typo (`-PmnorepoDir=...`) would fall back to the default scan root and produce a green
 * build against the bundled fixture - the same silent-success failure mode the tool's exit
 * code 2 exists to prevent. Fail the build instead.
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
val engineVersion = (findProperty("kgpEngineVersion") as String?) ?: "2.4.10"

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


/**
 * Scan root, resolved against the project directory at **configuration** time. A raw relative
 * string used to reach the JVM untouched, so a mistyped absolute path (the truncated
 * `/Useyermukhamed.shakhman/...` incident) was only caught after startup - and a path that
 * happens to exist relative to the daemon's working directory would be scanned silently.
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

/** Main task that checks for deprecation **/
tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans .kt/.java under -PmonorepoDir (default test-monorepo) for embedded " +
        "Gradle scripts using deprecated KGP APIs. Optional -Pallowlist=<file>."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    // Surface the indexed KGP version in the tool's banner
    systemProperty("kgp.engineVersion", engineVersion)
    // `asPath` instead of a manual joinToString; resolution happens here, which is lazy because
    // `tasks.register`'s configuration block only runs when the task is in the graph (so
    // `./gradlew help` never resolves it). It cannot be a Provider: JavaExec.systemProperty does
    // not unwrap one and would pass the provider's toString() as the classpath.
    systemProperty("kgp.pluginJars", kgpJars.asPath)
    // A checker must always run; say so deliberately rather than by declaring no inputs/outputs.
    outputs.upToDateWhen { false }
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

    // identify monorepo (validated at configuration time, see resolveScanRoot)
    val monorepo = resolveScanRoot().path
    // identify allowlist
    val allowlistArg = findProperty("allowlist")?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)
}
