package muse.gadget

import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeTransport(private val mtu: Int = 185) : SetupTransport {
    /** JSON objects for chunked notifications, raw strings for plain status packets. */
    val messages = mutableListOf<Any>()
    val disconnects = mutableListOf<Long>()
    private val assembler = ChunkAssembler()

    override fun mtu() = mtu

    override fun sendPackets(packets: List<ByteArray>) {
        for (p in packets) {
            assertTrue(p.size <= maxOf(mtu - 3, 20))
            val message = assembler.feed(p) ?: continue
            messages += if ((p[0].toInt() and 0xFF) == 0xFE) JSONObject(String(message)) else String(message)
        }
    }

    override fun disconnect(delayMs: Long) { disconnects += delayMs }
}

private class FakeNetwork(var online: Boolean = true) : NetworkInfo {
    override fun isOnline() = online
    override fun currentConnectionEntry(): JSONObject = JSONObject().put("ssid", "home").put("connected", true)
}

class SetupControllerTest {
    private val vectors = JSONObject(javaClass.getResource("/link_pairing_v5.json")!!.readText()).getJSONArray("vectors")
    private val vector = (0 until vectors.length()).map { vectors.getJSONObject(it) }.first { it.getString("name") == "community_app_v5" }
    private val sameThread = Executor { it.run() }

    private inner class Rig(online: Boolean = true, val failWith: String? = null) {
        val f = Fixture(vector)
        val transport = FakeTransport()
        val network = FakeNetwork(online)
        val provisioned = mutableListOf<Credentials>()
        var committed = false
        var completed = 0
        val controller = SetupController(
            pairing = f.session, identity = Identity("02:00:00:00:00:01"), version = "0.1.0",
            transport = transport, network = network,
            provision = ProvisionStep { credentials, commit ->
                provisioned += credentials
                if (failWith != null) throw ProvisionFailed(failWith)
                committed = commit { true }
            },
            onComplete = { completed++ },
            messageExecutor = sameThread, provisionExecutor = sameThread,
        )

        fun sendPlain(json: String) = controller.handleMessage(json.toByteArray())
        fun hello() = controller.handleMessage(f.hello().toString().toByteArray())
        fun sendEncrypted(plaintext: String, counter: Long) = controller.handleMessage(f.record(plaintext, counter).toString().toByteArray())

        /** Pairs up to `pairing_confirmed` and returns the next free mobile counter. */
        fun pair(): Long {
            hello()
            controller.handleMessage(f.clientFinishedRecord().toString().toByteArray())
            return 1
        }

        /** Decrypted device-to-mobile records, in order. */
        fun fromDevice(): List<JSONObject> = transport.messages.filterIsInstance<JSONObject>()
            .filter { it.optString("type") == "pairing_encrypted" }.map { JSONObject(f.openFromDevice(it)) }

        fun statuses(): List<String> = fromDevice().filter { it.optString("type") == "status" }.map { it.getString("status") }
    }

    private val provisionCommand = """{"action":"provision_v2","ssid":"x","password":"y","access_token":"at","refresh_token":"rt","token_type":"device","username":"u","api_url":"a","api_url_v2":"b","noise_host":"n"}"""

    @Test
    fun getDeviceInfoWorksInPlaintext() {
        val r = Rig()
        r.sendPlain("""{"action":"get_device_info"}""")
        val info = r.transport.messages.single() as JSONObject
        assertEquals("device_info", info.getString("type"))
        assertEquals("homelink-000001", info.getString("node_id"))
        assertEquals("0.1.0", info.getString("version"))
        assertEquals("confirm_app", info.getString("pairing_policy"))
        assertTrue(info.getBoolean("network_ready"))
    }

    @Test
    fun sensitiveActionsNeedEncryption() {
        val r = Rig()
        r.sendPlain(provisionCommand)
        assertEquals(listOf<Any>("error_encryption_required"), r.transport.messages)
    }

    @Test
    fun helloReturnsPairingReadyAndBlocksPlaintextAfterwards() {
        val r = Rig()
        r.hello()
        val ready = r.transport.messages.single() as JSONObject
        assertEquals("pairing_ready", ready.getString("type"))
        r.sendPlain(provisionCommand) // ignored once pairing started
        assertEquals(1, r.transport.messages.size)
        r.sendPlain("""{"action":"get_device_info"}""") // metadata is still allowed
        assertEquals("device_info", (r.transport.messages.last() as JSONObject).getString("type"))
    }

    @Test
    fun badHelloIsAnsweredWithAPlaintextError() {
        val r = Rig()
        r.controller.handleMessage(r.f.hello(mapOf("version" to 4)).toString().toByteArray())
        assertEquals(listOf<Any>("error_pairing_invalid_hello"), r.transport.messages)
    }

