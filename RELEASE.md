# Releasing KMP Device Hub

## 0.1.0: free early access

Ship Milestones 1–5 free (every Pro feature on) to build an audience, while the paid plan is reviewed.

### Your steps (accounts and keys only you can create)

- [ ] **Sonatype Central Portal** account at central.sonatype.com; verify the `io.github.khubaibkhan4` namespace (GitHub login does this).
- [ ] **GPG key** for signing the library: `gpg --full-generate-key`, then publish it: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`.
- [ ] Store secrets with `gh secret set` (it prompts; never paste them in chat): `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`, `PUBLISH_TOKEN` (Marketplace).
- [ ] **Fix GitHub billing** so CI runs, and decide whether the repo goes public at launch.
- [ ] **Paid plan**: apply in JetBrains Marketplace → your vendor profile → Sales. They assign the product code used below.

### Build steps (Claude can do these)

- [x] Maven Central publishing for `network/` is set up (`./gradlew :network:publishToMavenCentral`, then release the deployment on central.sonatype.com). Needs `mavenCentralUsername` / `mavenCentralPassword` / `signing.gnupg.keyName` in `~/.gradle/gradle.properties`.
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

- [ ] Add JetBrains' signed-license verification to `ProLicense.isLicensed()` (today it only checks that a license stamp exists).
- [ ] Ship a no-op `devicehub-network` variant so release builds can depend on it safely.
- [ ] Bump `devicehub.releaseDate` / `devicehub.releaseVersion` in `gradle.properties`.
- [ ] Build with `./gradlew :idea-plugin:buildPlugin -Pdevicehub.productCode=<CODE>`; Pro then needs a license or the Marketplace trial.
