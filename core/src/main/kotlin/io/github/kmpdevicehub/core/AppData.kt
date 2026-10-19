package io.github.kmpdevicehub.core

import java.io.File

/** A file or folder in an app's data area. [path] is on the device (Android) or on the Mac (iOS simulator). */
data class DataEntry(val name: String, val path: String, val isDirectory: Boolean, val size: Long, val modified: String) {
    val kind: DataKind get() = DataKind.of(path, isDirectory)
}

enum class DataKind {
    FOLDER, PREFERENCES, DATABASE, TEXT, IMAGE, OTHER;

    companion object {
        private val DATABASE_EXT = setOf("db", "sqlite", "sqlite3", "sqlitedb")
        private val TEXT_EXT = setOf("json", "txt", "xml", "log", "csv", "properties", "yaml", "yml", "html", "md")
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "heic")

        /** Classifies by path: SharedPreferences are the XML files in `shared_prefs/`. */
        fun of(path: String, isDirectory: Boolean): DataKind {
            if (isDirectory) return FOLDER
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return when {
                ext == "plist" -> PREFERENCES
                ext == "xml" && "/shared_prefs/" in path -> PREFERENCES
                ext in DATABASE_EXT -> DATABASE
                ext in TEXT_EXT -> TEXT
                ext in IMAGE_EXT -> IMAGE
                else -> OTHER
            }
        }
    }
}

/** A folder listing, or why there isn't one. */
data class DataListing(val ok: Boolean, val message: String, val entries: List<DataEntry> = emptyList())

/** One preference: key, type (string, int, boolean, …) and value as text. */
data class PrefEntry(val key: String, val type: String, val value: String)

/** Query results: column names and rows of display text (null for SQL NULL). */
data class SqlTable(val columns: List<String>, val rows: List<List<String?>>, val total: Long? = null)

/**
 * Browses an app's private data: on Android through `run-as` (debuggable builds only, as in Android Studio's Device
 * Explorer), on iOS simulators directly in the app's data container on the Mac. Reads SharedPreferences / NSUserDefaults
 * and SQLite databases (Room, SQLDelight, Core Data) from a local snapshot.
 */
