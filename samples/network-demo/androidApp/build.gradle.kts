plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.kmpdevicehub.demo"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.kmpdevicehub.demo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
