package muse.gadget

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import muse.gadget.noise.*
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore(var record: PairingRecord? = null) : PairingStore {
    var deletes = 0
    override fun load() = record
    override fun save(record: PairingRecord) { this.record = record }
    override fun delete() { record = null; deletes++ }
}

/** Answers the two API calls the service makes; `vmsBody` and `refreshStatus` steer the scenarios. */
private class FakeApi(
    var vmsStatusFor: (String) -> Int = { 200 },
    var vmsBody: String = """{"vm_list":[{"vm_ws_url":"wss://x","vm_auth_token":"vm-token","vm_name":"alpha","vm_id":"vm-1","default":true}]}""",
    var refreshStatus: Int = 200,
) : HttpTransport {
    val calls = mutableListOf<Triple<String, String, JSONObject?>>() // "METHOD path", bearer, body
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        val path = java.net.URL(url).path
        calls += Triple("$method $path", headers["Authorization"] ?: "", body?.let { JSONObject(String(it)) })
        return when (path) {
            FETCH_PATH -> {
                val bearer = headers["Authorization"]!!.removePrefix("Bearer ")
                val status = vmsStatusFor(bearer)
                HttpResult(status, if (status == 200) vmsBody else "")
            }
            REFRESH_PATH -> if (refreshStatus == 200) HttpResult(200, """{"access_token":"new-access","refresh_token":"new-refresh"}""") else HttpResult(refreshStatus, "")
            else -> HttpResult(404, "")
        }
    }
}

class ServiceTest {
    private val identity = Identity("02:00:00:00:00:01")
    private val device = DeviceDescription("homelink-000001", "watch", "0.1.0", JSONObject())
    private val fresh = { System.currentTimeMillis() / 1000 }

    private fun record(savedAt: Long = fresh()) = PairingRecord("access-1", "refresh-1", "me", "", "", "gw.example", savedAt)

    private class Harness(
        val store: MemoryStore,
        val api: FakeApi,
        val pauses: MutableList<Double> = mutableListOf(),
        val connects: MutableList<Pair<String, Map<String, String>>> = mutableListOf(),
    )

    private fun <T> withService(
        store: MemoryStore, api: FakeApi, sdkToken: String? = null,
        connector: (Harness) -> WsConnector,
        body: suspend (GadgetService, Harness, CoroutineScope) -> T,
    ): T = runBlocking {
        withTimeout(20_000) {
            val h = Harness(store, api)
            val service = GadgetService(
                identity, store, device, connector(h), sdkToken, api,
                delayScale = 0.001, pauseHook = { h.pauses += it },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job = scope.launch { service.run() }
            try {
                body(service, h, scope)
            } finally {
                job.cancel(); scope.cancel()
            }
        }
    }

    private suspend fun until(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition not met in $timeoutMs ms" }
            delay(5)
        }
    }

    private val failingConnector: (Harness) -> WsConnector = { h ->
        WsConnector { url, headers -> h.connects += url to headers; throw IOException("no route") }
    }

    @Test
    fun withoutAPairingItWaitsAndWakesWhenPaired() {
        val store = MemoryStore(null)
        withService(store, FakeApi(), connector = failingConnector) { service, h, _ ->
            until { service.status.value == ServiceStatus.NOT_PAIRED && h.pauses.isNotEmpty() }
            assertEquals(UNPAIRED_POLL_S, h.pauses.first())
            assertTrue(h.connects.isEmpty())
            store.save(record())
            service.pairingChanged()
            until { h.connects.isNotEmpty() }
            assertEquals("wss://gw.example/v1/noise?vm_id=vm-1", h.connects.first().first)
            assertEquals("Bearer vm-token", h.connects.first().second["Authorization"])
        }
    }

