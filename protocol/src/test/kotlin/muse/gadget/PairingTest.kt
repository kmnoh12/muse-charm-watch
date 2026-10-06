package muse.gadget

import java.math.BigInteger
import java.security.MessageDigest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeClock(var now: Double = 1000.0) : () -> Double {
    override fun invoke() = now
}

class PairingTest {
    private val vectors = JSONObject(javaClass.getResource("/link_pairing_v5.json")!!.readText()).getJSONArray("vectors")
    private fun vector(name: String): JSONObject = (0 until vectors.length()).map { vectors.getJSONObject(it) }.first { it.getString("name") == name }
    private val appVector get() = vector("community_app_v5")

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun transcriptOf(v: JSONObject) = buildTranscript(
        community = v.getBoolean("community"), authEpoch = v.getInt("pairing_auth_epoch"),
        policy = v.getString("pairing_policy"), deviceId = v.getString("device_id"), nodeId = v.getString("node_id"),
        mac = v.getString("mac"), firmwareVersion = v.getString("firmware_version"),
        mobilePub = v.getString("mobile_pub"), devicePub = v.getString("device_pub"),
        mobileNonce = v.getString("mobile_nonce"), deviceNonce = v.getString("device_nonce"),
    )

    private fun allVectors() = (0 until vectors.length()).map { vectors.getJSONObject(it) }

    @Test
    fun transcriptMatchesEveryVector() {
        for (v in allVectors()) {
            val t = transcriptOf(v)
            assertEquals(v.getString("transcript"), t, v.getString("name"))
            assertEquals(v.getString("transcript_hash"), b64urlEncode(sha256(t.toByteArray())), v.getString("name"))
        }
    }

    @Test
    fun keyScheduleMatchesEveryVector() {
        for (v in allVectors()) {
            val keys = deriveSessionKeys(
                v.getString("ecdh_secret_hex").unhex(), b64urlDecode(v.getString("mobile_nonce")),
                b64urlDecode(v.getString("device_nonce")), b64urlDecode(v.getString("transcript_hash")),
            )
            assertEquals(v.getString("mobile_tx_key_hex"), keys.mobileTx.hex(), v.getString("name"))
            assertEquals(v.getString("mobile_rx_key_hex"), keys.mobileRx.hex(), v.getString("name"))
            assertEquals(v.getString("session_id"), b64urlEncode(keys.sessionId), v.getString("name"))
        }
    }

    @Test
    fun ecdhOfVectorKeysMatchesTheVectorSecret() {
        val v = appVector
        val device = P256.fromScalar(BigInteger.valueOf(2), b64urlDecode(v.getString("device_pub")))
        val secret = P256.ecdh(device.privateKey, P256.publicKeyFrom(b64urlDecode(v.getString("mobile_pub"))))
        assertEquals(v.getString("ecdh_secret_hex"), secret.hex())
    }

    @Test
    fun transcriptRejectsAppPolicyForOfficialDevices() {
        assertFailsWith<IllegalArgumentException> {
            buildTranscript(false, 1, POLICY_APP, "d", "n", "m", "f", "a", "b", "c", "e")
        }
    }

    // -- A session wired to the vector's fixed device key, nonce and clock ----------------

    private class Fixture(val v: JSONObject, val clock: FakeClock = FakeClock()) {
        val session = PairingSession(
            nodeId = v.getString("node_id"), deviceId = v.getString("device_id"), mac = v.getString("mac"),
            firmwareVersion = v.getString("firmware_version"), sdkToken = "tok-123", clock = clock,
            generateKey = { P256.fromScalar(BigInteger.valueOf(2), b64urlDecode(v.getString("device_pub"))) },
            randomBytes = { b64urlDecode(v.getString("device_nonce")) },
        )
        val mobileTx = v.getString("mobile_tx_key_hex").unhex()
        val mobileRx = v.getString("mobile_rx_key_hex").unhex()
        val sessionId = v.getString("session_id")

        fun hello(overrides: Map<String, Any?> = emptyMap()): JSONObject {
            val m = JSONObject().put("action", "pairing_client_hello").put("version", 5)
                .put("pairing_auth", "none").put("pairing_policy", "confirm_app")
                .put("mobile_pub", v.getString("mobile_pub")).put("mobile_nonce", v.getString("mobile_nonce"))
            overrides.forEach { (k, value) -> if (value == null) m.remove(k) else m.put(k, value) }
            return m
        }

