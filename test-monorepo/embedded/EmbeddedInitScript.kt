// Fixture for the Groovy heuristic pass (NOT compiled — read as text by the detector).
//
// Mimics the IntelliJ pattern: a Gradle init script hardcoded as a Groovy string literal
// inside a .kt file. It uses the WARNING-deprecated KGP `withJava()` in Groovy code, which
// is dynamically typed and so cannot be resolved by any frontend — only the name-matching
// heuristic pass catches it. It must appear in the HEURISTIC report section and, being a
// WARNING (and non-gating), must NOT change the exit code.
package embedded

val initScript = """
    allprojects {
        afterEvaluate { project ->
            if (project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                project.kotlin.targets.each { target ->
                    target.compilations.each { compilation ->
                        // Deprecated KGP API reached through dynamic Groovy:
                        compilation.target.withJava()
                    }
                }
            }
        }
    }
"""
