package io.github.kmpdevicehub.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.kmpdevicehub.core.ActionResult
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceSimulation
import io.github.kmpdevicehub.core.GeoPoint
import io.github.kmpdevicehub.core.NetworkProfile
import io.github.kmpdevicehub.core.PresetRunner
import io.github.kmpdevicehub.core.PushNotification
import io.github.kmpdevicehub.core.TestPreset
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList

/** What the tabs need from the devices panel. */
internal interface PanelContext {
    fun selectedDevice(): Device?
    fun appId(): String

    /** True when Pro is available; otherwise explains Pro and returns false. */
    fun requirePro(feature: String): Boolean

    /** Runs [block] on the selected device, or on every running device when "All running devices" is on. */
    fun runOnTargets(progress: String, block: (device: Device, appId: String) -> ActionResult)
}

/** A tab that enables its controls for the selected device. */
internal interface DeviceTab {
    val component: JComponent
    fun deviceChanged(device: Device?)
}

// ---- Simulate: location, network, status bar ------------------------------------------------------------

internal class SimulateTab(private val ctx: PanelContext, private val simulation: DeviceSimulation) : DeviceTab {
    private val placeBox = ComboBox<Any>(DefaultComboBoxModel<Any>((GeoPoint.COMMON + CUSTOM).toTypedArray<Any>())).apply {
        renderer = textRenderer<Any> { (it as? GeoPoint)?.label ?: it?.toString() }
    }
    private val latField = JBTextField(8).apply { emptyText.text = "latitude" }
    private val lonField = JBTextField(8).apply { emptyText.text = "longitude" }
    private val setLocationButton = JButton("Set location", AllIcons.General.Locate)
    internal val networkBox = ComboBox<NetworkProfile>(DefaultComboBoxModel<NetworkProfile>(NetworkProfile.entries.toTypedArray())).apply {
        renderer = textRenderer<NetworkProfile> { (it as? NetworkProfile)?.label }
    }
    private val applyNetworkButton = JButton("Apply")
    internal val statusBarBox = JBCheckBox("Clean status bar (9:41, full battery and signal)")
    private val batterySpinner = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(20, 0, 100, 5))
    private val chargingBox = JBCheckBox("Charging")
    private val batteryButton = JButton("Set battery")
    private val rotateButton = JButton("Rotate", AllIcons.Actions.Refresh)
    private val autoRotateButton = JButton("Auto-rotate")
    private val bioMatchButton = JButton("Biometric match", AllIcons.General.InspectionsOK)
    private val bioFailButton = JButton("No match", AllIcons.General.Error)
    private val enrollBox = JBCheckBox("Face ID / Touch ID enrolled")
    private val mediaButton = JButton("Add photos or videos…", AllIcons.FileTypes.Image)
    private val clipboardField = JBTextField().apply { emptyText.text = "Text to put on the device's clipboard" }
    private val clipboardSendButton = JButton("Send")
    private val clipboardReadButton = JButton("Copy from device")
    private val hint = HintLabel()

    override val component: JComponent = panel {
        group("Location") {
            row { cell(placeBox).align(AlignX.FILL).resizableColumn() }
            row { cell(latField); cell(lonField) }
            row { cell(setLocationButton) }
        }
        group("Network") {
            row { cell(networkBox) }
            row { cell(applyNetworkButton) }
        }
        group("Status bar") {
            row { cell(statusBarBox) }
        }
        group("Battery") {
            row("Level %:") { cell(batterySpinner); cell(chargingBox) }
            row { cell(batteryButton) }
        }
        group("Rotation and biometrics") {
            row { cell(rotateButton); cell(autoRotateButton) }
            row { cell(bioMatchButton); cell(bioFailButton) }
            row { cell(enrollBox) }
        }
        group("Media and clipboard") {
            row { cell(mediaButton) }
            row { cell(clipboardField).align(AlignX.FILL).resizableColumn() }
            row { cell(clipboardSendButton); cell(clipboardReadButton) }
        }
        row { cell(hint) }
    }

    init {
        placeBox.addActionListener { fillCoordinates() }
        fillCoordinates()
        wireExtras()
        setLocationButton.addActionListener {
            if (!ctx.requirePro("Simulated location")) return@addActionListener
            val lat = latField.text.trim().toDoubleOrNull()
            val lon = lonField.text.trim().toDoubleOrNull()
            ctx.runOnTargets("Setting location…") { d, _ ->
                if (lat == null || lon == null) ActionResult.failed("Enter latitude and longitude as numbers, e.g. 24.8607 and 67.0011.")
                else simulation.setLocation(d, lat, lon)
            }
        }
        applyNetworkButton.addActionListener {
            if (!ctx.requirePro("Network conditions")) return@addActionListener
            val profile = networkBox.selectedItem as? NetworkProfile ?: return@addActionListener
            ctx.runOnTargets("Changing network…") { d, _ -> simulation.setNetwork(d, profile) }
        }
        statusBarBox.addActionListener {
            if (!ctx.requirePro("Clean status bar")) { statusBarBox.isSelected = !statusBarBox.isSelected; return@addActionListener }
            val clean = statusBarBox.isSelected
            ctx.runOnTargets("Changing status bar…") { d, _ -> simulation.cleanStatusBar(d, clean) }
        }
    }

    private fun wireExtras() {
        batteryButton.addActionListener {
            if (!ctx.requirePro("Battery simulation")) return@addActionListener
            val level = (batterySpinner.value as Number).toInt()
            val charging = chargingBox.isSelected
            ctx.runOnTargets("Changing battery…") { d, _ -> simulation.setBattery(d, level, charging) }
        }
        rotateButton.addActionListener { if (ctx.requirePro("Rotation")) ctx.runOnTargets("Rotating…") { d, _ -> simulation.rotate(d) } }
        autoRotateButton.addActionListener { ctx.runOnTargets("Turning on auto-rotate…") { d, _ -> simulation.resetRotation(d) } }
        bioMatchButton.addActionListener { if (ctx.requirePro("Biometric simulation")) ctx.runOnTargets("Sending a matching biometric…") { d, _ -> simulation.biometric(d, true) } }
        bioFailButton.addActionListener { if (ctx.requirePro("Biometric simulation")) ctx.runOnTargets("Sending a non-matching biometric…") { d, _ -> simulation.biometric(d, false) } }
        enrollBox.addActionListener {
            if (!ctx.requirePro("Biometric simulation")) { enrollBox.isSelected = !enrollBox.isSelected; return@addActionListener }
            val on = enrollBox.isSelected
            ctx.runOnTargets("Changing enrollment…") { d, _ -> simulation.enrollBiometrics(d, on) }
        }
        mediaButton.addActionListener {
            if (!ctx.requirePro("Adding media")) return@addActionListener
            val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Add Photos or Videos").withDescription("Images (jpg, png, heic, gif, webp) or videos (mp4, mov).")
            com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, null, component, null) { chosen ->
                addMedia(chosen.map { java.io.File(it.path) })
            }
        }
        clipboardSendButton.addActionListener {
            val text = clipboardField.text
            if (text.isNotEmpty()) ctx.runOnTargets("Sending clipboard…") { d, _ -> simulation.setClipboard(d, text) }
        }
        clipboardReadButton.addActionListener {
            ctx.runOnTargets("Reading the clipboard…") { d, _ ->
                val text = simulation.getClipboard(d)
                if (text == null) ActionResult.failed("Only iOS simulators' clipboards can be read; Android emulators share yours.")
                else {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
                    ActionResult.done("Copied ${d.name}'s clipboard (${text.length} characters) to yours.")
                }
            }
        }
    }

    internal fun addMedia(files: List<java.io.File>) = ctx.runOnTargets("Adding media…") { d, _ -> simulation.addMedia(d, files) }

    private fun fillCoordinates() {
        val place = placeBox.selectedItem as? GeoPoint
        latField.isEditable = place == null
        lonField.isEditable = place == null
        if (place != null) {
            latField.text = place.latitude.toString()
            lonField.text = place.longitude.toString()
        }
    }

    override fun deviceChanged(device: Device?) {
        val usable = device?.isRunning == true
        listOf(placeBox, latField, lonField, setLocationButton, networkBox, applyNetworkButton, statusBarBox, batterySpinner, chargingBox,
            batteryButton, rotateButton, autoRotateButton, bioMatchButton, bioFailButton, enrollBox, mediaButton, clipboardField,
            clipboardSendButton, clipboardReadButton).forEach { it.isEnabled = usable }
        val ios = device?.platform == DevicePlatform.IOS
        rotateButton.isVisible = !ios; autoRotateButton.isVisible = !ios
        enrollBox.isVisible = ios; clipboardReadButton.isVisible = ios
        hint.hint = when {
            device == null || !usable -> "Start a device to simulate conditions."
            device.platform == DevicePlatform.IOS -> "Network conditions are Android-emulator only; iOS simulators use your Mac's network."
            else -> "Location and network speed work on emulators; offline works on phones too."
        }
    }

    internal fun setPlace(index: Int) { placeBox.selectedIndex = index }
    internal fun clickSetLocation() = setLocationButton.doClick()
    internal fun applyNetwork(profile: NetworkProfile) { networkBox.selectedItem = profile; applyNetworkButton.doClick() }
    internal fun setBatteryForTest(level: Int, charging: Boolean) { batterySpinner.value = level; chargingBox.isSelected = charging; batteryButton.doClick() }
    internal fun clickRotate() = rotateButton.doClick()
    internal fun clickBiometric(match: Boolean) = (if (match) bioMatchButton else bioFailButton).doClick()

    private companion object {
        const val CUSTOM = "Custom…"
    }
}

