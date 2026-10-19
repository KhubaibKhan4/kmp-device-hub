<div align="center">

<img src="docs/banner.png" alt="KMP Device Hub: every Android device, emulator and iOS simulator in one panel" width="900">

# KMP Device Hub

**Every Android device, emulator and iOS simulator in one panel.**

For Kotlin Multiplatform and Compose Multiplatform developers who test on Android and iOS every day.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.khubaibkhan4/devicehub-network?label=devicehub-network)](https://central.sonatype.com/artifact/io.github.khubaibkhan4/devicehub-network)
![Android Studio & IntelliJ IDEA 2025.1+](https://img.shields.io/badge/Android%20Studio%20%26%20IntelliJ-2025.1%2B-7F52FF)
![Platforms](https://img.shields.io/badge/devices-Android%20%7C%20iOS%20simulator-1E88E5)

[▶ Watch the 1-minute preview](docs/kmp-device-hub-preview.mp4)

</div>

## Install

In Android Studio or IntelliJ IDEA (2025.1 or newer): **Settings → Plugins → Marketplace**, search for
**KMP Device Hub**, install, and restart. Then open the **KMP Devices** tool window (right) and **KMP Network**
(bottom).

You need the Android SDK for Android devices, and macOS with Xcode for iOS simulators. Both are found automatically,
including the project's `sdk.dir`.

## Features

### Every device, one panel

Running and stopped Android emulators, connected phones (USB or Wi-Fi) and iOS simulators in one list, with search and
a platform filter. Start or shut down any of them, and launch, stop, clear or uninstall your app; its Android
`applicationId` and iOS bundle id are found in your project.

<img src="docs/screenshots/01-devices.png" alt="Devices panel next to an Android emulator and an iPhone simulator" width="900">

### Dark mode, text size and right-to-left

Switch appearance and text size on Android and iOS, and run your app in another language, including Arabic, Urdu and
Hebrew for right-to-left layouts. The app restarts in the new language on both platforms.

<img src="docs/screenshots/02-appearance-language.png" alt="Settings tab with an iPhone at the largest text size and Android in Arabic" width="900">

### Permissions

See what your app was granted, then grant, revoke or reset permissions (camera, location, microphone, contacts, photos,
calendar, notifications) without digging through Settings.

<img src="docs/screenshots/03-permissions.png" alt="Permissions tab with granted and denied states" width="900">

### Network Inspector

Every HTTP call your app makes, Ktor on Android, iOS and JVM or OkHttp/Retrofit on Android: status, timing, size,
headers, pretty-printed bodies and a JSON tree, plus totals, throughput and device connectivity. Copy any call as cURL.
[Set it up](#network-inspector-setup) with one line in your `HttpClient`.

<img src="docs/screenshots/04-network-inspector.png" alt="KMP Network window with API calls and a JSON tree" width="900">

Errors stand out: 4xx/5xx responses and failed calls (no connection, unknown host, timeouts). Mock responses, fail
requests or add latency to test your error states, and export everything as HAR.

<img src="docs/screenshots/05-network-errors.png" alt="A 402 response and a failed call in the Network Inspector" width="900">

### Simulate the real world

Simulated location, Android emulator network conditions (LTE, 3G, EDGE, GPRS or offline), push notifications (a real
push on iOS, a notification-tap intent on Android), deep links, screenshots, screen recording and a clean 9:41 status
bar for store screenshots.

<img src="docs/screenshots/06-simulate.png" alt="Simulate tab next to an iPhone home screen with a 9:41 status bar" width="900">

### Test presets

Set several conditions at once: dark mode with the largest text, right-to-left, long German words, a slow or offline
network, store screenshots, or your own. Turn on **All running devices** to apply anything to every device at once.

<img src="docs/screenshots/07-presets.png" alt="Presets tab next to Android and iOS in Arabic" width="900">

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

It's on Maven Central (`mavenCentral()`), so no extra repository is needed. To try local changes, publish with
`./gradlew :network:publishToMavenLocal` and add `mavenLocal()`.

## Free and Pro

Everything is free during early access. Later, a Pro plan through JetBrains Marketplace (with a free trial) will cover
the power features: all-devices actions, presets, screen recording, the push tester, the deep link library, location,
network conditions and the clean status bar, and the Network Inspector's mocking and HAR export. Device control,
settings, permissions and the Network Inspector itself stay free.

## Roadmap

- Unified logs: Logcat and the iOS simulator log in one view, filtered to your app
- App data explorer: files, preferences and SQLite databases on the device
- Install an APK or `.app` by dragging it onto a device
- Battery, rotation, biometrics, media and clipboard simulation

## How it works

| Module | What it is |
|---|---|
| [`core`](core) | Dependency-free engine: finds `adb`, `emulator` and `xcrun`, lists devices, and runs every device action, setting, simulation and preset through them. Every command goes through a `CommandRunner`, faked in tests. |
| [`network`](network) | `devicehub-network`, the Kotlin Multiplatform library (JVM/Android, iOS) apps add to send their HTTP calls to the Network Inspector: a Ktor client plugin and an OkHttp interceptor. |
| [`idea-plugin`](idea-plugin) | The KMP Devices and KMP Network tool windows for Android Studio and IntelliJ IDEA 2025.1+. |
| [`samples/network-demo`](samples/network-demo) | A small Android + iOS app for trying the Network Inspector. |

## Development

```bash
echo 'devicehub.ideaLocalPath=/Applications/Android Studio.app' >> local.properties   # git-ignored
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew build                 # all tests
./gradlew :idea-plugin:runIde   # sandbox IDE with the plugin
```

Releasing (plugin, Maven Central, listing screenshots and video) is described in [RELEASE.md](RELEASE.md) and
[tools/listing](tools/listing/README.md).

© 2026 Khubaib Khan. All rights reserved.
