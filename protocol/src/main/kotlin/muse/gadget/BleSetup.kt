package muse.gadget

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * BLE setup commands: the protocol logic behind the GATT characteristics (port of ble_setup.py).
 *
 * Independent of the Bluetooth stack: a transport delivers reassembled writes and sends framed
 * notifications. Messages are handled one at a time, in arrival order, and every send happens
 * under one lock so encrypted record counters reach the phone in order.
 */

val SENSITIVE_ACTIONS = setOf("provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth")
val PLAINTEXT_STATUSES = setOf(
    "error_encryption_required", "error_pairing_invalid_hello", "error_pairing_unavailable", "error_pairing_decrypt",
)
const val DISCONNECT_AFTER_ERROR_MS = 300L

interface SetupTransport {
    /** Notify each packet in order; blocks until all are queued. */
    fun sendPackets(packets: List<ByteArray>)

    fun mtu(): Int

    fun disconnect(delayMs: Long)
}

interface NetworkInfo {
    fun isOnline(): Boolean

    fun currentConnectionEntry(): JSONObject
}

/** What the Muse app hands the device during setup (the Wi-Fi fields are deliberately dropped). */
class Credentials(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
)

/** Setup could not finish; [status] is what the app is told. */
class ProvisionFailed(val status: String) : Exception(status)

/**
 * Verifies [Credentials] and persists them through `commit` (which runs under the pairing lock),
 * throwing [ProvisionFailed] on failure.
 */
fun interface ProvisionStep {
    fun run(credentials: Credentials, commit: (save: () -> Boolean) -> Boolean)
}