// ---- Push notifications ---------------------------------------------------------------------------------

internal class PushTab(private val ctx: PanelContext, private val simulation: DeviceSimulation) : DeviceTab {
    private val titleField = JBTextField("Your order shipped")
    private val bodyField = JBTextField("Tap to track order #42")
    private val badgeField = JBTextField(4).apply { emptyText.text = "none" }
    private val dataArea = JBTextArea("orderId=42", 3, 20)
    private val sendButton = JButton("Send push", AllIcons.Ide.Notification.NoEvents)
    private val hint = HintLabel()

    override val component: JComponent = panel {
        row("Title:") { cell(titleField).align(AlignX.FILL).resizableColumn() }
        row("Body:") { cell(bodyField).align(AlignX.FILL).resizableColumn() }
        row("Badge:") { cell(badgeField) }
        row("Data:") { cell(ScrollPaneFactory.createScrollPane(dataArea)).align(AlignX.FILL).resizableColumn() }
        row { comment("One key=value per line, delivered as custom payload fields (iOS) or intent extras (Android).", maxLineLength = HINT_WIDTH) }
        row { cell(sendButton) }
        row { cell(hint) }
    }

    init {
        sendButton.addActionListener { send() }
    }

    private fun send() {
        if (!ctx.requirePro("Push notification tester")) return
        val badgeText = badgeField.text.trim()
        val badge = badgeText.toIntOrNull()
        val push = PushNotification(titleField.text.trim(), bodyField.text.trim(), badge, PushNotification.parseData(dataArea.text))
        ctx.runOnTargets("Sending push…") { d, app ->
            if (badgeText.isNotEmpty() && badge == null) ActionResult.failed("The badge must be a whole number.")
            else simulation.sendPush(d, app, push)
        }
    }

