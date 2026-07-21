// Fixture for the detector (NOT compiled — read as text by the tool).
//
// Mimics the IntelliJ pattern: a Gradle init script hardcoded as a Groovy string literal
// inside a .kt file. It uses the WARNING-deprecated KGP `withJava()` in Groovy code, which
// is dynamically typed and so cannot be resolved by any frontend — only name-matching
// catches it. Being WARNING-only, it must NOT fail the run (only ERROR/HIDDEN gate exit 1).
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