class SetupController(
    private val pairing: PairingSession,
    private val identity: Identity,
    private val version: String,
    private val transport: SetupTransport,
    private val network: NetworkInfo,
    private val provision: ProvisionStep,
    private val onComplete: () -> Unit = {},
    /** Handles messages one at a time in arrival order; tests pass a same-thread executor. */
    private val messageExecutor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ble-setup").also { it.isDaemon = true } },
    private val provisionExecutor: Executor = Executor { r -> Thread(r, "provision").also { it.isDaemon = true }.start() },
) {
    private val assembler = ChunkAssembler()
    private val txLock = Any()
    private val stateLock = Any()
    private var plaintextBlocked = false
    private var provisioning = false

    // -- Transport callbacks (any thread) ---------------------------------------

    fun onWrite(packet: ByteArray) {
        val message = synchronized(assembler) { assembler.feed(packet) } ?: return
        messageExecutor.execute { handleMessage(message) }
    }

    fun onDisconnect() {
        synchronized(assembler) { assembler.reset() }
        synchronized(stateLock) { plaintextBlocked = false }
        pairing.reset()
    }

    // -- Dispatch ---------------------------------------------------------------

    fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
        val command = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: JSONException) {
            sendStatus("error_invalid_command")
            return
        }
        val action = command.optString("action", "")
        val blocked = synchronized(stateLock) { plaintextBlocked }

        when {
            !decrypted && action == "pairing_client_hello" -> handleHello(command)
            !decrypted && action == "pairing_encrypted" -> handleRecord(command)
            // Public metadata, safe in plaintext at any point. Apps re-read it when they restart a handshake.
            action == "get_device_info" -> sendJson(deviceInfo())
            !decrypted && blocked -> {} // plaintext command ignored after pairing started
            !decrypted && action in SENSITIVE_ACTIONS -> sendStatus("error_encryption_required")
            decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
            decrypted && action in SENSITIVE_ACTIONS && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
            decrypted && action == "wifi_scan" -> handleWifiScan()
            decrypted && action == "provision_v2" -> handleProvision(command)
            else -> sendStatus("error_unknown_action")
        }
    }

    fun deviceInfo(): JSONObject {
        val info = JSONObject()
            .put("type", "device_info")
            .put("node_id", identity.nodeId)
            .put("version", version)
        val pairingInfo = pairing.deviceInfo()
        pairingInfo.keys().forEach { info.put(it, pairingInfo.get(it)) }
        return info.put("build_sha", "").put("network_ready", network.isOnline())
    }

    private fun handleHello(command: JSONObject) {
        val ready = try {
            pairing.handleHello(command)
        } catch (e: PairingError) {
            sendStatus(e.status)
            return
        }
        synchronized(stateLock) { plaintextBlocked = true }
        sendJson(ready)
    }

    private fun handleRecord(envelope: JSONObject) {
        val plaintext = try {
            pairing.decrypt(envelope)
        } catch (e: PairingError) {
            sendStatus(e.status)
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        handleMessage(plaintext.toByteArray(Charsets.UTF_8), decrypted = true)
    }

    private fun handleClientFinished(command: JSONObject) {
        val generation = pairing.handleClientFinished(command)
        if (generation == 0L) {
            sendStatus("error_pairing_decrypt")
            transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
            return
        }
        sendStatus("pairing_confirmed", generation)
    }

    private fun handleWifiScan() {
        val networks = JSONArray()
        if (network.isOnline()) networks.put(network.currentConnectionEntry())
        sendEncryptedJson(JSONObject().put("type", "wifi_scan_result").put("networks", networks))
    }

    private fun handleProvision(command: JSONObject) {
        fun text(key: String): String = command.opt(key).let { if (it is String) it else "" }

        if (command.opt("ssid") !is String || command.opt("password") !is String ||
            text("access_token").isEmpty() || text("refresh_token").isEmpty() || text("token_type") != "device"
        ) {
            sendStatus("error_missing_credentials")
            return
        }
        var generation = 0L
        val refusal: String? = synchronized(stateLock) {
            if (provisioning) {
                "error_operation_in_progress"
            } else {
                generation = pairing.markProvisioning()
                provisioning = generation != 0L
                if (generation != 0L) null else "error_pairing_confirm_required"
            }
        }
        // Sent after the lock is released: sendStatus() may take stateLock itself.
        if (refusal != null) {
            sendStatus(refusal)
            return
        }
        // The Wi-Fi fields are deliberately dropped: this device only sets up when it is already online.
        val credentials = Credentials(
            text("access_token"), text("refresh_token"), text("username"), text("api_url"), text("api_url_v2"), text("noise_host"),
        )
        val gen = generation
        provisionExecutor.execute { runProvision(credentials, gen) }
    }

    private fun runProvision(credentials: Credentials, generation: Long) {
        try {
            sendStatus("wifi_connecting", generation)
            if (!network.isOnline()) {
                // Stay in provisioning so the app can retry, as the firmware does.
                pairing.extendProvisioning(generation)
                sendStatus("wifi_failed", generation)
                return
            }
            sendStatus("wifi_connected", generation)
            try {
                provision.run(credentials) { save -> pairing.commitProvisioning(generation, save) }
            } catch (e: ProvisionFailed) {
                sendStatus(e.status, generation)
                transport.disconnect(500)
                return
            }
            sendStatus("auth_ok", generation)
            onComplete()
        } finally {
            synchronized(stateLock) { provisioning = false }
        }
    }

    // -- Sending ----------------------------------------------------------------

    fun sendStatus(status: String, generation: Long = 0L) = synchronized(txLock) {
        val envelope = pairing.encryptStatus(status, generation)
        if (envelope != null) {
            sendLocked(envelope.toString())
            return@synchronized
        }
        val blocked = synchronized(stateLock) { plaintextBlocked }
        if (generation != 0L || blocked || status !in PLAINTEXT_STATUSES) return@synchronized
        transport.sendPackets(listOf(status.toByteArray()))
    }

    fun sendJson(obj: JSONObject) = synchronized(txLock) { sendLocked(obj.toString()) }

    fun sendEncryptedJson(obj: JSONObject, generation: Long = 0L) = synchronized(txLock) {
        val envelope = pairing.encryptJson(obj.toString(), generation) ?: return@synchronized
        sendLocked(envelope.toString())
    }

    private fun sendLocked(text: String) {
        transport.sendPackets(BleFraming.encodeChunks(text.toByteArray(Charsets.UTF_8), transport.mtu()))
    }
}
