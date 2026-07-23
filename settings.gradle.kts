pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/kt/dev")
    }
    plugins {
        kotlin("jvm") version "2.4.0"
    }
}
rootProject.name = "kgp-deprecation-detector"