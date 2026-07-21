// Fixture for the detector (NOT compiled — read as text by the tool).
//
// Mimics an IDE-injected Gradle script hardcoded as a Kotlin-DSL string literal inside a
// .kt file (the counterpart to EmbeddedInitScript.kt's Groovy case). The string is never
// compiled by the surrounding .kt file, so this is exactly as unresolved as embedded
// Groovy — only name-matching catches deprecated KGP usage here too.
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
