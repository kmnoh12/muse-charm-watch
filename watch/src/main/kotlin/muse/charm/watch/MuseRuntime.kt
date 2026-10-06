package muse.charm.watch

import android.app.Application
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import muse.charm.conversation.ConversationController
import muse.charm.conversation.ServiceMuseChannel
import muse.charm.conversation.SwitchableChannel
import muse.gadget.DeviceDescription
import muse.gadget.GadgetService
import muse.gadget.PairingSession
import muse.gadget.ServiceStatus
import muse.gadget.SetupController
import muse.gadget.VerifyAndSave
import muse.gadget.unsupportedCommand
import org.json.JSONObject

/**
 * Everything that lives as long as the app: the identity, the connection service, the BLE pairing
 * peripheral and the conversation. The demo channel answers until the watch is online with Muse.
 */
class MuseRuntime(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    val identity = loadIdentity(app)
    private val store = PrefsPairingStore(app)
    private val sdkToken = BuildConfig.MUSE_SDK_TOKEN.ifEmpty { null }

    /** True when a personal SDK token was built into the app (see local.properties). */
    val hasSdkToken: Boolean = sdkToken != null

    private val device = DeviceDescription(
        nodeId = identity.nodeId,
        displayName = "Galaxy Watch",
        version = BuildConfig.VERSION_NAME,
        commands = JSONObject().put(
            "device.health",
            JSONObject().put("description", "Device status: battery, uptime, software version.")
                .put("required", JSONObject()).put("optional", JSONObject()),
        ),
    )

    val service = GadgetService(
        identity = identity, store = store, device = device, connector = OkHttpConnector(), sdkToken = sdkToken,
        log = { Log.i("MuseCharm", it) },
        runCommand = { command, _, _ -> if (command == "device.health") health() else unsupportedCommand(command) },
    )

    private val pairing = PairingSession(
        nodeId = identity.nodeId, deviceId = identity.deviceId, mac = identity.mac,
        firmwareVersion = BuildConfig.VERSION_NAME, sdkToken = sdkToken,
    )

    private lateinit var setup: SetupController

    val ble = BlePeripheral(app, onWrite = { setup.onWrite(it) }, onClientDisconnected = { setup.onDisconnect() })

    init {
        setup = SetupController(
            pairing = pairing, identity = identity, version = BuildConfig.VERSION_NAME,
            transport = ble, network = AndroidNetwork(app), provision = VerifyAndSave(store, log = { Log.i("MuseSetup", it) }),
            log = { Log.i("MuseSetup", it) },
            onComplete = {
                main.post { service.pairingChanged() }
                main.postDelayed({ ble.stop() }, 1_500) // let the phone receive the final status first
            },
        )
    }

    val speaker = AndroidSpeaker(app)
    private val demo = DemoChannel(scope)
    private val live = ServiceMuseChannel(service.session)
    private val channel = SwitchableChannel(demo)

    val controller = ConversationController(scope, channel, AndroidRecorder(), speaker, System::currentTimeMillis)

    val serviceStatus: StateFlow<ServiceStatus> get() = service.status

    init {
        controller.start()
        scope.launch { service.run() }
        scope.launch { service.status.collect { channel.switchTo(if (it == ServiceStatus.ONLINE) live else demo) } }
    }

    /** Starts advertising as `MuseGadgetXXXXXX` so the Muse app can add this watch. */
    fun startPairing() = ble.start(identity.bleName)

    fun stopPairing() = ble.stop()

    private fun health(): JSONObject {
        val battery = app.getSystemService(BatteryManager::class.java)
        val payload = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("device", "Galaxy Watch")
            .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
            .put("battery_percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
        return JSONObject().put("ok", true).put("payload", payload)
    }
}

class MuseCharmApp : Application() {
    lateinit var runtime: MuseRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = MuseRuntime(this)
    }
}
