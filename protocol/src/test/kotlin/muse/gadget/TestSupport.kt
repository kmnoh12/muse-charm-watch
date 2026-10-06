package muse.gadget

import java.math.BigInteger
import org.json.JSONObject

internal class FakeClock(var now: Double = 1000.0) : () -> Double {
    override fun invoke() = now
}

/** A pairing session wired to a reference vector's fixed device key, nonce and clock, plus the mobile side's keys. */
internal class Fixture(val v: JSONObject, val clock: FakeClock = FakeClock()) {
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
