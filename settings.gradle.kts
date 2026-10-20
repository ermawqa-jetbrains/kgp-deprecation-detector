pluginManagement {
    repositories {
        maven("https://packages.jetbrains.team/maven/p/kt/dev")
        maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        maven("https://cache-redirector.jetbrains.com/maven-central")
    }
    plugins {
        kotlin("jvm") version "2.4.10"
    }
}

rootProject.name = "kgp-deprecation-detector"
