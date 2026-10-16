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

Coming next: a **Network Inspector** for your app's API calls (requests, JSON responses, errors, connectivity and
bandwidth), then unified logs and an app data explorer.

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
