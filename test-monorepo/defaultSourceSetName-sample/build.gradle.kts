// Reproduces the IntelliJ regression: usage of `defaultSourceSetName`
// (escalated from WARNING to ERROR in newer KGP, causing
// "cannot create task MainKt.main() due to missing defaultSourceSetName").
//
// Run the detector against this fixture:
//   ./gradlew checkKgpDeprecations -PkgpVersion=<ver> -PmonorepoDir=test-monorepo
kotlin {
    targets.all {
        compilations.all {
            val resolvedName = defaultSourceSetName
            println(resolvedName)
        }
    }
}

// Additional deprecated usage that the live detector flags against KGP 2.4.0-dev-8644:
// `kotlinOptions { ... }` was deprecated in favor of `compilerOptions { ... }`.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
