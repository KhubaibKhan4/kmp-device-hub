<div align="center">

<img src="idea-plugin/src/main/resources/META-INF/pluginIcon.svg" width="96" alt="KMP Device Hub icon">

# KMP Device Hub

**Every Android device, emulator and iOS simulator in one panel.**

For Kotlin Multiplatform and Compose Multiplatform developers who test on Android and iOS every day.

</div>

## Status

Early development.

- **Milestone 1 (done):** a **KMP Devices** tool window listing running and stopped Android emulators, connected
  phones and iOS simulators together, with clear status and live updates.
- **Milestone 2 (done):** start and shut down emulators and simulators (double-click to start); your app's
  Android `applicationId` and iOS bundle id are detected from the project; **Launch**, **Stop**, **Clear data** and
  **Uninstall** on the selected device, with confirmation before anything destructive.
- **Milestone 3 (done):** grant, revoke and reset app permissions; dark mode and text size; run your app in
  another language (including right-to-left Arabic, Urdu and Hebrew); open deep links; screenshots saved and copied
  to the clipboard; Android Wi-Fi debugging (pair and connect).

- **Milestone 4 (done, Pro):** apply actions to **all running devices** at once; **test presets** (built-in and your
  own); **screen recording**; a **push notification tester** (real simulated pushes on iOS, a notification-tap intent
  with your data on Android); a **deep link library** with links found in your manifest and `Info.plist`;
  **simulated location**; Android emulator **network conditions** (LTE, 3G, EDGE, GPRS, offline); a **clean 9:41
  status bar** for store screenshots.

- **Milestone 5 (done):** the **Network Inspector**, a **KMP Network** tool window showing every HTTP call your app
  makes (Ktor on Android/iOS/JVM, or OkHttp/Retrofit on Android). It covers method, URL, status, timing, size, request
  and response headers and bodies (pretty-printed), a JSON tree, failures, totals, a throughput sparkline and device
  connectivity. Copy any call as cURL. **Pro:** mock responses, fail requests or add latency (from a rule or
  right-click → *Mock This Response*), and export HAR.

Coming next: unified logs (Logcat + simulator log), an app data explorer, installing APK/.app files and more device
simulation.

## Network Inspector setup

Add the library to your app's debug builds, then install it in your HTTP client:

```kotlin
// build.gradle.kts — commonMain (or debugImplementation in an Android-only app)
implementation("io.github.khubaibkhan4:devicehub-network:0.1.0")

// Ktor (Android, iOS, JVM)
val client = HttpClient {
    install(DeviceHubNetwork) { enabled = isDebugBuild }
}

// OkHttp / Retrofit (Android)
OkHttpClient.Builder().addInterceptor(DeviceHubInterceptor { enabled = BuildConfig.DEBUG })
```

The library connects to the IDE on `127.0.0.1:38383`. iOS simulators reach it directly, and Android devices and
emulators reach it through `adb reverse`, which the plugin sets up. Bodies over 128 KB and binary content are reported
by size only. Use `redactHeaders = setOf("Authorization")` to keep secrets out of the IDE.

**Only enable it in debug builds**: it sends your app's traffic, headers included, to whatever listens on that port.

Until it's on Maven Central, either publish it locally with `./gradlew :network:publishToMavenLocal` and add
`mavenLocal()` to your repositories, or build a shareable repository with
`./gradlew :network:publishAllPublicationsToDistRepository` (written to `dist/maven`), zip it, and add
`maven { url = uri("/path/to/maven") }`. [`samples/network-demo`](samples/network-demo) is a small Android + iOS app to try it with.

## Pro

Pro is sold through JetBrains Marketplace, which also runs the free trial. Builds made without a product code (the
default) are free early-access builds with every Pro feature on. Once Marketplace approves the paid plan and assigns a
product code:

```bash
./gradlew :idea-plugin:buildPlugin -Pdevicehub.productCode=<CODE>   # adds an optional (freemium) product-descriptor
```

Bump `devicehub.releaseDate` / `devicehub.releaseVersion` in `gradle.properties` for each major release.

## How it works

| Module | What it is |
|---|---|
| [`core`](core) | Dependency-free engine: finds `adb`, `emulator` and `xcrun`, lists devices, and runs every device action, setting, simulation and preset through them. Every command goes through a `CommandRunner`, faked in tests. |
| [`network`](network) | `devicehub-network`, the Kotlin Multiplatform library (JVM/Android, iOS) apps add to send their HTTP calls to the Network Inspector: a Ktor client plugin and an OkHttp interceptor. |
| [`idea-plugin`](idea-plugin) | The KMP Devices tool window for Android Studio and IntelliJ IDEA 2025.1+. |

Requirements: the Android SDK for Android devices; macOS with Xcode for iOS simulators.

## Development

```bash
echo 'devicehub.ideaLocalPath=/Applications/Android Studio.app' >> local.properties   # git-ignored
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build                 # all tests
./gradlew :idea-plugin:runIde   # sandbox IDE with the plugin
```

© 2026 Khubaib Khan. All rights reserved.
