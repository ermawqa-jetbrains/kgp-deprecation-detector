package org.jetbrains.kotlin.deprecations

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ensures finder and extractor use the same marker patterns to avoid losing candidates.
 */
class EmbeddedScriptMarkerTest {

    // one representative snippet per alternative in EmbeddedScriptFinder.MARKER
    private val markerSamples = listOf(
        "allprojects { }",
        "afterEvaluate { }",
        "tasks.create(\"foo\")",
        "tasks.register(\"foo\")",
        "gradle.rootProject { }",
        "gradle.ext.foo = 1",
        "gradle.settingsEvaluated { }",
        "gradle.buildFinished { }",
        "pluginManager.apply(\"foo\")",
        "GradleVersion.current()",
        "plugins { id(\"foo\") }",
        "kotlin { jvm() }",
        "dependencies { implementation(\"foo\") }",
        "compilerOptions { }",
        "kotlinOptions { }",
        "withType(Test::class)",
    )

    @Test
    fun everySampleMatchesTheSharedFinderMarker() {
        // sanity check: every sample below is actually covered by EmbeddedScriptFinder.MARKER,
        // so the extractor assertion is meaningful, not vacuous.
        val finderRegex = Regex(EmbeddedScriptFinder.MARKER)
        markerSamples.forEach { assertTrue(finderRegex.containsMatchIn(it), "not covered by finder marker: $it") }
    }

    @Test
    fun extractorRecognizesEveryPatternTheFinderMarkerCovers() {
        markerSamples.forEach { sample ->
            val text = "val script = \"\"\"\n    $sample\n\"\"\"\n"
            val extracted = EmbeddedScriptExtractor.extractFromText(text)
            assertTrue(extracted.isNotEmpty(), "extractor missed a finder-marker sample: $sample")
        }
    }
}