        /** A valid mobile-to-device record with the given counter, sealed with the vector key. */
        fun record(plaintext: String, counter: Long): JSONObject {
            val sealed = Gcm.seal(mobileTx, recordNonce(0, counter), recordAad(sessionId, 0, counter), plaintext.toByteArray())
            return JSONObject().put("action", "pairing_encrypted").put("session_id", sessionId).put("counter", counter.toString())
                .put("ciphertext", b64urlEncode(sealed.copyOfRange(0, sealed.size - 16)))
                .put("tag", b64urlEncode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        }

        fun clientFinishedRecord(): JSONObject = JSONObject()
            .put("action", "pairing_encrypted").put("session_id", sessionId).put("counter", "0")
            .put("ciphertext", v.getString("client_finished_ciphertext")).put("tag", v.getString("client_finished_tag"))

        /** Decrypts a device-to-mobile record with the vector key. */
        fun openFromDevice(envelope: JSONObject): String {
            val counter = envelope.getString("counter").toLong()
            val sealed = b64urlDecode(envelope.getString("ciphertext")) + b64urlDecode(envelope.getString("tag"))
            return String(Gcm.open(mobileRx, recordNonce(1, counter), recordAad(sessionId, 1, counter), sealed))
        }
    }

    private fun expectDecryptFailure(f: Fixture, envelope: JSONObject) {
        val e = assertFailsWith<PairingError> { f.session.decrypt(envelope) }
        assertEquals(ERROR_DECRYPT, e.status)
        assertEquals(PairingState.IDLE, f.session.currentState())
    }

    @Test
    fun deviceInfoAdvertisesCommunityAppConfirmation() {
        val info = Fixture(appVector).session.deviceInfo()
        assertEquals("none", info.getString("pairing_auth"))
        assertEquals("confirm_app", info.getString("pairing_policy"))
        assertEquals(5, info.getInt("pairing_protocol"))
        assertEquals("hatch_link", info.getString("model"))
    }

    @Test
    fun helloProducesTheVectorPairingReady() {
        val f = Fixture(appVector)
        val ready = f.session.handleHello(f.hello())
        assertEquals("pairing_ready", ready.getString("type"))
        assertEquals(appVector.getString("device_pub"), ready.getString("device_pub"))
        assertEquals(appVector.getString("device_nonce"), ready.getString("device_nonce"))
        assertEquals(appVector.getString("transcript_hash"), ready.getString("transcript_hash"))
        assertEquals(appVector.getString("session_id"), ready.getString("session_id"))
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, f.session.currentState())
    }

