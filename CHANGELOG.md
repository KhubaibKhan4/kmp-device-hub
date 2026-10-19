# Changelog

## 0.2.0

- New KMP Logs window: your app's logs from Android (logcat, filtered to your app and following restarts) and iOS simulators (the unified log, or the app's println output), with level filter, search and color.
- Install apps: drop an .apk or simulator .app on a device, or use Install App… (opens at your latest build). Clear messages for the usual install failures.
- Simulate battery level and charging, rotate Android screens, send matching or failing Face ID / Touch ID / fingerprint, add photos and videos to the gallery, and copy text to or from a simulator's clipboard.
- Network Inspector: the JSON tree opens three levels deep; "Start Free Trial" opens the Marketplace listing.

## 0.1.2

- Fixed: changing your app's language on Android now restarts the app in that language (like on iOS), instead of leaving it in the old language until you restart it yourself.
- Fixed: choosing "Device language" on Android now really resets the app to the device's language.

## 0.1.1

- Fixed: tab titles in the Devices panel (App, Settings, Permissions…) were blank in the new UI; it now uses the IDE's own tabs, with an overflow menu when the panel is narrow.
- Search devices by name or OS, and filter by platform (Android / iOS).
- A header for the selected device, with Start / Shut down and Screenshot buttons, so it's always clear which device the actions apply to.
- Drag the divider between the device list and the tabs; its position is remembered.
- App tab: a green check when your app is installed, and Clear data / Uninstall set apart from Launch / Stop.

## 0.1.0

- Devices tool window: every Android device, emulator and iOS simulator in one list.
- Start and shut down emulators and simulators; double-click a stopped device to start it.
- Detects your app's Android applicationId and iOS bundle id from the project.
- Launch, stop, clear data and uninstall your app on the selected device, with confirmation for destructive actions.
- Permissions tab: see, grant, revoke and reset your app's permissions on Android and iOS.
- Settings tab: dark mode, text size, and running your app in another language (RTL included).
- Links tab: open deep links and URLs on the selected device.
- Screenshot button: saves to ~/Pictures/KMP Device Hub and copies the image to the clipboard.
- Wi-Fi debugging: pair and connect Android devices over Wi-Fi.
- Pro: apply settings, links, launches, presets and pushes to all running devices at once.
- Pro: test presets (dark + largest text, right-to-left, long words, slow network, offline, store screenshots) and your own.
- Pro: screen recording to ~/Movies/KMP Device Hub.
- Pro: push notification tester.
- Pro: deep link library, including links declared in AndroidManifest.xml and Info.plist.
- Pro: simulated location, Android emulator network conditions and a clean 9:41 status bar.
- Deep links with `&` or spaces now open correctly on Android.
- Network Inspector: a KMP Network tool window listing every HTTP call your app makes, with headers, pretty-printed bodies, a JSON tree, errors, timings, totals, throughput and device connectivity.
- devicehub-network library: a Ktor client plugin (Android, iOS, JVM) and an OkHttp interceptor; Android devices connect automatically through adb reverse.
- Copy any call as cURL, URL or response body.
- Pro: mock responses, fail requests or add latency with rules; export HAR.
- Network demo sample app (Android + iOS simulator) in samples/network-demo.
- Network Inspector shows emulators by their AVD name and iOS network errors as one readable line.