    @Test
    fun aRegisteredSessionIsOnlineAndUnpairingRemovesThePairing() {
        val store = MemoryStore(record())
        val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
        val toVm = Channel<ByteArray>(Channel.UNLIMITED)
        val vm = FakeVm(Pipe(toVm, toDevice))
        val connector: (Harness) -> WsConnector = { h -> WsConnector { url, headers -> h.connects += url to headers; Pipe(toDevice, toVm) } }
        withService(store, FakeApi(), connector = connector) { service, _, _ ->
            vm.handshake(); vm.acceptControlStream()
            val register = vm.nextMessage()
            assertEquals("link.register", register.getString("method"))
            assertEquals("homelink-000001", register.getJSONObject("params").getString("node_id"))
            vm.sendMessage(JSONObject().put("type", "res").put("id", register.getString("id")).put("ok", true))

            service.status.first { it == ServiceStatus.ONLINE }
            val session = assertNotNull(service.session.value)
            assertTrue(session.isRegistered)

            vm.sendMessage(JSONObject("""{"type":"evt","event":"link.unpaired"}"""))
            service.status.first { it == ServiceStatus.NOT_PAIRED }
            assertNull(store.record)
            assertEquals(1, store.deletes)
            until { service.session.value == null }
        }
    }

    @Test
    fun anOldAccessTokenIsRotatedBeforeUse() {
        val store = MemoryStore(record(savedAt = fresh() - TOKEN_REFRESH_AGE_S - 10))
        val api = FakeApi()
        withService(store, api, connector = failingConnector) { _, h, _ ->
            until { h.connects.isNotEmpty() }
            assertEquals("POST /device_token/refresh", api.calls.first().first)
            assertEquals("Bearer hatch_refresh:refresh-1", api.calls.first().second)
            assertEquals("homelink-000001", api.calls.first().third!!.getString("device_id"))
            assertEquals("Bearer new-access", api.calls.first { it.first == "GET /fetch_vms" }.second)
            assertEquals("new-access" to "new-refresh", store.record!!.accessToken to store.record!!.refreshToken)
        }
    }

    @Test
    fun aRejectedAccessTokenTriggersARefreshAndARetry() {
        val store = MemoryStore(record())
        val api = FakeApi(vmsStatusFor = { if (it == "access-1") 401 else 200 })
        withService(store, api, connector = failingConnector) { _, h, _ ->
            until { h.connects.isNotEmpty() }
            assertEquals(listOf("GET /fetch_vms", "POST /device_token/refresh", "GET /fetch_vms"), api.calls.map { it.first }.take(3))
            assertEquals("Bearer new-access", api.calls[2].second)
        }
    }

    @Test
    fun aRevokedPairingIsDroppedAtOnce() {
        val store = MemoryStore(record(savedAt = fresh() - TOKEN_REFRESH_AGE_S - 10))
        val api = FakeApi(refreshStatus = 401)
        withService(store, api, connector = failingConnector) { service, h, _ ->
            until { store.record == null }
            until { service.status.value == ServiceStatus.NOT_PAIRED && h.pauses.isNotEmpty() }
            assertEquals(listOf(UNPAIRED_POLL_S), h.pauses.take(1)) // no 300 s retry wait first
            assertTrue(h.connects.isEmpty())
        }
    }

    @Test
    fun theSdkTokenIsReportedOnceWithAFreshAccessToken() {
        val store = MemoryStore(record())
        val api = FakeApi()
        withService(store, api, sdkToken = "mgst_abc", connector = failingConnector) { _, h, _ ->
            until { h.connects.isNotEmpty() }
            val refresh = api.calls.first { it.first == "POST /device_token/refresh" }
            assertEquals("mgst_abc", refresh.third!!.getString("sdk_token"))
            until { h.pauses.size >= 2 }
            assertEquals(1, api.calls.count { it.first == "POST /device_token/refresh" })
        }
    }

    @Test
    fun aFailedSdkTokenReportNeverUnpairs() {
        val store = MemoryStore(record())
        val api = FakeApi(refreshStatus = 401)
        withService(store, api, sdkToken = "mgst_abc", connector = failingConnector) { _, h, _ ->
            until { h.connects.isNotEmpty() }
            assertNotNull(store.record)
            assertEquals(0, store.deletes)
        }
    }