    @Test
    fun fullAppConfirmedHandshake() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        val plaintext = f.session.decrypt(f.clientFinishedRecord())
        assertEquals(appVector.getString("client_finished_plaintext"), plaintext)
        val gen = f.session.handleClientFinished(JSONObject(plaintext))
        assertNotEquals(0L, gen)
        assertTrue(f.session.confirmed)
        assertTrue(f.session.isCurrent(gen))
    }

    @Test
    fun pairingConfirmedCarriesTheSdkToken() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.session.handleClientFinished(JSONObject(f.session.decrypt(f.clientFinishedRecord())))
        val status = JSONObject(f.openFromDevice(assertNotNull(f.session.encryptStatus("pairing_confirmed"))))
        assertEquals("status", status.getString("type"))
        assertEquals("pairing_confirmed", status.getString("status"))
        assertEquals("tok-123", status.getString("sdk_token"))
        val other = JSONObject(f.openFromDevice(assertNotNull(f.session.encryptStatus("wifi_connected"))))
        assertFalse(other.has("sdk_token"))
    }

    @Test
    fun deviceRecordsCountUpFromZero() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        val counters = (0 until 3).map { assertNotNull(f.session.encryptJson("""{"n":$it}""")).getString("counter") }
        assertEquals(listOf("0", "1", "2"), counters)
    }

    @Test
    fun helloRejectsInvalidInput() {
        val bad = listOf(
            mapOf("version" to 4), mapOf("version" to true), mapOf("version" to "5"),
            mapOf("pairing_auth" to "fleet_ecdsa_p256_v1"), mapOf("pairing_policy" to "confirm_press"),
            mapOf("mobile_pub" to null), mapOf("mobile_pub" to "not base64!"),
            mapOf("mobile_pub" to b64urlEncode(ByteArray(64))), mapOf("mobile_pub" to b64urlEncode(byteArrayOf(0x04) + ByteArray(64))),
            mapOf("mobile_nonce" to b64urlEncode(ByteArray(15))),
        )
        for (o in bad) {
            val f = Fixture(appVector)
            val e = assertFailsWith<PairingError>("$o") { f.session.handleHello(f.hello(o)) }
            assertEquals(ERROR_INVALID_HELLO, e.status)
            assertEquals(PairingState.IDLE, f.session.currentState())
        }
    }

    @Test
    fun newHelloReplacesThePreviousSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        val first = f.clientFinishedRecord()
        f.session.handleHello(f.hello())
        // Same fixed keys, so the record still opens, but the counter restarted at 0.
        assertEquals(appVector.getString("client_finished_plaintext"), f.session.decrypt(first))
    }

    @Test
    fun replayedRecordClearsTheSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.session.decrypt(f.clientFinishedRecord())
        expectDecryptFailure(f, f.clientFinishedRecord())
    }

    @Test
    fun skippedCounterClearsTheSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        expectDecryptFailure(f, f.record("""{"action":"x"}""", 1))
    }

    @Test
    fun malformedCounterClearsTheSession() {
        for (c in listOf("-1", "1.5", "abc", "", "18446744073709551616")) {
            val f = Fixture(appVector)
            f.session.handleHello(f.hello())
            expectDecryptFailure(f, f.clientFinishedRecord().put("counter", c))
        }
    }

    @Test
    fun tamperedCiphertextClearsTheSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        val ct = b64urlDecode(appVector.getString("client_finished_ciphertext"))
        ct[0] = (ct[0].toInt() xor 1).toByte()
        expectDecryptFailure(f, f.clientFinishedRecord().put("ciphertext", b64urlEncode(ct)))
    }

    @Test
    fun wrongSessionIdClearsTheSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        expectDecryptFailure(f, f.clientFinishedRecord().put("session_id", b64urlEncode(ByteArray(16))))
    }

    @Test
    fun shortTagClearsTheSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        expectDecryptFailure(f, f.clientFinishedRecord().put("tag", b64urlEncode(ByteArray(8))))
    }

    @Test
    fun decryptWithoutSessionFails() {
        val f = Fixture(appVector)
        val e = assertFailsWith<PairingError> { f.session.decrypt(f.clientFinishedRecord()) }
        assertEquals(ERROR_DECRYPT, e.status)
    }

    @Test
    fun firstRecordMustBeExactClientFinished() {
        val commands = listOf("""{"action":"other"}""", """{"action":"pairing_client_finished","extra":1}""", "{}")
        for (c in commands) {
            val f = Fixture(appVector)
            f.session.handleHello(f.hello())
            val plaintext = f.session.decrypt(f.record(c, 0))
            assertEquals(0L, f.session.handleClientFinished(JSONObject(plaintext)), c)
            assertEquals(PairingState.IDLE, f.session.currentState())
        }
    }

    @Test
    fun clientFinishedOnlyCountsAsTheFirstRecord() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.session.decrypt(f.record("""{"action":"get_device_info"}""", 0))
        f.session.decrypt(f.record("""{"action":"pairing_client_finished"}""", 1))
        assertEquals(0L, f.session.handleClientFinished(JSONObject("""{"action":"pairing_client_finished"}""")))
    }

    @Test
    fun clientFinishedTimesOutAfter60Seconds() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.clock.now += 59
        assertEquals(PairingState.WAIT_CLIENT_FINISHED, f.session.currentState())
        f.clock.now += 2
        assertEquals(PairingState.IDLE, f.session.currentState())
    }

    @Test
    fun confirmedSessionExpiresAfter120Seconds() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.session.handleClientFinished(JSONObject(f.session.decrypt(f.clientFinishedRecord())))
        f.clock.now += 119
        assertTrue(f.session.confirmed)
        f.clock.now += 2
        assertFalse(f.session.confirmed)
    }

    @Test
    fun staleGenerationCannotEncrypt() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        val gen = f.session.handleClientFinished(JSONObject(f.session.decrypt(f.clientFinishedRecord())))
        assertNotNull(f.session.encryptStatus("wifi_connecting", gen))
        f.session.reset()
        assertNull(f.session.encryptStatus("wifi_connected", gen))
        assertFalse(f.session.isCurrent(gen))
    }

    @Test
    fun provisioningRequiresConfirmation() {
        val f = Fixture(appVector)
        assertEquals(0L, f.session.markProvisioning())
        f.session.handleHello(f.hello())
        assertEquals(0L, f.session.markProvisioning())
    }

    @Test
    fun commitRunsOnlyForTheCurrentProvisioningSession() {
        val f = Fixture(appVector)
        f.session.handleHello(f.hello())
        f.session.handleClientFinished(JSONObject(f.session.decrypt(f.clientFinishedRecord())))
        val gen = f.session.markProvisioning()
        assertNotEquals(0L, gen)
        var committed = 0
        assertTrue(f.session.commitProvisioning(gen) { committed++; true })
        assertFalse(f.session.commitProvisioning(gen + 1) { committed++; true })
        assertTrue(f.session.extendProvisioning(gen))
        f.session.reset()
        assertFalse(f.session.commitProvisioning(gen) { committed++; true })
        assertEquals(1, committed)
    }

    @Test
    fun b64urlDecodeRejectsWhatTheFirmwareRejects() {
        for (t in listOf("", "a", "abcde", "ab+/", "ab==", "ab cd", "ab\n")) {
            assertFailsWith<IllegalArgumentException>("'$t'") { b64urlDecode(t) }
        }
        assertFailsWith<IllegalArgumentException> { b64urlDecode(null) }
        assertFailsWith<IllegalArgumentException> { b64urlDecode("A".repeat(5000)) }
    }

    @Test
    fun b64urlRoundTripIsUnpadded() {
        for (n in 1..40) {
            val data = ByteArray(n) { (it * 13).toByte() }
            val enc = b64urlEncode(data)
            assertFalse(enc.contains("="))
            assertContentEquals(data, b64urlDecode(enc))
        }
    }
}