    @Test
    fun clientFinishedConfirmsPairingAndCarriesTheSdkToken() {
        val r = Rig()
        r.pair()
        val status = r.fromDevice().single()
        assertEquals("pairing_confirmed", status.getString("status"))
        assertEquals("tok-123", status.getString("sdk_token"))
        assertTrue(r.f.session.confirmed)
    }

    @Test
    fun sensitiveActionsAreRefusedUntilConfirmed() {
        val r = Rig()
        r.hello()
        r.sendEncrypted("""{"action":"wifi_scan"}""", 0)
        assertEquals(listOf("error_pairing_confirm_required"), r.statuses())
    }

    @Test
    fun wifiScanReportsTheCurrentConnection() {
        val r = Rig()
        val next = r.pair()
        r.sendEncrypted("""{"action":"wifi_scan"}""", next)
        val result = r.fromDevice().last()
        assertEquals("wifi_scan_result", result.getString("type"))
        assertEquals("home", (result.getJSONArray("networks") as JSONArray).getJSONObject(0).getString("ssid"))
    }

    @Test
    fun provisionRunsToAuthOk() {
        val r = Rig()
        val next = r.pair()
        r.sendEncrypted(provisionCommand, next)
        assertEquals(listOf("pairing_confirmed", "wifi_connecting", "wifi_connected", "auth_ok"), r.statuses())
        val c = r.provisioned.single()
        assertEquals(listOf("at", "rt", "u", "a", "b", "n"), listOf(c.accessToken, c.refreshToken, c.username, c.apiUrl, c.apiUrlV2, c.noiseHost))
        assertTrue(r.committed)
        assertEquals(1, r.completed)
    }

    @Test
    fun provisionNeedsCompleteCredentials() {
        val r = Rig()
        val next = r.pair()
        r.sendEncrypted("""{"action":"provision_v2","ssid":"x","password":"y","access_token":"at","token_type":"device"}""", next)
        assertEquals("error_missing_credentials", r.statuses().last())
        assertTrue(r.provisioned.isEmpty())
    }

    @Test
    fun provisionWhileOfflineFailsButStaysRetryable() {
        val r = Rig(online = false)
        val next = r.pair()
        r.sendEncrypted(provisionCommand, next)
        assertEquals(listOf("pairing_confirmed", "wifi_connecting", "wifi_failed"), r.statuses())
        assertTrue(r.provisioned.isEmpty())
        r.network.online = true
        r.sendEncrypted(provisionCommand, next + 1)
        assertEquals("auth_ok", r.statuses().last())
    }

    @Test
    fun failedProvisioningReportsItsStatusAndDisconnects() {
        val r = Rig(failWith = "error_auth_failed")
        val next = r.pair()
        r.sendEncrypted(provisionCommand, next)
        assertEquals("error_auth_failed", r.statuses().last())
        assertEquals(listOf(500L), r.transport.disconnects)
        assertEquals(0, r.completed)
    }

    @Test
    fun unknownEncryptedActionIsReported() {
        val r = Rig()
        val next = r.pair()
        r.sendEncrypted("""{"action":"nope"}""", next)
        assertEquals("error_unknown_action", r.statuses().last())
    }

    @Test
    fun aBadRecordDisconnectsWithoutAPlaintextReply() {
        val r = Rig()
        r.hello()
        val before = r.transport.messages.size
        r.controller.handleMessage(r.f.clientFinishedRecord().put("counter", "5").toString().toByteArray())
        assertEquals(before, r.transport.messages.size) // once pairing started, plaintext replies are suppressed
        assertEquals(listOf(DISCONNECT_AFTER_ERROR_MS), r.transport.disconnects)
        assertEquals(PairingState.IDLE, r.f.session.currentState())
    }

    @Test
    fun invalidJsonWithoutASessionIsSilentlyDropped() {
        val r = Rig()
        r.controller.handleMessage("not json".toByteArray())
        assertTrue(r.transport.messages.isEmpty())
    }

    @Test
    fun chunkedWritesAreReassembledBeforeHandling() {
        val r = Rig()
        val hello = r.f.hello().toString().toByteArray()
        BleFraming.encodeChunks(hello, mtu = 23).forEach { r.controller.onWrite(it) }
        assertEquals("pairing_ready", (r.transport.messages.single() as JSONObject).getString("type"))
    }

    @Test
    fun disconnectClearsThePairingSession() {
        val r = Rig()
        r.pair()
        assertTrue(r.f.session.confirmed)
        r.controller.onDisconnect()
        assertFalse(r.f.session.confirmed)
        r.sendPlain("""{"action":"get_device_info"}""")
        assertNotNull(r.transport.messages.last())
    }

    @Test
    fun largeNotificationsAreChunkedToTheMtu() {
        val r = Rig()
        r.controller.sendJson(JSONObject().put("blob", "x".repeat(2000)))
        assertEquals(2000, (r.transport.messages.single() as JSONObject).getString("blob").length)
    }
}
