// Gradle init script hardcoded as a Groovy string literal
// inside a .kt file. It uses the WARNING-deprecated KGP `withJava()` in Groovy code
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
