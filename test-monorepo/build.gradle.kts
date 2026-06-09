// Buildable fixture for the detector. Uses a WARNING-level deprecated KGP API
// (`KotlinJvmTarget.withJava()`, deprecated in 2.2.20) reached through the implicit
// `kotlin { jvm { … } }` accessor chain — the case a text matcher cannot resolve.
//
// Run:  ./gradlew checkKgpDeprecations           (defaults to this fixture)
plugins {
    kotlin("multiplatform") version "2.2.20"
}

kotlin {
    jvm {
        withJava()
    }
}

// Decoy: a user symbol named like the deprecated KGP one. A correct, resolving
// detector must NOT flag this — only the real withJava() above.
val withJava = "not the KGP withJava()"
println(withJava)
