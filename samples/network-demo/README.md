# Network demo

A small Kotlin Multiplatform app for trying the KMP Device Hub **Network Inspector** on a real Android emulator and
iOS simulator. It uses Ktor with `DeviceHubNetwork` and makes the calls a typical app makes, in two rounds 10 seconds
apart: a JSON GET, an authenticated POST (its `Authorization` header is redacted), a 404 and an unreachable host.
Add a mock rule in the IDE during the first round and you'll see it in the second.

| Module | What it is |
|---|---|
| `shared` | The calls (`DemoCalls`), for jvm (used by Android) and iOS simulator |
| `androidApp` | An Android app that runs them and shows the results |

## Run it

Publish the library first, from the repository root:

```bash
./gradlew :network:publishToMavenLocal
```

**Android** (open the KMP Network tool window first; it sets up `adb reverse` for you):

```bash
./gradlew -p samples/network-demo :androidApp:installDebug
adb shell am start -n io.github.kmpdevicehub.demo/.MainActivity
```

**iOS simulator**: the iOS side is a command-line program run inside a booted simulator, so no Xcode project is
needed:

```bash
./gradlew -p samples/network-demo :shared:linkDebugExecutableIosSimulatorArm64
xcrun simctl spawn booted samples/network-demo/shared/build/bin/iosSimulatorArm64/debugExecutable/shared.kexe
```

The calls appear in **KMP Network** as they happen.
