package org.jetbrains.kotlin.deprecations

import java.io.File

/**
 * Encapsulates all configuration parameters required for a KGP deprecation scan.
 */
internal data class DetectorConfig(
    val scanRoot: File,
    val allowlist: Set<String> = emptySet(),
    val excludePatterns: List<String> = DEFAULT_EXCLUDE_PATTERNS,
    val pluginJars: List<String>,
    val engineVersion: String? = null,
    val engineSource: String? = null,
    val targetSymbols: List<String> = emptyList(),
    val reportFilePath: String? = null,
    val fullIndex: Boolean = false,
) {
    companion object {
        internal val DEFAULT_EXCLUDE_PATTERNS = listOf(
            "/testData/", "/testdata/", "/testResources/", "/testSources/", "/testSrc/",
            "/test/", "/tests/", "/integration-tests/", "/agpIntegrationTestSrc/", "/resources/",
            // Notebook prompt (not a script)
            "/privacy/KotlinNotebookSystemPromptPrivacySafeWrapper.kt",
            // Documentation snippet (not a script)
            "/fleet/buildtool/bundles/helpers.kt",
        )

        /**
         * Parses command-line arguments and system properties into a [DetectorConfig].
         * Returns `null` if arguments or required properties are invalid.
         */
        fun parse(
            args: Array<String>,
            propertyLookup: (String) -> String? = System::getProperty,
        ): DetectorConfig? {
            if (args.isEmpty() || args[0].isBlank()) {
                return null
            }

            val scanRoot = File(args[0]).canonicalFile
            if (!scanRoot.isDirectory) {
                System.err.println("Not a directory: ${scanRoot.path}")
                return null
            }

            val allowlistPath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
            val allowlistFile = allowlistPath?.let(::File)
            val allowlist = if (allowlistFile == null) emptySet() else {
                loadAllowlist(allowlistFile) ?: return null
            }

            val userExcludes = propertyLookup("kgp.excludePatterns").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotBlank() }
            val excludePatterns = (DEFAULT_EXCLUDE_PATTERNS + userExcludes).distinct()

            val jars = propertyLookup("kgp.pluginJars").orEmpty()
                .split(File.pathSeparator).filter { it.isNotBlank() }
            if (jars.isEmpty()) {
                System.err.println("No KGP jars provided (kgp.pluginJars) - nothing to match against.")
                return null
            }

            val engineVersion = propertyLookup("kgp.engineVersion")?.takeIf { it.isNotBlank() }
            val engineSource = propertyLookup("kgp.engineSource")?.takeIf { it.isNotBlank() }
            val targetSymbols = propertyLookup("kgp.targetSymbols").orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val reportFilePath = propertyLookup("kgp.reportFile")?.takeIf { it.isNotBlank() }
            val fullIndex = propertyLookup("kgp.fullIndex").toBoolean()

            return DetectorConfig(
                scanRoot = scanRoot,
                allowlist = allowlist,
                excludePatterns = excludePatterns,
                pluginJars = jars,
                engineVersion = engineVersion,
                engineSource = engineSource,
                targetSymbols = targetSymbols,
                reportFilePath = reportFilePath,
                fullIndex = fullIndex,
            )
        }

        private fun loadAllowlist(file: File): Set<String>? {
            if (!file.exists()) {
                System.err.println("Allowlist file not found: ${file.path}")
                return null
            }
            return file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}
