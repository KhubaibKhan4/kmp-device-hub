// A small app for trying the KMP Device Hub Network Inspector end to end on a real emulator and simulator.
// Uses devicehub-network from Maven Central; mavenLocal() comes first so local library changes can be tried with
// `./gradlew :network:publishToMavenLocal`.
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
