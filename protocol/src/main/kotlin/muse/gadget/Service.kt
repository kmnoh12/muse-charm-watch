package muse.gadget

import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Keeps the gadget connected to its Muse VM: loads the pairing, rotates the device token, picks the
 * VM, runs a [LinkSession] and reconnects with backoff (port of service.py's run loop).
 */

const val BACKOFF_BASE_S = 2.0
const val BACKOFF_MAX_S = 60.0
const val AUTH_BACKOFF_MIN_S = 15.0
const val HEALTHY_SESSION_S = 30.0
const val UNPAIRED_POLL_S = 30.0

/** Device access tokens live about 4 hours; rotate at 3. */
const val TOKEN_REFRESH_AGE_S = 3 * 3600L
const val TOKEN_RETRY_S = 300L

/** What the phone app handed over at pairing, plus when the access token was minted. */
data class PairingRecord(
    val accessToken: String,
    val refreshToken: String,
    val username: String = "",
    val apiUrl: String = "",
    val apiUrlV2: String = "",
    val noiseHost: String = "",
    val accessTokenSavedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("access_token", accessToken).put("refresh_token", refreshToken).put("token_type", "device")
        .put("username", username).put("api_url", apiUrl).put("api_url_v2", apiUrlV2)
        .put("noise_host", noiseHost).put("access_token_saved_at", accessTokenSavedAt)

    companion object {
        fun fromJson(json: JSONObject): PairingRecord? {
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            if (access.isEmpty() || refresh.isEmpty()) return null
            return PairingRecord(
                access, refresh, json.optString("username"), json.optString("api_url"), json.optString("api_url_v2"),
                json.optString("noise_host"), json.optLong("access_token_saved_at", 0),
            )
        }
    }
}

/** Where the pairing lives between runs (app-private storage on the watch). */
interface PairingStore {
    fun load(): PairingRecord?
    fun save(record: PairingRecord)
    fun delete()
}

/** Checks the credentials from the phone app by asking for the leased VMs, then stores them. */
class VerifyAndSave(
    private val store: PairingStore,
    private val http: HttpTransport = UrlConnectionTransport,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val log: (String) -> Unit = {},
) : ProvisionStep {
    override fun run(credentials: Credentials, commit: (save: () -> Boolean) -> Boolean) {
        val apiUrl = credentials.apiUrl.takeIf { it.startsWith("https://") } ?: ""
        val apiUrlV2 = credentials.apiUrlV2.takeIf { it.startsWith("https://") } ?: ""
        val root = apiRoot(apiUrlV2)
        val (vms, status) = fetchVmsWithStatus(credentials.accessToken, root, http, userAgent)
        log("verify: GET $root$FETCH_PATH -> status=$status, ${vms.size} VM(s)")
        if (vms.isEmpty()) throw ProvisionFailed("auth_failed")
        val record = PairingRecord(
            credentials.accessToken, credentials.refreshToken, credentials.username, apiUrl, apiUrlV2,
            credentials.noiseHost, nowSeconds(),
        )
        if (!commit { store.save(record); true }) throw ProvisionFailed("error_storage")
    }
}

enum class ServiceStatus {
    /** No pairing: waiting for the Muse app to set the device up. */
    NOT_PAIRED,

    /** Paired and trying to reach the VM. */
    CONNECTING,

    /** Registered with the VM; chats and voice notes can flow. */
    ONLINE,
}

class Backoff {
    private var failures = 0
    var floor = 0.0

    fun nextDelaySeconds(): Double {
        val delay = min(BACKOFF_BASE_S * Math.pow(2.0, min(failures, 16).toDouble()), BACKOFF_MAX_S)
        failures++
        return maxOf(delay, floor)
    }

    fun reset() {
        failures = 0
        floor = 0.0
    }
}

class GadgetService(
    private val identity: Identity,
    private val store: PairingStore,
    private val device: DeviceDescription,
    private val connector: WsConnector,
    private val sdkToken: String? = null,
    private val http: HttpTransport = UrlConnectionTransport,
    private val runCommand: suspend (String, JSONObject, Long?) -> JSONObject = { c, _, _ -> unsupportedCommand(c) },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val monotonicSeconds: () -> Double = { System.nanoTime() / 1e9 },
    private val userAgent: String = DEFAULT_USER_AGENT,
    /** Multiplies every wait (tests shrink it). */
    private val delayScale: Double = 1.0,
    /** Told the length of every wait before it starts (tests record it). */
    private val pauseHook: ((Double) -> Unit)? = null,
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow(ServiceStatus.NOT_PAIRED)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _session = MutableStateFlow<LinkSession?>(null)

    /** The running session, from the moment it is created; wait for [LinkSession.registered] before using it. */
    val session: StateFlow<LinkSession?> = _session.asStateFlow()

    private var wake = CompletableDeferred<Unit>()
    private var lastRefreshAttempt = Double.NEGATIVE_INFINITY
    private var sdkTokenReportAttempted = false

    /** Call after the pairing changed (just paired, or removed) so the loop reacts without waiting. */
    fun pairingChanged() {
        wake.complete(Unit)
    }

    /** Runs until cancelled. */
    suspend fun run() {
        val backoff = Backoff()
        while (true) {
            val stored = store.load()
            if (stored == null) {
                _status.value = ServiceStatus.NOT_PAIRED
                pause(UNPAIRED_POLL_S)
                continue
            }
            _status.value = ServiceStatus.CONNECTING
            val pairing = maybeRefresh(stored)
            if (pairing == null) {
                if (store.load() != null) pause(TOKEN_RETRY_S.toDouble()) // a revoked pairing is noticed at once
                continue
            }

            val root = apiRoot(pairing.apiUrlV2)
            val (vms, status) = withContext(Dispatchers.IO) { fetchVmsWithStatus(pairing.accessToken, root, http, userAgent) }
            if (status == 401) {
                if (maybeRefresh(pairing, force = true) == null && store.load() != null) pause(TOKEN_RETRY_S.toDouble())
                continue
            }
            val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
            if (vm == null) {
                pause(backoff.nextDelaySeconds())
                continue
            }

            log("connecting to VM ${vm.vmName.ifEmpty { vm.vmId }} via ${pairing.noiseHost.ifEmpty { DEFAULT_NOISE_HOST }}")
            val (outcome, lasted) = runSession(vm, pairing)
            log("session ended: $outcome after ${"%.0f".format(lasted)}s")
            _session.value = null
            _status.value = ServiceStatus.CONNECTING
            if (outcome == Outcome.STOPPED) return
            if (outcome == Outcome.UNPAIRED) {
                store.delete()
                continue
            }
            if (lasted >= HEALTHY_SESSION_S) backoff.reset()
            if (outcome == Outcome.AUTH_REJECTED || outcome == Outcome.FORBIDDEN) backoff.floor = AUTH_BACKOFF_MIN_S
            pause(backoff.nextDelaySeconds())
        }
    }

    private suspend fun pause(seconds: Double) {
        pauseHook?.invoke(seconds)
        val signal = wake
        withTimeoutOrNull((seconds * 1000 * delayScale).toLong().coerceAtLeast(1)) { signal.await() }
        if (signal.isCompleted && wake === signal) wake = CompletableDeferred()
    }

    private suspend fun runSession(vm: Vm, pairing: PairingRecord): Pair<Outcome, Double> {
        val session = LinkSession(
            noiseHost = pairing.noiseHost.ifEmpty { DEFAULT_NOISE_HOST },
            vmId = vm.vmId.ifEmpty { vm.vmName },
            vmAuthToken = vm.vmAuthToken,
            device = device,
            runCommand = runCommand,
            connector = connector,
        )
        _session.value = session
        val started = monotonicSeconds()
        val stop = CompletableDeferred<Unit>()
        val outcome = try {
            coroutineScope {
                val online = launch { session.registered.first { it }; _status.value = ServiceStatus.ONLINE }
                try {
                    session.run(stop)
                } finally {
                    online.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("session failed: ${e::class.simpleName}: ${e.message}")
            Outcome.CLOSED
        }
        val lasted = if (session.isRegistered) monotonicSeconds() - started else 0.0
        return outcome to lasted
    }

    /**
     * The current pairing, rotating tokens first if they are due. Null only when a due refresh failed and the
     * old token should not be used yet; the pairing is deleted when it has been revoked.
     */
    private suspend fun maybeRefresh(pairing: PairingRecord, force: Boolean = false): PairingRecord? {
        val age = nowSeconds() - pairing.accessTokenSavedAt
        val reportDue = !sdkToken.isNullOrEmpty() && !sdkTokenReportAttempted
        val due = force || age >= TOKEN_REFRESH_AGE_S
        if (!due && !reportDue) return pairing
        if (!force && monotonicSeconds() - lastRefreshAttempt < TOKEN_RETRY_S) return pairing
        lastRefreshAttempt = monotonicSeconds()
        if (reportDue) sdkTokenReportAttempted = true
        val (tokens, status) = withContext(Dispatchers.IO) {
            refreshDeviceToken(pairing.refreshToken, identity.nodeId, apiRoot(pairing.apiUrlV2), sdkToken, http, userAgent)
        }
        if (tokens != null) {
            val updated = pairing.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, accessTokenSavedAt = nowSeconds())
            store.save(updated)
            return updated
        }
        // Only reporting the SDK token: nothing rejected the current token, so a refusal must never unpair the device.
        if (!due) return pairing
        if (status == 401) {
            store.delete()
            return null
        }
        return if (force) null else pairing
    }
}