class AppDataExplorer(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
    private val sqlite3: String? = listOf("/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3", "/usr/local/bin/sqlite3").firstOrNull { File(it).canExecute() },
) {
    // ---- Where the app's data lives -------------------------------------------------------------------------

    /** The app's data root: `/data/data/<app>` (Android) or its simulator data container (iOS). */
    fun root(device: Device, appId: String): Pair<ActionResult, String?> {
        if (!device.isRunning) return ActionResult.failed("Start ${device.name} first.") to null
        if (appId.isBlank()) return ActionResult.failed("Choose an app first.") to null
        return when (device.platform) {
            DevicePlatform.ANDROID -> {
                val adb = tools.adb ?: return ActionResult.failed("adb not found.") to null
                val r = runner.run(listOf(adb, "-s", device.id, "shell", "run-as", appId, "pwd"))
                val path = r.stdout.trim()
                when {
                    r.ok && path.startsWith("/") -> ActionResult.done("") to path
                    "not debuggable" in r.stdout + r.stderr ->
                        ActionResult.failed("$appId isn't a debuggable build. Android only lets debug builds' data be read (run-as).") to null
                    "not an application" in r.stdout + r.stderr ->
                        ActionResult.failed("$appId is a system app. Only debuggable builds of your own apps can be explored.") to null
                    "unknown package" in r.stdout + r.stderr ->
                        ActionResult.failed("$appId isn't installed on ${device.name}.") to null
                    else -> ActionResult.failed("Couldn't open $appId's data: ${r.errorSummary}") to null
                }
            }
            DevicePlatform.IOS -> {
                val xcrun = tools.xcrun ?: return ActionResult.failed("Xcode not found.") to null
                val r = runner.run(listOf(xcrun, "simctl", "get_app_container", device.id, appId, "data"))
                if (r.ok) ActionResult.done("") to r.stdout.trim().trimEnd('/') else ActionResult.failed("$appId isn't installed on ${device.name}.") to null
            }
        }
    }

    // ---- Listing, reading, copying, deleting ------------------------------------------------------------------

    fun list(device: Device, appId: String, path: String): DataListing = when (device.platform) {
        DevicePlatform.ANDROID -> {
            val adb = tools.adb
            if (adb == null) DataListing(false, "adb not found.") else {
                val r = runner.run(listOf(adb, "-s", device.id, "shell", "run-as", appId, "ls", "-la", shellQuote(path)))
                if (!r.ok && r.stdout.isBlank()) DataListing(false, "Couldn't list $path: ${r.errorSummary}")
                else DataListing(true, "", parseLs(r.stdout, path))
            }
        }
        DevicePlatform.IOS -> {
            val dir = File(path)
            val children = dir.listFiles()
            if (children == null) DataListing(false, "Couldn't list $path.")
            else DataListing(true, "", children.map { f ->
                DataEntry(f.name, f.path, f.isDirectory, if (f.isDirectory) 0 else f.length(),
                    java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16))
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
        }
    }

    /** Copies a file to [target] on this computer. On Android it goes through `run-as cat`, so private files work. */
    fun pull(device: Device, appId: String, path: String, target: File): ActionResult {
        target.parentFile?.mkdirs()
        return when (device.platform) {
            DevicePlatform.ANDROID -> {
                val adb = tools.adb ?: return ActionResult.failed("adb not found.")
                // exec-out keeps the bytes intact; the shell redirect writes them to the local file.
                val r = runner.run(listOf("/bin/sh", "-c", "\"\$1\" -s \"\$2\" exec-out run-as \"\$3\" cat \"\$4\" > \"\$5\"",
                    "sh", adb, device.id, appId, shellQuote(path), target.path), timeoutSeconds = 120)
                // exec-out sends cat's errors to stdout and still exits 0, so they'd land in the file.
                val error = pullError(target)
                when {
                    error != null -> { target.delete(); ActionResult.failed("Couldn't copy $path: $error") }
                    r.ok && target.exists() -> ActionResult.done(target.path)
                    else -> ActionResult.failed("Couldn't copy $path: ${r.errorSummary}")
                }
            }
            DevicePlatform.IOS -> runCatching {
                File(path).copyTo(target, overwrite = true); ActionResult.done(target.path)
            }.getOrElse { ActionResult.failed("Couldn't copy $path: ${it.message}") }
        }
    }

    /** Text content (first [maxBytes]), or null if it can't be read. */
    fun readText(device: Device, appId: String, path: String, maxBytes: Int = 512 * 1024): String? {
        val tmp = File.createTempFile("kmpdevicehub-read", ".txt")
        return try {
            if (!pull(device, appId, path, tmp).ok) null
            else tmp.readBytes().let { bytes -> String(bytes, 0, minOf(bytes.size, maxBytes), Charsets.UTF_8) }
        } finally {
            tmp.delete()
        }
    }

    fun delete(device: Device, appId: String, path: String): ActionResult {
        val name = path.substringAfterLast('/')
        return when (device.platform) {
            DevicePlatform.ANDROID -> {
                val adb = tools.adb ?: return ActionResult.failed("adb not found.")
                val r = runner.run(listOf(adb, "-s", device.id, "shell", "run-as", appId, "rm", "-rf", shellQuote(path)))
                if (r.ok && r.stdout.isBlank()) ActionResult.done("Deleted $name.") else ActionResult.failed("Couldn't delete $name: ${r.errorSummary}")
            }
            DevicePlatform.IOS -> {
                val (rootResult, root) = root(device, appId)
                when {
                    !rootResult.ok || root == null -> rootResult
                    // Only ever inside this app's own simulator data container.
                    !DeviceActions.isSimulatorDataContainer(root, device.id) || !File(path).canonicalPath.startsWith(File(root).canonicalPath + "/") ->
                        ActionResult.failed("Refusing to delete outside the app's data container.")
                    File(path).deleteRecursively() -> ActionResult.done("Deleted $name.")
                    else -> ActionResult.failed("Couldn't delete $name.")
                }
            }
        }
    }

    // ---- Preferences ------------------------------------------------------------------------------------------

    /** SharedPreferences XML (Android) or a plist such as NSUserDefaults (iOS), as key / type / value rows. */
    fun preferences(device: Device, appId: String, path: String): Pair<ActionResult, List<PrefEntry>> {
        val tmp = File.createTempFile("kmpdevicehub-prefs", if (path.endsWith(".plist")) ".plist" else ".xml")
        try {
            val pulled = pull(device, appId, path, tmp)
            if (!pulled.ok) return pulled to emptyList()
            return if (path.endsWith(".plist")) plist(tmp) else ActionResult.done("") to parseSharedPreferences(tmp.readText())
        } finally {
            tmp.delete()
        }
    }

    private fun plist(file: File): Pair<ActionResult, List<PrefEntry>> {
        val r = runner.run(listOf("/usr/bin/plutil", "-convert", "json", "-r", "-o", "-", file.path))
        if (!r.ok) {
            // Plists with dates or data can't become JSON; fall back to the readable XML form.
            val xml = runner.run(listOf("/usr/bin/plutil", "-convert", "xml1", "-o", "-", file.path))
            return if (xml.ok) ActionResult.done("") to parsePlistXml(xml.stdout) else ActionResult.failed("Couldn't read the plist: ${r.errorSummary}") to emptyList()
        }
        val map = runCatching { Json.parse(r.stdout) as? Map<*, *> }.getOrNull() ?: return ActionResult.failed("Couldn't read the plist.") to emptyList()
        return ActionResult.done("") to map.entries.map { (k, v) -> PrefEntry(k.toString(), typeOf(v), display(v)) }.sortedBy { it.key.lowercase() }
    }

    // ---- SQLite -----------------------------------------------------------------------------------------------

    /** Copies a database and its WAL / journal files, so recent writes (Room uses WAL) are included. */
    fun snapshotDatabase(device: Device, appId: String, path: String, dir: File): Pair<ActionResult, File?> {
        dir.mkdirs()
        val main = File(dir, path.substringAfterLast('/'))
        val result = pull(device, appId, path, main)
        if (!result.ok) return result to null
        // Only the side files that exist: SQLite treats a bogus journal as corruption.
        val siblings = when (device.platform) {
            DevicePlatform.ANDROID -> list(device, appId, path.substringBeforeLast('/')).entries.map { it.name }.toSet()
            DevicePlatform.IOS -> File(path).parentFile?.list()?.toSet().orEmpty()
        }
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val name = path.substringAfterLast('/') + suffix
            if (name !in siblings) continue
            val side = File(dir, main.name + suffix)
            if (!pull(device, appId, path + suffix, side).ok || side.length() == 0L) side.delete()
        }
        return ActionResult.done("") to main
    }

    fun tables(db: File): Pair<ActionResult, List<String>> {
        val sqlite = sqlite3 ?: return noSqlite() to emptyList()
        val r = runner.run(sqliteCommand(sqlite, db,
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name"))
        if (!r.ok) return ActionResult.failed("Couldn't open the database: ${r.errorSummary}") to emptyList()
        return ActionResult.done("") to rows(r.stdout).mapNotNull { it["name"] as? String }
    }

    /** First [limit] rows of a table, with blobs shown as their size. */
    fun tableRows(db: File, table: String, limit: Int = 500): Pair<ActionResult, SqlTable> {
        val sqlite = sqlite3 ?: return noSqlite() to SqlTable(emptyList(), emptyList())
        val quoted = "\"" + table.replace("\"", "\"\"") + "\""
        val info = runner.run(sqliteCommand(sqlite, db, "PRAGMA table_info($quoted)"))
        val columns = rows(info.stdout).mapNotNull { it["name"] as? String }
        if (columns.isEmpty()) return ActionResult.failed("Couldn't read $table: ${info.errorSummary}") to SqlTable(emptyList(), emptyList())
        val select = columns.joinToString(", ") { c ->
            val q = "\"" + c.replace("\"", "\"\"") + "\""
            "CASE WHEN typeof($q) = 'blob' THEN '<blob ' || length($q) || ' bytes>' ELSE $q END AS $q"
        }
        val data = runner.run(sqliteCommand(sqlite, db, "SELECT $select FROM $quoted LIMIT $limit"))
        if (!data.ok) return ActionResult.failed(data.errorSummary) to SqlTable(columns, emptyList())
        val count = runner.run(sqliteCommand(sqlite, db, "SELECT count(*) FROM $quoted", json = false)).stdout.trim().toLongOrNull()
        return ActionResult.done("") to SqlTable(columns, rows(data.stdout).map { row -> columns.map { c -> row[c]?.let(::display) } }, count)
    }

    /** Runs any SQL on the read-only snapshot (changes are refused). */
    fun query(db: File, sql: String, limit: Int = 1000): Pair<ActionResult, SqlTable> {
        val sqlite = sqlite3 ?: return noSqlite() to SqlTable(emptyList(), emptyList())
        if (sql.isBlank()) return ActionResult.failed("Type a query.") to SqlTable(emptyList(), emptyList())
        val r = runner.run(sqliteCommand(sqlite, db, sql.trim(), "-cmd", ".timeout 2000"))
        if (!r.ok) return ActionResult.failed(r.errorSummary.removePrefix("Error: ").removePrefix("Parse error: ")) to SqlTable(emptyList(), emptyList())
        val parsed = rows(r.stdout).take(limit)
        val columns = parsed.firstOrNull()?.keys?.map { it.toString() }.orEmpty()
        return ActionResult.done("${parsed.size} row(s)") to SqlTable(columns, parsed.map { row -> columns.map { c -> row[c]?.let(::display) } })
    }

    /**
     * The snapshot is our own copy, so it's opened normally (a WAL database can't be opened `-readonly` without its
     * shared-memory file) and kept read-only with `PRAGMA query_only`.
     */
    private fun sqliteCommand(sqlite: String, db: File, sql: String, vararg extra: String, json: Boolean = true): List<String> =
        listOf(sqlite) + (if (json) listOf("-json") else emptyList()) + listOf("-cmd", "PRAGMA query_only = ON") + extra + listOf(db.path, sql)

    private fun noSqlite() = ActionResult.failed("sqlite3 not found. It comes with macOS; on Linux install the sqlite3 package.")

    companion object {
        /** The error text when a pulled file holds a `cat` / `run-as` error instead of content. */
        internal fun pullError(file: File): String? {
            if (!file.isFile || file.length() > 512) return null
            val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
            return text.takeIf { it.startsWith("cat: ") || it.startsWith("run-as: ") }
        }

        private val LS = Regex("""^([dl-])[rwxsStT-]{9}\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d\d-\d\d)\s+(\d\d:\d\d)\s+(.+)$""")

        internal fun parseLs(output: String, dir: String): List<DataEntry> = output.lines().mapNotNull { line ->
            val m = LS.find(line.trim()) ?: return@mapNotNull null
            val (type, size, date, time, rawName) = m.destructured
            val name = rawName.substringBefore(" -> ")
            if (name == "." || name == "..") return@mapNotNull null
            DataEntry(name, dir.trimEnd('/') + "/" + name, type == "d", size.toLong(), "$date $time")
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        /** `<map><string name="k">v</string><int name="n" value="1"/><set name="s"><string>a</string></set></map>`. */
        internal fun parseSharedPreferences(xml: String): List<PrefEntry> {
            val entries = mutableListOf<PrefEntry>()
            Regex("""<(string|int|long|float|boolean|set)\s+name="([^"]*)"(?:\s+value="([^"]*)")?\s*(/>|>([\s\S]*?)</\1>)""").findAll(xml).forEach { m ->
                val (type, name, value, _, body) = m.destructured
                val text = when (type) {
                    "set" -> Regex("""<string>([\s\S]*?)</string>""").findAll(body).map { unescape(it.groupValues[1]) }.joinToString(", ", "[", "]")
                    "string" -> unescape(body)
                    else -> value
                }
                entries += PrefEntry(unescape(name), type, text)
            }
            return entries.sortedBy { it.key.lowercase() }
        }

        internal fun parsePlistXml(xml: String): List<PrefEntry> {
            val body = xml.substringAfter("<dict>", "").substringBeforeLast("</dict>")
            return Regex("""<key>([\s\S]*?)</key>\s*(<(\w+)\s*/>|<(\w+)>([\s\S]*?)</\4>)""").findAll(body).map { m ->
                val key = unescape(m.groupValues[1])
                val selfClosing = m.groupValues[3]
                if (selfClosing.isNotEmpty()) PrefEntry(key, "boolean", selfClosing)
                else PrefEntry(key, m.groupValues[4], unescape(m.groupValues[5].trim()).replace(Regex("""\s+"""), " ").take(500))
            }.toList().sortedBy { it.key.lowercase() }
        }

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        @Suppress("UNCHECKED_CAST")
        private fun rows(json: String): List<Map<String, Any?>> =
            (runCatching { Json.parse(json.trim().ifEmpty { "[]" }) }.getOrNull() as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }

        private fun typeOf(v: Any?) = when (v) {
            null -> "null"; is String -> "string"; is Boolean -> "boolean"; is Long, is Int -> "integer"; is Number -> "real"
            is List<*> -> "array"; is Map<*, *> -> "dictionary"; else -> v::class.simpleName.orEmpty()
        }

        private fun display(v: Any?): String = when (v) {
            is Map<*, *>, is List<*> -> Json.write(v)
            is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            else -> v.toString()
        }
    }
}
