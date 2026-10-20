import java.io.File
import java.net.URI

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// 1. Property Validation (Catch CLI typos early)

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
    val unknown = gradle.startParameter.projectProperties.keys
        .filter { "." !in it && it !in knownProjectProperties }
    if (unknown.isNotEmpty()) {
        val details = unknown.joinToString("\n") { name ->
            val suggestion = knownProjectProperties
                .minByOrNull { levenshtein(name.lowercase(), it.lowercase()) }
                ?.takeIf { levenshtein(name.lowercase(), it.lowercase()) <= 4 }
            "  -P$name" + (suggestion?.let { " (did you mean -P$it?)" } ?: "")
        }
        throw GradleException(
            "Unknown project ${if (unknown.size == 1) "property" else "properties"}:\n$details\n" +
                "Known properties: ${knownProjectProperties.sorted().joinToString(", ") { "-P$it" }}"
        )
    }
}

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

// 2. KGP Version & Repository Resolution

abstract class TeamCityBuildNumber : ValueSource<String, TeamCityBuildNumber.Parameters> {
    interface Parameters : ValueSourceParameters {
        val url: Property<String>
    }

    override fun obtain(): String? = runCatching {
        val connection = URI(parameters.url.get()).toURL().openConnection()
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.getInputStream().use { it.reader().readText() }.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

val teamcityServer = "https://buildserver.labs.intellij.net"
val kgpBuildType = (findProperty("kgpBuildType") as String?)?.takeIf { it.isNotBlank() }
    ?: "Kotlin_KotlinDev_Artifacts"

fun teamcityBuildNumber(locator: String): String? = providers.of(TeamCityBuildNumber::class) {
    parameters.url.set("$teamcityServer/guestAuth/app/rest/builds/$locator/number")
}.orNull

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

val versionProblem = when {
    isLatest && !isTeamCityBuild -> "Cannot ask TeamCity for latest build of '$kgpBuildType'.\n" +
        "Connect to JetBrains network, or pin version via -PkgpEngineVersion=<version>."
    else -> null
}

val kgpRepoUrl = if (isTeamCityBuild) {
    "$teamcityServer/guestAuth/app/rest/builds/" +
        "buildType:(id:$kgpBuildType),number:$engineVersion,branch:default:any/artifacts/content/maven.zip!/"
} else null

repositories {
    maven {
        name = "kotlinDevRepo"
        setUrl("https://packages.jetbrains.team/maven/p/kt/dev")
        // Only KGP/Kotlin artifacts live here; keeps other dependencies off this repo.
        content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..+)?") }
    }
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
    // Cache-redirector mirror, declared last so the Kotlin-specific repos are tried first.
    mavenCentral {
        url = URI("https://cache-redirector.jetbrains.com/maven-central")
    }
}

// 3. Dependencies

val kgpJars = configurations.create("kgpJars")

dependencies {
    implementation("org.ow2.asm:asm:9.10.1")
    testImplementation(kotlin("test"))
    kgpJars("org.jetbrains.kotlin:kotlin-gradle-plugin:$engineVersion")
}

// 4. Main Verification Task

fun resolveScanRoot(): File {
    val raw = findProperty("monorepoDir")?.toString()?.takeIf { it.isNotBlank() } ?: "test-monorepo"
    val given = File(raw)
    val resolved = if (given.isAbsolute) given else layout.projectDirectory.asFile.resolve(raw)
    if (!resolved.isDirectory) {
        throw GradleException(
            "-PmonorepoDir does not point at a directory:\n  given   : $raw\n  resolved: ${resolved.path}"
        )
    }
    return resolved.canonicalFile
}

tasks.register<JavaExec>("checkKgpDeprecations") {
    group = "verification"
    description = "Scans .kt/.java for embedded Gradle scripts using deprecated KGP APIs."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.jetbrains.kotlin.deprecations.MainKt")
    outputs.upToDateWhen { false }

    versionProblem?.let { throw GradleException(it) }

    // Core execution properties
    systemProperty("kgp.engineVersion", engineVersion)
    systemProperty("kgp.engineSource", engineSource)
    systemProperty("kgp.pluginJars", kgpJars.asPath)

    // Forward optional -P properties cleanly
    fun forward(projectProp: String, systemProp: String = "kgp.$projectProp") {
        findProperty(projectProp)?.toString()?.takeIf { it.isNotBlank() }?.let {
            systemProperty(systemProp, it)
        }
    }
    forward("excludePatterns")
    forward("rgPath")
    forward("targetSymbols")
    findProperty("fullIndex")?.let {
        systemProperty("kgp.fullIndex", it.toString().takeIf { v -> v.isNotBlank() } ?: "true")
    }

    // Report file path
    val reportFile = findProperty("reportFile")?.toString()?.takeIf { it.isNotBlank() }
        ?: layout.buildDirectory.file("reports/kgp-deprecations.txt").get().asFile.path
    systemProperty("kgp.reportFile", reportFile)

    // Positional arguments: [scanRoot, allowlist]
    val monorepo = resolveScanRoot().path
    val allowlistArg = findProperty("allowlist")?.toString().orEmpty()
    args = listOf(monorepo, allowlistArg)

    // TeamCity environment detection
    val isTeamCity = !System.getenv("TEAMCITY_VERSION").isNullOrBlank() ||
        !System.getProperty("teamcity.version").isNullOrBlank() ||
        findProperty("teamcity.version")?.toString()?.isNotBlank() == true
    if (isTeamCity) {
        systemProperty("teamcity.version", "true")
    }

    // Inspect exit code directly to handle findings (1) vs setup errors (2)
    isIgnoreExitValue = true
    val result = executionResult
    doLast {
        when (val code = result.get().exitValue) {
            0 -> Unit
            1 -> {
                if (!isTeamCity) {
                    throw GradleException(
                        "KGP deprecation check FAILED: deprecated API usages found. See the report above."
                    )
                }
                // In Main.kt, ##teamcity[buildProblem] was already emitted. Exiting cleanly
                // marks the build failed without duplicate errors or 100-line stack trace noise.
            }
            2 -> throw GradleException(
                "KGP deprecation check DID NOT RUN (setup failure, exit 2). Fix the invocation and re-run."
            )
            else -> throw GradleException("KGP deprecation check exited unexpectedly with code $code.")
        }
    }
}
