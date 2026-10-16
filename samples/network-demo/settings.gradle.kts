// A small app for trying the KMP Device Hub Network Inspector end to end on a real emulator and simulator.
// Uses devicehub-network from Maven Local: run `./gradlew :network:publishToMavenLocal` in the repo root first.
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "network-demo"
include(":shared", ":androidApp")
