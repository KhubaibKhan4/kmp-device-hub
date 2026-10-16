plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.intellij.platform) apply false
}

allprojects {
    group = "io.github.khubaibkhan4"
    version = providers.gradleProperty("devicehub.version").get()
}