    override fun deviceChanged(device: Device?) {
        sendButton.isEnabled = device?.isRunning == true
        hint.hint = when {
            device == null || !device.isRunning -> "Start a device to send a push."
            device.platform == DevicePlatform.IOS -> "Delivered to your app like a real push, in the foreground or background."
            else -> "Android has no local push without Firebase: this opens your app as if its notification was tapped, with the data as extras."
        }
    }

    internal fun sendWith(title: String, data: String) { titleField.text = title; dataArea.text = data; send() }
}

// ---- Presets --------------------------------------------------------------------------------------------

internal class PresetsTab(
    private val ctx: PanelContext,
    private val runner: PresetRunner,
    private val store: PropertiesComponent,
    /** The device conditions currently shown in the panel, for "Save current". */
    private val current: () -> TestPreset,
    private val storeShots: io.github.kmpdevicehub.core.StoreScreenshots? = null,
    /** Asks what to capture; null when cancelled. Tests answer directly. */
    private val pickStorePlan: () -> Pair<io.github.kmpdevicehub.core.StoreShotPlan, java.io.File>? = { StoreShotsDialog.ask() },
) : DeviceTab {
    private val model = CollectionListModel<TestPreset>()
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<TestPreset>() {
            override fun customizeCellRenderer(list: JList<out TestPreset>, value: TestPreset?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.name)
                append("  " + value.summary, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        visibleRowCount = 7
    }
    private val applyButton = JButton("Apply", AllIcons.Actions.Execute)
    private val saveButton = JButton("Save current…", AllIcons.Actions.MenuSaveall)
    private val deleteButton = JButton("Delete")
    private val storeButton = JButton("Capture store screenshots…", AllIcons.FileTypes.Image)

    override val component: JComponent = panel {
        row { cell(ScrollPaneFactory.createScrollPane(list)).align(AlignX.FILL).resizableColumn() }
        row { cell(applyButton); cell(saveButton); cell(deleteButton) }
        row { comment("A preset sets several conditions at once. Double-click to apply.", maxLineLength = HINT_WIDTH) }
        separator()
        row { cell(storeButton) }
        row { comment("Relaunches your app in each language you pick (and dark mode) with a clean 9:41 status bar, and saves a screenshot of each. With All running devices, every device at once.", maxLineLength = HINT_WIDTH) }
    }

    init {
        reload()
        list.selectedIndex = 0
        applyButton.addActionListener { apply() }
        storeButton.addActionListener { captureStoreShots() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) apply() }
        })
        list.addListSelectionListener { deleteButton.isEnabled = list.selectedValue?.builtIn == false }
        saveButton.addActionListener {
            if (!ctx.requirePro("Test presets")) return@addActionListener
            val name = Messages.showInputDialog(component, "Name for this preset:", "Save Test Preset", null)?.trim()
            if (!name.isNullOrEmpty()) save(current().copy(name = name))
        }
        deleteButton.addActionListener {
            val preset = list.selectedValue?.takeIf { !it.builtIn } ?: return@addActionListener
            store.setList(KEY, custom().filter { it.name != preset.name }.map { it.encode() })
            reload()
        }
    }

    private fun custom() = store.getList(KEY).orEmpty().mapNotNull(TestPreset::decode)

    private fun reload() = model.replaceAll(TestPreset.BUILT_IN + custom())

    internal fun save(preset: TestPreset) {
        store.setList(KEY, (custom().filter { it.name != preset.name } + preset).map { it.encode() })
        reload()
        list.setSelectedValue(model.items.last(), true)
    }

    private fun apply() {
        val preset = list.selectedValue ?: return
        if (!ctx.requirePro("Test presets")) return
        ctx.runOnTargets("Applying \"${preset.name}\"…") { d, app -> runner.apply(d, app, preset) }
    }

    override fun deviceChanged(device: Device?) {
        applyButton.isEnabled = device?.isRunning == true
    }

    internal val presetNames: List<String> get() = model.items.map { it.name }
    private fun captureStoreShots() {
        val shots = storeShots ?: return
        if (!ctx.requirePro("Store screenshots")) return
        val (plan, folder) = pickStorePlan() ?: return
        ctx.runOnTargets("Capturing ${plan.shotsPerDevice} store screenshot(s) per device…") { d, app ->
            shots.capture(d, app, plan, folder).also { result ->
                if (result.ok) com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    com.intellij.ide.actions.RevealFileAction.openDirectory(folder)
                }
            }
        }
    }

    internal fun captureStoreShotsForTest() = captureStoreShots()

    internal fun applyPreset(name: String) { list.setSelectedValue(model.items.first { it.name == name }, false); apply() }

    private companion object {
        const val KEY = "kmpdevicehub.presets"
    }
}

