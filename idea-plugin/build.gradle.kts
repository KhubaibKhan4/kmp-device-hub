import java.util.Properties
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // 2025.1 (since-build 251) bundles Kotlin 2.1; stay at or below it.
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// A local Android Studio / IntelliJ (from -P, ~/.gradle/gradle.properties or the git-ignored local.properties),
// otherwise IntelliJ IDEA is downloaded (CI).
val localIdePath: String? = providers.gradleProperty("devicehub.ideaLocalPath").orNull
    ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.reader().use { load(it) } }.getProperty("devicehub.ideaLocalPath")
    }
val localIde: String? = localIdePath?.takeIf { file(it).exists() }

dependencies {
    implementation(project(":core")) {
        // The IDE provides the Kotlin stdlib.
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        // IntelliJ IDEA ships as one distribution since 2025.3 (no separate Community artifact).
        if (localIde != null) local(localIde) else intellijIdea(providers.gradleProperty("devicehub.ciPlatformVersion").get())
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

/** The newest section of CHANGELOG.md as HTML, for the Marketplace "What's New" tab. */
val changeNotesHtml = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText.map { md ->
    val section = md.substringAfter("\n## ").substringAfter("\n").substringBefore("\n## ").trim()
    val items = section.lines().filter { it.startsWith("- ") }.joinToString("") { "<li>${it.removePrefix("- ")}</li>" }
    "<ul>$items</ul>"
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        // Permanent once published on Marketplace.
        id = "io.github.khubaibkhan4.kmpdevicehub"
        name = "KMP Device Hub"
        version = project.version.toString()
        changeNotes = changeNotesHtml
        vendor {
            name = providers.gradleProperty("devicehub.vendorName")
            url = providers.gradleProperty("devicehub.vendorUrl")
        }
        ideaVersion {
            sinceBuild = "251"
            // No untilBuild: the plugin uses stable platform APIs only.
            untilBuild = provider { null }
        }
        // Paid (freemium) plan: only once JetBrains Marketplace has approved it and assigned the product code,
        // e.g. -Pdevicehub.productCode=PKMPDEVICEHUB. Without it the build is free and every Pro feature is on.
        providers.gradleProperty("devicehub.productCode").orNull?.let { productCode ->
            productDescriptor {
                code = productCode
                releaseDate = providers.gradleProperty("devicehub.releaseDate")
                releaseVersion = providers.gradleProperty("devicehub.releaseVersion")
                optional = true
            }
        }
    }

    // ./gradlew :idea-plugin:signPlugin — values come from CI secrets, never from files in the repo.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // ./gradlew :idea-plugin:publishPlugin — needs a JetBrains Marketplace permanent token.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("devicehub.marketplaceChannel").map { listOf(it) }
    }

    // ./gradlew :idea-plugin:verifyPlugin — the local IDE always; -Pdevicehub.verifyRecommended=true also
    // downloads the IDE versions JetBrains recommends for the since-build range (several GB, used in CI).
    pluginVerification {
        ides {
            if (localIde != null) local(localIde)
            if (providers.gradleProperty("devicehub.verifyRecommended").orNull == "true") recommended()
            // e.g. -Pdevicehub.verifyIdes=IC:2025.1.7,IC:2025.2.6 to reproduce a Marketplace verification report.
            providers.gradleProperty("devicehub.verifyIdes").orNull?.split(',')?.filter { it.isNotBlank() }?.forEach {
                val (type, version) = it.trim().split(':')
                create(type, version)
            }
        }
    }
}

// The IntelliJ Platform plugin derives jvmTarget from the IDE's runtime (Java 25 for 2026.2).
// Keep 21 so the plugin also loads in 2025.x IDEs that run on JBR 21.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    // Call the platform's interface default methods directly instead of compiling bridges into our classes,
    // which the Plugin Verifier would report as overrides of deprecated/experimental API.
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}

// ./gradlew :idea-plugin:runIde -Pdevicehub.openProject=/path/to/project opens that project in the sandbox IDE.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val openProject = providers.gradleProperty("devicehub.openProject")
    argumentProviders += CommandLineArgumentProvider { listOfNotNull(openProject.orNull) }
    // Lets the sandbox IDE try Pro in a paid build (honoured only in internal mode, never in a user's IDE).
    jvmArgs("-Didea.is.internal=true", "-Dkmpdevicehub.pro=true")
}

// The IDE test fixture plus panel renders need more than the default heap on CI runners.
tasks.named<Test>("test") {
    maxHeapSize = "2g"
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("kmp-device-hub")
}
