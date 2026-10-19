# Releasing KMP Device Hub

## 0.1.0: free early access

Ship Milestones 1–5 free (every Pro feature on) to build an audience, while the paid plan is reviewed.

### Your steps (accounts and keys only you can create)

- [x] **Sonatype Central Portal** account at central.sonatype.com; verify the `io.github.khubaibkhan4` namespace (GitHub login does this).
- [x] **GPG key** for signing the library: `gpg --full-generate-key`, then publish it: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
- [ ] Store secrets with `gh secret set` (it prompts; never paste them in chat): `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`, `PUBLISH_TOKEN` (Marketplace).
- [ ] **Fix GitHub billing** so CI runs, and decide whether the repo goes public at launch.
- [ ] **Paid plan**: apply in JetBrains Marketplace → your vendor profile → Sales. They assign the product code used below.

### Build steps (Claude can do these)

- [x] `io.github.khubaibkhan4:devicehub-network:0.1.0` published to Maven Central (2026-10-19). Publishing is set up (`./gradlew :network:publishToMavenCentral`, then release the deployment on central.sonatype.com). Needs `mavenCentralUsername` / `mavenCentralPassword` / `signing.gnupg.keyName` in `~/.gradle/gradle.properties`.
- [ ] Screenshots: the Devices panel (App, Settings, Simulate, Presets tabs), the KMP Network window, the iOS push banner, and the Arabic RTL screen.
- [ ] A 60–90 s demo video (same style as the Dependency Hub one).
- [ ] `./gradlew build :idea-plugin:verifyPlugin -Pdevicehub.verifyRecommended=true`, then `./gradlew :idea-plugin:buildPlugin` **without** `-Pdevicehub.productCode` (the free build).
- [ ] Upload `idea-plugin/build/distributions/kmp-device-hub-0.1.0.zip` to Marketplace and fill in the listing below.
- [ ] Tag `v0.1.0` and create a GitHub release with the zip.

## Marketplace listing

**Name:** KMP Device Hub

**Short description:** Every Android device, emulator and iOS simulator in one panel, plus a Network Inspector for your Kotlin Multiplatform app.

**Tags:** Kotlin Multiplatform, Compose Multiplatform, Android, iOS, Emulator, Simulator, Network, Testing

**Category:** Tools Integration (also Mobile)

**Description:** generated from `plugin.xml`; keep it in sync. Lead with:

> Stop switching between Android Studio's Device Manager, Xcode and the terminal. KMP Device Hub lists your emulators,
> phones and iOS simulators together, runs your app on them, flips dark mode, text size, language (RTL too) and
> permissions on both platforms, and shows every API call your app makes in a Network Inspector.

**What's new (0.1.0):** comes from the top section of `CHANGELOG.md` automatically.

## Paid release (after approval)

Applied for the paid plan on 2026-10-19.

- [x] JetBrains' signed-license verification (`LicenseVerifier`, ported from their reference code): license keys and
      license-server tickets must chain to JetBrains' roots; results are cached for 6 hours.
- [x] `devicehub-network-noop`: the same API, doing nothing, for release builds. Publish it to Maven Central with the
      library (`./gradlew :network-noop:publishToMavenCentral`, then Publish on central.sonatype.com).
- [ ] When JetBrains assigns the **product code** (capital letters only, starts with P, 4–15 characters):
  - Set `devicehub.version=1.0.0`, `devicehub.releaseVersion=10` and `devicehub.releaseDate` to the launch date
    (YYYYMMDD) in `gradle.properties`. Keep them for 1.0.x updates; changing them resets trials.
  - `./gradlew build :idea-plugin:verifyPlugin -Pdevicehub.verifyRecommended=true`
  - `./gradlew :idea-plugin:buildPlugin -Pdevicehub.productCode=<CODE>` and upload it.
- [ ] Android Studio: paid plugins need the free **JetBrains Marketplace Licensing Support** plugin. The Plugin Manager
      usually installs it with a paid plugin, but not always (and not for zips installed from disk). When it's missing,
      Pro features explain how to install it instead of offering a trial.
- [ ] JetBrains recommends obfuscating paid plugins before upload (e.g. Zelix KlassMaster); optional.