// ---- Links: open one, keep a library ---------------------------------------------------------------------

internal class LinksTab(
    private val ctx: PanelContext,
    private val store: PropertiesComponent,
    private val open: (String) -> Unit,
) : DeviceTab {
    internal val field = JBTextField().apply { emptyText.text = "myapp://orders/42 or https://example.com/orders/42" }
    private val openButton = JButton("Open link", AllIcons.Ide.External_link_arrow)
    private val saveButton = JButton("Save to library")
    private val removeButton = JButton("Remove")
    private val model = CollectionListModel<LinkEntry>()
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<LinkEntry>() {
            override fun customizeCellRenderer(list: JList<out LinkEntry>, value: LinkEntry?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.url)
                if (value.detected) append("  from your project", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        visibleRowCount = 5
        emptyText.text = "Saved links appear here"
    }
    private var detected: List<String> = emptyList()

    override val component: JComponent = panel {
        row { cell(field).align(AlignX.FILL).resizableColumn() }
        row { cell(openButton); cell(saveButton) }
        row { cell(ScrollPaneFactory.createScrollPane(list)).align(AlignX.FILL).resizableColumn() }
        row { cell(removeButton) }
        row { comment("Opens the link the way a tap would; with an app selected on Android, only that app handles it. Double-click a saved link to open it.", maxLineLength = HINT_WIDTH) }
    }

    init {
        openButton.addActionListener { open(field.text) }
        field.addActionListener { open(field.text) }
        saveButton.addActionListener {
            val url = field.text.trim()
            if (url.isEmpty() || !ctx.requirePro("Deep link library")) return@addActionListener
            store.setList(KEY, (saved() - url) + url)
            reload()
        }
        removeButton.addActionListener {
            val entry = list.selectedValue?.takeIf { !it.detected } ?: return@addActionListener
            store.setList(KEY, saved() - entry.url)
            reload()
        }
        list.addListSelectionListener { list.selectedValue?.let { field.text = it.url }; removeButton.isEnabled = list.selectedValue?.detected == false }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) list.selectedValue?.let { open(it.url) } }
        })
        reload()
    }

    fun setDetected(links: List<String>) { detected = links; reload() }

    private fun saved() = store.getList(KEY).orEmpty()

    private fun reload() {
        val mine = saved()
        model.replaceAll(mine.map { LinkEntry(it, false) } + detected.filter { it !in mine }.map { LinkEntry(it, true) })
    }

    override fun deviceChanged(device: Device?) {
        val usable = device?.isRunning == true
        field.isEnabled = usable
        openButton.isEnabled = usable
    }

    internal val libraryLinks: List<String> get() = model.items.map { it.url }
    internal fun saveLink(url: String) { field.text = url; saveButton.doClick() }

    data class LinkEntry(val url: String, val detected: Boolean)

    private companion object {
        const val KEY = "kmpdevicehub.links"
    }
}

