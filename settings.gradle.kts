pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/kt/dev")
    }
    plugins {
        kotlin("jvm") version "2.4.10"
    }
}

plugins {
    id ("com.gradle.develocity") version "4.3"
}

develocity {
    buildScan {
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
    server.set("https://ge.labs.jb.gg")
}

rootProject.name = "kgp-deprecation-detector"