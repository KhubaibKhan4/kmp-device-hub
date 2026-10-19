package io.github.kmpdevicehub.idea

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.AppDataExplorer
import io.github.kmpdevicehub.core.CommandResult
import io.github.kmpdevicehub.core.CommandRunner
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.ProcessCommandRunner
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.RunningProcess
import io.github.kmpdevicehub.core.ToolPaths
import java.io.File
import java.nio.file.Files

class AppDataPanelTest : BasePlatformTestCase() {
    private val udid = "0A1B2C3D-0000-1111-2222-333344445555"
    private val sim = Device(udid, "iPhone 17", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val sqlite = listOf("/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3").firstOrNull { File(it).canExecute() }

    /** simctl answers with a container on disk; plutil and sqlite3 really run. */
    private class ContainerRunner(private val container: File) : CommandRunner {
        private val real = ProcessCommandRunner()
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult =
            if (command.getOrNull(1) == "simctl") CommandResult(0, container.path + "\n", "") else real.run(command, timeoutSeconds)
        override fun spawn(command: List<String>) {}
        override fun start(command: List<String>): RunningProcess? = null
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    fun `test tool window is registered`() {
        assertTrue(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.any { it.id == "KMP App Data" && it.factoryClass == AppDataToolWindowFactory::class.java.name })
    }

    fun `test browses a simulator app's files, preferences and database`() {
        if (sqlite == null || !File("/usr/bin/plutil").exists()) return
        val base = Files.createTempDirectory("sim").toFile()
        val container = File(base, "CoreSimulator/Devices/$udid/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666")
        File(container, "Documents/cart.json").apply { parentFile.mkdirs(); writeText("""{"items":[{"id":101,"qty":2}]}""") }
        val prefs = File(container, "Library/Preferences/com.example.shop.plist").apply { parentFile.mkdirs() }
        File(base, "p.json").writeText("""{"onboarded":true,"launchCount":7,"userName":"Sara"}""")
        ProcessCommandRunner().run(listOf("/usr/bin/plutil", "-convert", "binary1", "-o", prefs.path, File(base, "p.json").path))
        val db = File(container, "Library/Application Support/shop.sqlite").apply { parentFile.mkdirs() }
        ProcessCommandRunner().run(listOf(sqlite, db.path, "CREATE TABLE orders(id INTEGER PRIMARY KEY, item TEXT); INSERT INTO orders VALUES (1,'Tea'),(2,'Cake');"))

        val explorer = AppDataExplorer(ContainerRunner(container), ToolPaths(null, null, "/usr/bin/xcrun"), sqlite)
        val panel = AppDataPanel(project, testRootDisposable, explorer, devices = { listOf(sim) },
            detectApps = { ProjectApps(emptyList(), listOf("com.example.shop")) }, confirm = { _, _ -> true })
        assertTrue(waitFor { panel.deviceCount == 1 })
        panel.selectDevice(udid)
        panel.setApp("com.example.shop")
        panel.open()
        assertTrue("files: ${panel.fileNames} ${panel.statusText}", waitFor { panel.fileNames == listOf("Documents", "Library") })

        panel.goForTest("Documents")
        assertTrue(waitFor { panel.fileNames == listOf("cart.json") })
        panel.selectFile("cart.json")
        assertTrue(waitFor { panel.textShown.contains("\"qty\": 2") })

        panel.open()
        assertTrue(waitFor { panel.fileNames == listOf("Documents", "Library") })
        panel.goForTest("Library")
        assertTrue(waitFor { "Preferences" in panel.fileNames })
        panel.goForTest("Preferences")
        assertTrue(waitFor { panel.fileNames == listOf("com.example.shop.plist") })
        panel.selectFile("com.example.shop.plist")
        assertTrue("prefs: ${panel.prefRows}", waitFor { panel.prefRows == listOf("launchCount=7", "onboarded=true", "userName=Sara") })

        panel.open()
        assertTrue(waitFor { panel.fileNames == listOf("Documents", "Library") })
        panel.goForTest("Library")
        assertTrue(waitFor { "Application Support" in panel.fileNames })
        panel.goForTest("Application Support")
        assertTrue(waitFor { panel.fileNames == listOf("shop.sqlite") })
        panel.selectFile("shop.sqlite")
        assertTrue("db: ${panel.databaseStatus}", waitFor { panel.tableNames == listOf("orders") && panel.gridRowCount == 2 })
        assertEquals(listOf("id", "item"), panel.gridColumns)
        panel.runSqlForTest("SELECT item FROM orders WHERE id = 2")
        assertTrue(waitFor { panel.gridRowCount == 1 && panel.gridColumns == listOf("item") })
    }
}
