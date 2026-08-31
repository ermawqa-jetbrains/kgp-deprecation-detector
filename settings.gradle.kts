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
    // Applied only on request, see below.
    id("com.gradle.develocity") version "4.3" apply false
}

// Build scans are opt-in (`-PbuildScan`). Publishing on every build made the check depend on
// reaching ge.labs.jb.gg, which an offline or network-restricted CI agent cannot do. A
// `publishing.onlyIf { }` predicate would be the obvious shape, but the plugin cannot serialize
// a settings-script lambda into the configuration cache - so the plugin itself is only applied
// when the flag is present.
if (gradle.startParameter.projectProperties.containsKey("buildScan")) {
    apply(plugin = "com.gradle.develocity")
    configure<com.gradle.develocity.agent.gradle.DevelocityConfiguration> {
        buildScan {
            termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
            termsOfUseAgree.set("yes")
        }
        server.set("https://ge.labs.jb.gg")
    }
}

rootProject.name = "kgp-deprecation-detector"
