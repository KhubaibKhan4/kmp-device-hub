package io.github.kmpdevicehub.idea

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.kmpdevicehub.core.ActionResult
import io.github.kmpdevicehub.core.Connectivity
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceService
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.NetworkBridge
import io.github.kmpdevicehub.core.NetworkLog
import io.github.kmpdevicehub.core.NetworkRule
import io.github.kmpdevicehub.core.NetworkServer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the Network panel needs; the service in the IDE, a fake in tests. */
interface InspectorBackend {
    val log: NetworkLog
    val port: Int
    val isListening: Boolean
    fun start(): ActionResult
    var rules: List<NetworkRule>
    fun runningDevices(): List<Device>
    fun connectivity(device: Device): Connectivity
}

/**
 * One inspector per IDE, shared by all projects: listens on 127.0.0.1:38383 and keeps `adb reverse` set up on every
 * connected Android device so apps there reach it as localhost. Starts with the first project.
 */
@Service(Service.Level.APP)
class NetworkInspectorService : InspectorBackend, Disposable {
    override val log = NetworkLog()
    private val server = NetworkServer(log)
    private val bridge = NetworkBridge()
    private val store get() = PropertiesComponent.getInstance()
    private var reverseTask: ScheduledFuture<*>? = null
    private val reversed = HashSet<String>()
    private var lastFullPass = 0L

    override val port: Int get() = server.localPort
    override val isListening: Boolean get() = server.isRunning

    override var rules: List<NetworkRule>
        get() = server.rules
        set(value) {
            server.rules = value
            store.setValue(RULES_KEY, NetworkRule.encodeAll(value))
        }

    @Synchronized
    override fun start(): ActionResult {
        if (server.rules.isEmpty()) server.rules = NetworkRule.decodeAll(store.getValue(RULES_KEY).orEmpty())
        val result = server.start()
        if (!result.ok) thisLogger().info(result.message)
        if (result.ok && reverseTask == null) {
            reverseTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::reverseDevices, 0, 5, TimeUnit.SECONDS)
        }
        return result
    }

    /** New Android devices get `adb reverse` at once; all of them again every minute in case adb restarted. */
    private fun reverseDevices() {
        runCatching {
            val serials = bridge.androidSerials()
            val full = System.currentTimeMillis() - lastFullPass > 60_000
            if (full) lastFullPass = System.currentTimeMillis()
            synchronized(reversed) {
                reversed.retainAll(serials.toSet())
                serials.filter { full || it !in reversed }.forEach { if (bridge.reverse(it, port)) reversed += it }
            }
        }
    }

    override fun runningDevices(): List<Device> = DeviceService().list().devices.filter { it.state == DeviceState.RUNNING }

    override fun connectivity(device: Device): Connectivity = bridge.check(device)

    override fun dispose() {
        reverseTask?.cancel(false)
        server.stop()
    }

    companion object {
        private const val RULES_KEY = "kmpdevicehub.network.rules"
    }
}

/** Starts listening when a project opens, so calls made before the Network window is opened still show up. */
class NetworkInspectorStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().getService(NetworkInspectorService::class.java).start()
    }
}
