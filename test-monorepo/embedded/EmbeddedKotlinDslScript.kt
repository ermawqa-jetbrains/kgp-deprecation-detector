// Mimics an IDE-injected Gradle script hardcoded as a Kotlin-DSL string literal inside a
// .kt file (the counterpart to EmbeddedInitScript.kt's Groovy case)
package embedded

val embeddedKts = """
    plugins {
        kotlin("multiplatform")
    }

    kotlin {
        jvm {
            // Deprecated KGP API, reached through Kotlin-DSL syntax this time:
            withJava()
        }
    }
"""
