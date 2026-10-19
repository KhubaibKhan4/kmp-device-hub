package io.github.kmpdevicehub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogsTest {
    private val adb = "/adb"
    private val xcrun = "/xcrun"
    private val tools = ToolPaths(adb, null, xcrun)
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37)
    private val sim = Device("U", "iPhone 17", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val app = "com.example.shop"
    private fun ok(out: String = "") = CommandResult(0, out, "")

    @Test
    fun `parses logcat threadtime lines`() {
        val e = assertNotNull(LogParser.logcat("10-19 12:34:56.789  4321  4350 E ShopRepository: Failed to load cart: timeout"))
        assertEquals(LogEntry("12:34:56.789", LogLevel.ERROR, "ShopRepository", "Failed to load cart: timeout", "4321"), e)
        assertEquals(LogLevel.DEBUG, LogParser.logcat("10-19 12:34:56.790  4321  4321 D Ktor    : REQUEST: https://api.example.com")!!.level)
        assertEquals("Ktor", LogParser.logcat("10-19 12:34:56.790  4321  4321 D Ktor    : REQUEST: https://api.example.com")!!.tag)
        assertEquals(LogLevel.ERROR, LogParser.logcat("10-19 12:00:00.000  1  1 F libc    : Fatal signal 6")!!.level)
        assertNull(LogParser.logcat("--------- beginning of main"))
    }

    @Test
    fun `parses Apple compact log lines`() {
        val line = "2026-10-19 12:34:56.789123+0500 Df iosApp[1234:5678] [com.example.shop:network] Loaded 3 products"
        assertEquals(LogEntry("12:34:56.789", LogLevel.INFO, "com.example.shop:network", "Loaded 3 products", "1234"), LogParser.apple(line))
        assertEquals(LogLevel.ERROR, LogParser.apple("2026-10-19 12:34:56.789 E  iosApp[1234:5678] Crash reporter ready")!!.level)
        assertEquals("iosApp", LogParser.apple("2026-10-19 12:34:56.789 E  iosApp[1234:5678] Crash reporter ready")!!.tag)
        assertNull(LogParser.apple("Filtering the log data using \"process == \\\"iosApp\\\"\""))
        assertEquals(LogLevel.ERROR, LogParser.console("Uncaught Kotlin exception: kotlin.IllegalStateException") { "t" }!!.level)
        assertEquals(LogEntry("t", LogLevel.INFO, "stdout", "Hello from println"), LogParser.console("Hello from println  ") { "t" })
    }

    @Test
    fun `streams one app's logs on Android and iOS`() {
        val runner = FakeRunner(
            mapOf(
                "$adb -s emulator-5554 shell pidof $app" to ok("4321\n"),
                "$xcrun simctl get_app_container U $app app" to ok("/sim/Containers/Bundle/Application/X/iosApp.app\n"),
                "/usr/bin/plutil -extract CFBundleExecutable raw /sim/Containers/Bundle/Application/X/iosApp.app/Info.plist" to ok("iosApp\n"),
            ),
            streams = mapOf(
                "$adb -s emulator-5554 logcat -v threadtime --pid=4321" to listOf(
                    "--------- beginning of main",
                    "10-19 12:00:00.001  4321  4321 I ActivityThread: Started",
                    "10-19 12:00:00.002  4321  4330 W Shop    : Slow response",
                ),
                "$xcrun simctl spawn U log stream --style compact --level debug --predicate process == \"iosApp\"" to listOf(
                    "Filtering the log data using \"process == \\\"iosApp\\\"\"",
                    "2026-10-19 12:00:00.100 Db iosApp[9:a] [shop:ui] Rendered home",
                ),
                "$xcrun simctl launch --console-pty --terminate-running-process U $app" to listOf("$app: 9", "println from Kotlin"),
            ),
        )
        val logs = LogStreamer(runner, tools)
        val android = mutableListOf<LogEntry>()
        assertEquals("4321", logs.androidPid(emu, app))
        assertNotNull(logs.streamAndroid(emu, "4321") { android += it })
        assertEquals(listOf("Started", "Slow response"), android.map { it.message })

        assertEquals("iosApp", logs.iosProcessName(sim, app))
        val ios = mutableListOf<LogEntry>()
        assertNotNull(logs.streamIos(sim, "iosApp") { ios += it })
        assertNotNull(logs.streamIosConsole(sim, app) { ios += it })
        assertEquals(listOf("Rendered home", "println from Kotlin"), ios.map { it.message })
        assertTrue(ios.first().level == LogLevel.DEBUG)
    }
}