/** Wrap hints so a long one never makes the tool window wider. */
internal const val HINT_WIDTH = 45

/** A small grey hint that wraps instead of widening the tool window. */
internal class HintLabel : com.intellij.ui.components.JBLabel() {
    init {
        font = com.intellij.util.ui.JBFont.small()
        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    var hint: String = ""
        set(value) {
            field = value
            text = if (value.isEmpty()) "" else "<html><body style='width: ${com.intellij.util.ui.JBUI.scale(260)}px'>" +
                com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(value) + "</body></html>"
        }
}

internal fun <T> textRenderer(text: (Any?) -> String?) = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(text(value).orEmpty())
    }
}


/** Picks the languages, appearances and folder for store screenshots. */
internal class StoreShotsDialog : com.intellij.openapi.ui.DialogWrapper(true) {
    private val languageBoxes = (listOf<io.github.kmpdevicehub.core.AppLanguage?>(null) + io.github.kmpdevicehub.core.AppLanguage.COMMON)
        .associateWith { lang -> JBCheckBox(lang?.label ?: "Device language").apply { isSelected = lang == null } }
    private val darkBox = JBCheckBox("Also in dark mode")
    private val folderField = com.intellij.openapi.ui.TextFieldWithBrowseButton().apply {
        text = java.io.File(System.getProperty("user.home"), "Pictures/KMP Device Hub/Store screenshots " +
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))).path
        addActionListener {
            com.intellij.openapi.fileChooser.FileChooser.chooseFile(com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor(), null, null) { text = it.path }
        }
    }

    init {
        title = "Capture Store Screenshots"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("Languages (your app relaunches in each):") }
        languageBoxes.values.chunked(3).forEach { boxes -> row { boxes.forEach { cell(it) } } }
        row { cell(darkBox) }
        row("Save to:") { cell(folderField).align(AlignX.FILL).resizableColumn() }
        row { comment("Each device gets a folder with <language>-light.png (and -dark.png). The status bar shows 9:41 with full battery and signal.", maxLineLength = 70) }
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (languageBoxes.values.none { it.isSelected }) com.intellij.openapi.ui.ValidationInfo("Pick at least one language.") else null

    val plan: io.github.kmpdevicehub.core.StoreShotPlan
        get() = io.github.kmpdevicehub.core.StoreShotPlan(languageBoxes.filterValues { it.isSelected }.keys.toList(), darkBox.isSelected)

    val folder: java.io.File get() = java.io.File(folderField.text.trim())

    companion object {
        fun ask(): Pair<io.github.kmpdevicehub.core.StoreShotPlan, java.io.File>? {
            val dialog = StoreShotsDialog()
            return if (dialog.showAndGet()) dialog.plan to dialog.folder else null
        }
    }
}
