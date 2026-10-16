package io.github.kmpdevicehub.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresetsTest {
    private val tools = ToolPaths("/adb", "/emulator", "/xcrun")
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37, "Pixel_9")
    private val sim = Device("UDID", "iPhone 18 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")

    /** Every command succeeds; airplane mode reads as off. */
    private class OkRunner : CommandRunner {
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            ran += command.joinToString(" ")
            return CommandResult(0, if ("airplane-mode" == command.last()) "disabled" else "OK", "")
        }
        override fun spawn(command: List<String>) {}
        override fun start(command: List<String>): RunningProcess? = null
    }

    @Test
    fun `presets round-trip through their saved form`() {
        TestPreset.BUILT_IN.forEach { assertEquals(it.copy(builtIn = false), TestPreset.decode(it.encode())) }
        val custom = TestPreset("Mine | odd", darkMode = true, language = "ur")
        assertEquals("Mine / odd", TestPreset.decode(custom.encode())!!.name)
        assertEquals(null, TestPreset.decode("garbage"))
        assertEquals("dark, Urdu (RTL)", custom.summary)
    }

    @Test
    fun `applying a preset runs each part and skips what the device can't do`() {
        val runner = OkRunner()
        val presets = PresetRunner(DeviceSettings(runner, tools), DeviceSimulation(runner, tools))
        val all = TestPreset("All", darkMode = true, textSize = TextSize.LARGEST, language = "ar", network = NetworkProfile.EDGE, cleanStatusBar = true)

        val android = presets.apply(emu, "com.example.app", all)
        assertTrue(android.ok, android.message)
        assertTrue(runner.ran.containsAll(listOf(
            "/adb -s emulator-5554 shell cmd uimode night yes",
            "/adb -s emulator-5554 shell settings put system font_scale 2.0",
            "/adb -s emulator-5554 shell cmd locale set-app-locales com.example.app --locales ar",
            "/adb -s emulator-5554 emu network speed edge",
        )))

        val ios = presets.apply(sim, "", all)
        assertTrue(ios.ok)
        assertContains(ios.message, "Skipped language (no app chosen), network (iOS uses the Mac's network)")
        assertFalse(presets.apply(sim.copy(state = DeviceState.STOPPED), "x.y", all).ok)
    }

    @Test
    fun `batch runs on every device and names the ones that failed`() {
        val ok = Batch.run(listOf(emu, sim)) { ActionResult.done("fine") }
        assertEquals("Done on all 2 devices.", ok.message)
        val mixed = Batch.run(listOf(emu, sim)) { if (it.platform == DevicePlatform.IOS) ActionResult.failed("Nope.") else ActionResult.done("fine") }
        assertEquals("1 of 2 done. iPhone 18 Pro: Nope.", mixed.message)
        assertEquals("fine", Batch.run(listOf(emu)) { ActionResult.done("fine") }.message, "one device keeps its own message")
        assertFalse(Batch.run(emptyList()) { ActionResult.done("x") }.ok)
    }

    @Test
    fun `deep links are found in manifests and Info plist`() {
        val manifest = """
            <activity android:name=".MainActivity">
              <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
              </intent-filter>
              <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="myapp" android:host="orders" />
                <data android:scheme="https" android:host="example.com" android:pathPrefix="/orders" />
              </intent-filter>
              <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <data android:scheme="https" />
              </intent-filter>
            </activity>
        """.trimIndent()
        val plist = "<key>CFBundleURLTypes</key><array><dict><key>CFBundleURLSchemes</key>\n<array>\n<string>myapp</string>\n<string>\$(PRODUCT_BUNDLE_IDENTIFIER)</string></array></dict></array>"
        assertEquals(
            listOf("myapp://orders", "https://example.com/orders", "myapp://"),
            LinkDetector.fromContents(listOf(manifest), listOf(plist)),
        )
    }
}
