// Buildable fixture for the detector. Uses a WARNING-level deprecated KGP API
// (`KotlinJvmTarget.withJava()`, deprecated in 2.2.20) reached through the implicit
// `kotlin { jvm { … } }` accessor chain — the case a text matcher cannot resolve.
//
// Run:  ./gradlew checkKgpDeprecations           (defaults to this fixture)
plugins {
    kotlin("multiplatform") version "2.4.0"
}

kotlin {
    jvm {
        withJava()
    }
}

// Lazy-property assignment (`Property<String> = …`). This only compiles when the
// kotlin-assignment compiler plugin is applied, exactly as Gradle applies it — so it
// proves the detector resolves real scripts, not just accessor chains. Not deprecated,
// so it must NOT be flagged.
tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
    archiveBaseName = "kgp-detector-fixture"
}

// Decoy: a user symbol named like the deprecated KGP one. A correct, resolving
// detector must NOT flag this — only the real withJava() above.
val withJava = "not the KGP withJava()"
println(withJava)
