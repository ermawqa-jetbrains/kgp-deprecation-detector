pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/kt/dev")
    }
    plugins {
        kotlin("jvm") version "2.3.21"
    }
}
rootProject.name = "kgp-deprecation-detector"