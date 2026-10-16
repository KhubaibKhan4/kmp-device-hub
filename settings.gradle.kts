pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kmp-device-hub"

include(":core")
if (providers.gradleProperty("devicehub.skipIdea").orNull != "true") include(":idea-plugin")
