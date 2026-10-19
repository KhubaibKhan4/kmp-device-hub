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
// The KMP library apps add to send their HTTP traffic to the Network Inspector (iOS targets build on macOS only).
if (providers.gradleProperty("devicehub.skipNetwork").orNull != "true") include(":network", ":network-noop")
if (providers.gradleProperty("devicehub.skipIdea").orNull != "true") include(":idea-plugin")
include(":samples")