    @Test
    fun failedConnectionsBackOffExponentiallyUpToAMinute() {
        val store = MemoryStore(record())
        withService(store, FakeApi(), connector = failingConnector) { _, h, _ ->
            until { h.pauses.size >= 8 }
            assertEquals(listOf(2.0, 4.0, 8.0, 16.0, 32.0, 60.0, 60.0, 60.0), h.pauses.take(8))
        }
    }

    @Test
    fun aRejectedUpgradeKeepsAtLeastFifteenSeconds() {
        val store = MemoryStore(record())
        val connector: (Harness) -> WsConnector = { h -> WsConnector { url, headers -> h.connects += url to headers; throw UpgradeRejected(401) } }
        withService(store, FakeApi(), connector = connector) { _, h, _ ->
            until { h.pauses.size >= 2 }
            assertTrue(h.pauses.all { it >= AUTH_BACKOFF_MIN_S })
        }
    }

    @Test
    fun noLeasedVmsMeansWaitingAndRetrying() {
        val store = MemoryStore(record())
        val api = FakeApi(vmsBody = """{"vm_list":[]}""")
        withService(store, api, connector = failingConnector) { _, h, _ ->
            until { h.pauses.size >= 2 }
            assertEquals(listOf(2.0, 4.0), h.pauses.take(2))
            assertTrue(h.connects.isEmpty())
        }
    }

    @Test
    fun theDefaultVmIsPreferred() {
        val store = MemoryStore(record())
        val body = """{"vm_list":[{"vm_ws_url":"u1","vm_auth_token":"t1","vm_id":"first"},{"vm_ws_url":"u2","vm_auth_token":"t2","vm_id":"second","default":true}]}"""
        withService(store, FakeApi(vmsBody = body), connector = failingConnector) { _, h, _ ->
            until { h.connects.isNotEmpty() }
            assertEquals("wss://gw.example/v1/noise?vm_id=second", h.connects.first().first)
        }
    }

    // -- Pairing storage -----------------------------------------------------------------

    private val credentials = Credentials("at", "rt", "u", "https://a.example", "https://b.example", "n.example")

    @Test
    fun verifyAndSaveStoresTheCredentialsOnceTheyWork() {
        val store = MemoryStore()
        val api = FakeApi()
        VerifyAndSave(store, api, nowSeconds = { 1234 }).run(credentials) { save -> save() }
        assertEquals(PairingRecord("at", "rt", "u", "https://a.example", "https://b.example", "n.example", 1234), store.record)
    }

    @Test
    fun verifyAndSaveDropsNonHttpsApiUrls() {
        val store = MemoryStore()
        VerifyAndSave(store, FakeApi()).run(Credentials("at", "rt", "u", "http://insecure", "ftp://nope", "n")) { save -> save() }
        assertEquals("" to "", store.record!!.apiUrl to store.record!!.apiUrlV2)
    }

    @Test
    fun verifyAndSaveRejectsCredentialsThatFindNoVm() {
        val store = MemoryStore()
        val e = kotlin.runCatching { VerifyAndSave(store, FakeApi(vmsStatusFor = { 401 })).run(credentials) { save -> save() } }.exceptionOrNull()
        assertEquals("auth_failed", (e as ProvisionFailed).status)
        assertNull(store.record)
    }

    @Test
    fun verifyAndSaveReportsAStorageFailure() {
        val store = MemoryStore()
        val e = kotlin.runCatching { VerifyAndSave(store, FakeApi()).run(credentials) { false } }.exceptionOrNull()
        assertEquals("error_storage", (e as ProvisionFailed).status)
        assertNull(store.record)
    }

    @Test
    fun pairingRecordSurvivesJson() {
        val r = PairingRecord("a", "r", "u", "https://x", "https://y", "n", 99)
        assertEquals(r, PairingRecord.fromJson(JSONObject(r.toJson().toString())))
        assertNull(PairingRecord.fromJson(JSONObject("""{"access_token":"only"}""")))
        assertFalse(r.toJson().has("sdk_token"))
    }
}
