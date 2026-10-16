package io.github.kmpdevicehub.core

import java.io.File

/** The app ids found in a project, best guess first. */
data class ProjectApps(val android: List<String>, val ios: List<String>) {
    fun forPlatform(platform: DevicePlatform) = if (platform == DevicePlatform.ANDROID) android else ios
}

/**
 * Finds the Android `applicationId` (Gradle files) and the iOS bundle id (Xcode project and `.xcconfig`, as in the
 * Kotlin Multiplatform wizard's `iosApp`) without running Gradle or Xcode.
 */
object AppDetector {
    private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".idea", ".kotlin", "node_modules", "Pods", "DerivedData", "xcuserdata", ".intellijPlatform")

    fun detect(root: File, maxDepth: Int = 5): ProjectApps {
        val gradle = mutableListOf<String>()
        val xcconfigs = mutableListOf<String>()
        val pbxprojs = mutableListOf<String>()
        walk(root, maxDepth) { file ->
            when {
                file.name == "build.gradle.kts" || file.name == "build.gradle" -> gradle += file.readText()
                file.name.endsWith(".xcconfig") -> xcconfigs += file.readText()
                file.name == "project.pbxproj" -> pbxprojs += file.readText()
            }
        }
        return fromContents(gradle, xcconfigs, pbxprojs)
    }

    fun fromContents(gradleFiles: List<String>, xcconfigs: List<String>, pbxprojs: List<String>): ProjectApps {
        val android = gradleFiles.flatMap { androidIds(it) }.distinct()
        val vars = xcconfigs.fold(emptyMap<String, String>()) { acc, text -> acc + xcconfigVars(text) }
        val rawIos = buildList {
            vars["PRODUCT_BUNDLE_IDENTIFIER"]?.let(::add)
            pbxprojs.forEach { addAll(pbxprojBundleIds(it)) }
        }
        val ios = rawIos.map { resolve(it, vars) }
            .filter { it.isNotBlank() && '$' !in it && !it.endsWith("Tests") && !it.endsWith("UITests") }
            .distinct()
        return ProjectApps(android, ios)
    }

    internal fun androidIds(gradle: String): List<String> =
        Regex("""(?m)^\s*applicationId\s*(?:=\s*)?["']([A-Za-z0-9_.\-]+)["']""").findAll(gradle).map { it.groupValues[1] }.toList()

    internal fun xcconfigVars(text: String): Map<String, String> = text.lines()
        .map { it.substringBefore("//").trim() }
        .mapNotNull { line -> Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$""").find(line)?.let { it.groupValues[1] to it.groupValues[2].trim() } }
        .toMap()

    internal fun pbxprojBundleIds(text: String): List<String> =
        Regex("""PRODUCT_BUNDLE_IDENTIFIER\s*=\s*"?([^";]+)"?;""").findAll(text).map { it.groupValues[1].trim() }.toList()

    /** Expands `$(VAR)` / `${VAR}`; unknown variables (like an empty TEAM_ID) become empty. */
    internal fun resolve(value: String, vars: Map<String, String>, depth: Int = 0): String {
        if (depth > 5) return value
        val expanded = Regex("""\$[({]([A-Za-z_][A-Za-z0-9_]*)(?::[^)}]*)?[)}]""").replace(value) { m ->
            vars[m.groupValues[1]].orEmpty()
        }
        return if (expanded != value && '$' in expanded) resolve(expanded, vars, depth + 1) else expanded
    }

    internal fun walk(dir: File, depth: Int, visit: (File) -> Unit) {
        if (depth < 0) return
        for (child in dir.listFiles().orEmpty()) {
            if (child.isDirectory) {
                if (child.name !in SKIP_DIRS) walk(child, depth - 1, visit)
            } else if (child.length() < 2_000_000) {
                visit(child)
            }
        }
    }
}
