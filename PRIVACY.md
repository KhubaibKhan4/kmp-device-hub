# Privacy Policy: KMP Device Hub

_Effective October 19, 2026._

This policy covers the **KMP Device Hub** plugin for JetBrains IDEs and the **devicehub-network** library
(`io.github.khubaibkhan4:devicehub-network`), both published by Khubaib Khan.

## Summary

KMP Device Hub does **not** collect, transmit, sell or share any personal data. It has no analytics, telemetry,
crash reporting or advertising. Everything it does happens on your computer and on the devices and simulators you
connect to it.

## What the plugin does on your computer

- **Device tools.** To list and control devices, the plugin runs the Android SDK tools (`adb`, `emulator`) and, on
  macOS, Xcode's `xcrun simctl`. These run locally and only on the devices you choose.
- **Project files.** It reads your project's build files, `AndroidManifest.xml`, `Info.plist` and Xcode configuration to
  find your app's id and deep links. Nothing is uploaded.
- **Settings you save.** Your chosen app ids, saved deep links, test presets and Network Inspector rules are stored in
  the IDE's own settings on your computer.
- **Files you create.** Screenshots are saved to `~/Pictures/KMP Device Hub`, screen recordings to
  `~/Movies/KMP Device Hub`, and HAR exports to the location you pick.
- **Connectivity check.** To show whether a device is online, the plugin sends one standard `ping` to `8.8.8.8`
  (Google Public DNS) from the device, or from your Mac for iOS simulators. No data is attached.

## Network Inspector and the devicehub-network library

- The plugin listens only on **`127.0.0.1:38383`** (your own computer), never on an outside address.
- When you add `devicehub-network` to your app, it sends a description of your app's HTTP calls (method, URL,
  headers, bodies up to 128 KB, status, timing) **only to that local address**. Android devices reach it through
  `adb reverse` over the USB or emulator connection. iOS simulators reach it directly on your Mac.
- Captured calls are kept **in memory only** (at most 2,000) and disappear when the IDE closes, unless you export them.
- You control what's captured: `redactHeaders` hides header values such as `Authorization`, and `enabled = false`
  turns the library off. **Only enable it in debug builds**, because it forwards your app's traffic to whatever
  listens on that port of the device.

## Licensing and purchases

If you start a trial or buy a Pro license, JetBrains handles it through JetBrains Marketplace and your JetBrains
Account, under [JetBrains' privacy policy](https://www.jetbrains.com/legal/docs/privacy/privacy/). The plugin only
asks the IDE whether a license is present. It never sees your payment or account details.

## Third parties

The plugin itself sends nothing to third parties. Opening the Marketplace page (for example, "Start Free Trial")
opens JetBrains' website in your browser.

## Children

The plugin is a developer tool and isn't directed at children.

## Changes

If this policy changes, the new version will be published with the plugin and on its JetBrains Marketplace page, with
a new effective date.

## Contact

Questions about this policy: contact the vendor (Khubaib Khan) through the plugin's JetBrains Marketplace page.
