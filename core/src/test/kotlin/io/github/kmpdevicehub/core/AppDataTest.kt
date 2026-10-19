package io.github.kmpdevicehub.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDataTest {
    private val adb = "/adb"
    private val xcrun = "/xcrun"
    private val tools = ToolPaths(adb, null, xcrun)
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37)
    private val udid = "0A1B2C3D-0000-1111-2222-333344445555"
    private val sim = Device(udid, "iPhone 17", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val app = "com.example.shop"
    private fun ok(out: String = "") = CommandResult(0, out, "")

    @Test
    fun `finds the data root and explains non-debuggable builds`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell run-as $app pwd" to ok("/data/user/0/$app\n"),
            "$adb -s emulator-5554 shell run-as com.release.app pwd" to CommandResult(1, "run-as: package not debuggable: com.release.app", ""),
            "$adb -s emulator-5554 shell run-as com.android.settings pwd" to CommandResult(1, "run-as: package not an application: com.android.settings", ""),
            "$adb -s emulator-5554 shell run-as com.missing pwd" to CommandResult(1, "run-as: unknown package: com.missing", ""),
            "$xcrun simctl get_app_container $udid $app data" to ok("/Users/me/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Data/Application/AAAA\n"),
        ))
        val data = AppDataExplorer(runner, tools)
        assertEquals("/data/user/0/$app", data.root(emu, app).second)
        assertContains(data.root(emu, "com.release.app").first.message, "isn't a debuggable build")
        assertContains(data.root(emu, "com.android.settings").first.message, "system app")
        assertContains(data.root(emu, "com.missing").first.message, "isn't installed")
        val bogus = File.createTempFile("pulled", ".db-wal").apply { writeText("cat: shop.db-wal: No such file or directory") }
        assertEquals("cat: shop.db-wal: No such file or directory", AppDataExplorer.pullError(bogus))
        assertEquals(null, AppDataExplorer.pullError(File.createTempFile("pulled", ".db").apply { writeBytes(ByteArray(2048)) }))
        assertTrue(data.root(sim, app).second!!.endsWith("/Application/AAAA"))
    }

    @Test
    fun `parses Android listings`() {
        val ls = """
            total 40
            drwxrwx--x  6 u0_a190 u0_a190 4096 2026-10-19 12:00 .
            drwxrwx--x  4 system  system  4096 2026-10-19 11:00 ..
            drwxrws--x  2 u0_a190 u0_a190_cache 4096 2026-10-19 12:00 cache
            drwxrwx--x  2 u0_a190 u0_a190 4096 2026-10-19 12:01 databases
            -rw-rw----  1 u0_a190 u0_a190  812 2026-10-19 12:02 notes file.txt
            lrwxrwxrwx  1 root    root       32 2026-10-19 11:00 lib -> /data/app/x/lib/arm64
        """.trimIndent()
        val entries = AppDataExplorer.parseLs(ls, "/data/user/0/$app")
        assertEquals(listOf("cache", "databases", "lib", "notes file.txt"), entries.map { it.name })
        val notes = entries.last()
        assertEquals(812, notes.size)
        assertEquals("/data/user/0/$app/notes file.txt", notes.path)
        assertEquals(DataKind.TEXT, notes.kind)
        assertEquals(DataKind.PREFERENCES, DataEntry("p.xml", "/d/shared_prefs/p.xml", false, 1, "").kind)
        assertEquals(DataKind.DATABASE, DataEntry("shop.db", "/d/databases/shop.db", false, 1, "").kind)
        assertEquals(DataKind.TEXT, DataEntry("layout.xml", "/d/files/layout.xml", false, 1, "").kind)
    }

    @Test
    fun `reads SharedPreferences and plists`() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="auth_token">abc&amp;123</string>
                <int name="launch_count" value="7" />
                <boolean name="onboarded" value="true" />
                <set name="tags"><string>tea</string><string>cake</string></set>
                <float name="volume" value="0.5" />
            </map>"""
        assertEquals(listOf(
            PrefEntry("auth_token", "string", "abc&123"), PrefEntry("launch_count", "int", "7"), PrefEntry("onboarded", "boolean", "true"),
            PrefEntry("tags", "set", "[tea, cake]"), PrefEntry("volume", "float", "0.5"),
        ), AppDataExplorer.parseSharedPreferences(xml))

        val plistXml = """<plist version="1.0"><dict>
            <key>lastSync</key><date>2026-10-19T07:00:00Z</date>
            <key>darkMode</key><true/>
            <key>name</key><string>Sara</string>
        </dict></plist>"""
        assertEquals(listOf(PrefEntry("darkMode", "boolean", "true"), PrefEntry("lastSync", "date", "2026-10-19T07:00:00Z"), PrefEntry("name", "string", "Sara")),
            AppDataExplorer.parsePlistXml(plistXml))
    }

    @Test
    fun `lists, reads and deletes inside an iOS simulator container`() {
        val base = Files.createTempDirectory("sim").toFile()
        val container = File(base, "CoreSimulator/Devices/$udid/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666")
        File(container, "Documents/notes.txt").apply { parentFile.mkdirs(); writeText("hello") }
        File(container, "Library/Preferences").mkdirs()
        val runner = FakeRunner(mapOf("$xcrun simctl get_app_container $udid $app data" to ok(container.path + "\n")))
        val data = AppDataExplorer(runner, tools)
        val listing = data.list(sim, app, container.path)
        assertEquals(listOf("Documents", "Library"), listing.entries.map { it.name })
        assertEquals("hello", data.readText(sim, app, "${container.path}/Documents/notes.txt"))
        assertTrue(data.delete(sim, app, "${container.path}/Documents/notes.txt").ok)
        assertFalse(File(container, "Documents/notes.txt").exists())
        assertFalse(data.delete(sim, app, base.path).ok, "never outside the container")
        assertTrue(base.exists())
    }

    @Test
    fun `queries SQLite snapshots, with blobs shown by size`() {
        val sqlite = listOf("/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3").firstOrNull { File(it).canExecute() } ?: return
        val db = File.createTempFile("shop", ".db").apply { delete() }
        ProcessCommandRunner().run(listOf(sqlite, db.path,
            "PRAGMA journal_mode=WAL; CREATE TABLE orders(id INTEGER PRIMARY KEY, item TEXT, price REAL, photo BLOB); " +
                "INSERT INTO orders VALUES (1, 'Tea', 4.5, x'010203'), (2, 'Cake', NULL, NULL); CREATE TABLE \"user settings\"(k TEXT);"))
        val data = AppDataExplorer(ProcessCommandRunner(), tools, sqlite)
        assertEquals(listOf("orders", "user settings"), data.tables(db).second)
        val (result, table) = data.tableRows(db, "orders")
        assertTrue(result.ok, result.message)
        assertEquals(listOf("id", "item", "price", "photo"), table.columns)
        assertEquals(listOf(listOf("1", "Tea", "4.5", "<blob 3 bytes>"), listOf("2", "Cake", null, null)), table.rows)
        assertEquals(2L, table.total)
        assertEquals(listOf(listOf("Tea")), data.query(db, "SELECT item FROM orders WHERE price > 1").second.rows)
        assertFalse(data.query(db, "DELETE FROM orders").first.ok, "snapshots are read-only")
        assertContains(data.query(db, "SELEC nope").first.message, "syntax")
    }
}
