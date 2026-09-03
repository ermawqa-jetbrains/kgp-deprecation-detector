import java.io.File
import java.net.URI

plugins {
    kotlin("jvm")
}

kotlin {
    // Pins toolchain to ensure consistent bytecode across environments.
    jvmToolchain(21)
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
    "kgpBuildType",
    "excludePatterns",
    "reportFile",
    "fullIndex",
    "rgPath",
    "targetSymbols",
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

/**
 * Reads the number of the build matching a TeamCity locator, or null if there is none (or the
 * server cannot be reached).
 *
 * A `ValueSource` rather than a plain HTTP read: the configuration cache re-evaluates it on every
 * run, so "latest" cannot silently stay pinned to yesterday's build on a reused agent.
 */
abstract class TeamCityBuildNumber : ValueSource<String, TeamCityBuildNumber.Parameters> {
    interface Parameters : ValueSourceParameters {
        val url: Property<String>
    }

    override fun obtain(): String? = runCatching {
        val connection = URI(parameters.url.get()).toURL().openConnection()
        // Bounded: this runs at configuration time, including for './gradlew build' off the network.
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.getInputStream().use { it.reader().readText() }.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

val teamcityServer = "https://buildserver.labs.intellij.net"

// TeamCity configuration the KGP jars are taken from. Its build numbers are the KGP versions
// ("2.5.0-dev-6260"). Override with -PkgpBuildType to index a release branch instead of master.
val kgpBuildType = (findProperty("kgpBuildType") as String?)?.takeIf { it.isNotBlank() }
    ?: "Kotlin_KotlinDev_Artifacts"

fun teamcityBuildNumber(locator: String): String? = providers.of(TeamCityBuildNumber::class) {
    parameters.url.set("$teamcityServer/guestAuth/app/rest/builds/$locator/number")
}.orNull

val buildTypeUrl = "$teamcityServer/buildConfiguration/$kgpBuildType"

// KGP version to index. Override with -PkgpEngineVersion; "latest" is the last successful build.
// There is no hard-coded default version on purpose: a build's artifacts are cleaned up over time,
// so any pinned default would rot.
val requestedVersion = (findProperty("kgpEngineVersion") as String?)?.takeIf { it.isNotBlank() } ?: "latest"
val isLatest = requestedVersion.equals("latest", ignoreCase = true)
val tcBuildNumber = if (isLatest) {
    teamcityBuildNumber("buildType:(id:$kgpBuildType),status:SUCCESS,branch:default:any")
} else {
    teamcityBuildNumber("buildType:(id:$kgpBuildType),number:$requestedVersion,branch:default:any")
}

val isTeamCityBuild = tcBuildNumber != null
val engineVersion = tcBuildNumber ?: requestedVersion
val engineSource = if (isTeamCityBuild) "TeamCity $kgpBuildType" else "Maven (Central / kt-dev)"

// Reported by the check task, not thrown here: only that task needs the jars, so 'build' (unit
// tests, pure offline ASM) must keep working without TeamCity.
val versionProblem = when {
    isLatest && !isTeamCityBuild -> "Cannot ask TeamCity for the latest build of '$kgpBuildType' ($buildTypeUrl).\n" +
        "Connect to the JetBrains network, or pin a version with -PkgpEngineVersion=<version>."
    else -> null
}

// The build publishes its whole Maven repository as a single 'maven.zip' artifact; TeamCity serves
// files from inside an archive, so the artifact is usable as a Maven repository as is.
val kgpRepoUrl = if (isTeamCityBuild) {
    "$teamcityServer/guestAuth/app/rest/builds/" +
        "buildType:(id:$kgpBuildType),number:$engineVersion,branch:default:any/artifacts/content/maven.zip!/"
} else null

repositories {
    mavenCentral()
    maven {
        name = "kotlinDevRepo"
        setUrl("https://packages.jetbrains.team/maven/p/kt/dev")
    }
    // If resolved from a TeamCity build, 'exclusiveContent' routes that specific version to TeamCity maven.zip
    if (kgpRepoUrl != null) {
        exclusiveContent {
            forRepository {
                maven {
                    name = "kotlinTeamCityBuild"
                    setUrl(kgpRepoUrl)
                }
            }
            filter { includeVersionByRegex("org\\.jetbrains\\.kotlin(\\..+)?", ".+", Regex.escape(engineVersion)) }
        }
    }
}

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
    versionProblem?.let { throw GradleException(it) }
    // Surface indexed KGP version and where its jars came from in the banner
    systemProperty("kgp.engineVersion", engineVersion)
    systemProperty("kgp.engineSource", engineSource)
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
    // -PrgPath pins an explicit ripgrep binary, bypassing PATH (some CI runners recompute PATH
    // internally, e.g. TeamCity's Gradle step with jdkHome set, silently dropping PATH prepends).
    findProperty("rgPath")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.rgPath", it) }
    // -PtargetSymbols asks for an explicit found/not-found verdict (with real usage count) for
    // specific symbols, so a caller never has to infer "0 usages" vs "not indexed at all".
    findProperty("targetSymbols")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("kgp.targetSymbols", it) }

    // Forward TeamCity detection if present
    findProperty("teamcity.version")?.toString()?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("teamcity.version", it) }
    System.getenv("TEAMCITY_VERSION")?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("teamcity.version", it) }

    // Mirror output to file if -PreportFile is set
    val reportFile = findProperty("reportFile")?.toString()?.takeIf { it.isNotBlank() }
        ?: layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path
    systemProperty("kgp.reportFile", reportFile)

    val monorepo = resolveScanRoot().path
    val allowlistArg = findProperty("allowlist")?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)

    val isTeamCity = !System.getenv("TEAMCITY_VERSION").isNullOrBlank() ||
        !System.getProperty("teamcity.version").isNullOrBlank() ||
        findProperty("teamcity.version")?.toString()?.isNotBlank() == true

    // Gradle turns any non-zero exit into its own generic failure, hiding the
    // tool's 1 (findings) vs 2 (setup failure) distinction. Inspect it ourselves.
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        when (val code = result.get().exitValue) {
            0 -> Unit
            1 -> {
                if (isTeamCity) {
                    // Main.kt already emitted ##teamcity[buildProblem]. Completing cleanly
                    // marks the build failed without redundant errors or stack trace noise.
                } else {
                    throw GradleException(
                        "KGP deprecation check FAILED: deprecated API usages found. See the report above."
                    )
                }
            }
            2 -> throw GradleException(
                "KGP deprecation check DID NOT RUN (setup failure, exit 2). " +
                    "The check produced no verdict - fix the invocation and re-run."
            )
            else -> throw GradleException("KGP deprecation check exited unexpectedly with code $code.")
        }
    }
}
