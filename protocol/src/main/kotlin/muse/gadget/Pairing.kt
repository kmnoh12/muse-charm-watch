package muse.gadget

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Device side of Muse Gadget BLE pairing, protocol version 5, community mode
 * (port of pairing.py). Community pairing protects setup secrets from passive observers;
 * it does not authenticate the ECDH peer, so it cannot stop an active man-in-the-middle.
 */

const val PAIRING_VERSION = 5
const val PAIRING_MODEL = "hatch_link"
const val PAIRING_SUITE = "p256-hkdf-sha256-aes-gcm-v1"
const val POLICY_BUTTON = "confirm_press"
const val POLICY_APP = "confirm_app"
const val AUTH_OFFICIAL = "fleet_ecdsa_p256_v1"
const val AUTH_COMMUNITY = "none"
const val BUTTON_CONFIRM_TIMEOUT_S = 60

const val RECORD_LABEL = "hatch-link ble setup v1"
private val SESSION_ID_LABEL = "hatch-link session id v1".toByteArray()

const val CLIENT_FINISHED_TIMEOUT_S = 60
const val CONFIRMED_TIMEOUT_S = 120
const val PROVISIONING_TIMEOUT_S = 120

const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
const val ERROR_DECRYPT = "error_pairing_decrypt"

private const val P256_POINT_BYTES = 65
private const val NONCE_BYTES = 16
private const val SESSION_ID_BYTES = 16
private const val TAG_BYTES = 16
private const val MAX_B64_CHARS = 4096
private const val MAX_CIPHERTEXT_B64_CHARS = 16384
private val B64URL_RE = Regex("[A-Za-z0-9_-]+")
private val DECIMAL_RE = Regex("[0-9]+")
private const val TO_DEVICE = 0
private const val FROM_DEVICE = 1

/** A handshake step failed; [status] is the wire error to report. */
class PairingError(val status: String) : Exception(status)

enum class PairingState { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

fun b64urlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

/** Decodes unpadded base64url, rejecting anything the firmware rejects. */
fun b64urlDecode(text: Any?, maxChars: Int = MAX_B64_CHARS): ByteArray {
    if (text !is String || text.isEmpty() || text.length > maxChars) throw IllegalArgumentException("invalid base64url length")
    if (text.length % 4 == 1 || !B64URL_RE.matches(text)) throw IllegalArgumentException("invalid base64url")
    return Base64.getUrlDecoder().decode(text)
}

fun parseCounter(text: Any?): Long {
    if (text !is String || !DECIMAL_RE.matches(text)) throw IllegalArgumentException("invalid counter")
    val value = BigInteger(text)
    if (value.bitLength() > 64) throw IllegalArgumentException("counter overflow")
    return value.toLong() // unsigned 64-bit bits; counters here stay far below 2^63
}

/** Canonical v5 transcript; SHA-256 of it is the `transcript_hash`. */
fun buildTranscript(
    community: Boolean,
    authEpoch: Int,
    policy: String,
    deviceId: String,
    nodeId: String,
    mac: String,
    firmwareVersion: String,
    mobilePub: String,
    devicePub: String,
    mobileNonce: String,
    deviceNonce: String,
): String {
    val button = policy == POLICY_BUTTON
    require(button || (community && policy == POLICY_APP)) { "unsupported pairing policy: '$policy'" }
    require(if (community) authEpoch == 0 else authEpoch > 0) { "invalid auth epoch: $authEpoch" }
    val fields = listOf(deviceId, nodeId, mac, firmwareVersion, mobilePub, devicePub, mobileNonce, deviceNonce)
    require(fields.all { it.isNotEmpty() }) { "transcript fields must be non-empty" }
    return listOf(
        "hatch-link-pairing-v$PAIRING_VERSION",
        "version=$PAIRING_VERSION",
        "initiator_role=mobile",
        "responder_role=link",
        "device_id=$deviceId",
        "node_id=$nodeId",
        "mac=$mac",
        "model=$PAIRING_MODEL",
        "firmware_version=$firmwareVersion",
        "selected_cipher_suite=$PAIRING_SUITE",
        "pairing_auth=${if (community) AUTH_COMMUNITY else AUTH_OFFICIAL}",
        "pairing_auth_epoch=$authEpoch",
        "pairing_policy=$policy",
        "confirm_timeout_seconds=${if (button) BUTTON_CONFIRM_TIMEOUT_S else 0}",
        "mobile_pub=$mobilePub",
        "device_pub=$devicePub",
        "mobile_nonce=$mobileNonce",
        "device_nonce=$deviceNonce",
    ).joinToString("\n")
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

private fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").also { md -> parts.forEach(md::update) }.digest()

/** HKDF-Expand to 32 bytes (one block) with SHA-256. */
private fun hkdfExpand32(prk: ByteArray, info: ByteArray): ByteArray = hmacSha256(prk, info + byteArrayOf(1))

class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

/**
 * `mobileTx` decrypts records the device receives; `mobileRx` encrypts records it sends.
 */
fun deriveSessionKeys(ecdhSecret: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): SessionKeys {
    val salt = sha256(mobileNonce, deviceNonce, transcriptHash)
    val prk = hmacSha256(salt, ecdhSecret) // HKDF-Extract
    val sessionSecret = hkdfExpand32(prk, RECORD_LABEL.toByteArray())
    val mobileTx = hkdfExpand32(sessionSecret, "mobile->device".toByteArray())
    val mobileRx = hkdfExpand32(sessionSecret, "device->mobile".toByteArray())
    val sessionId = sha256(SESSION_ID_LABEL, transcriptHash, ecdhSecret).copyOf(SESSION_ID_BYTES)
    return SessionKeys(mobileTx, mobileRx, sessionId)
}

fun recordNonce(direction: Int, counter: Long): ByteArray {
    val n = ByteArray(12)
    n[0] = direction.toByte()
    for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
    return n
}

fun recordAad(sessionIdB64: String, direction: Int, counter: Long): ByteArray {
    val arrow = if (direction == TO_DEVICE) "m2d" else "d2m"
    return "$RECORD_LABEL|$sessionIdB64|$arrow|${java.lang.Long.toUnsignedString(counter)}".toByteArray()
}

/** AES-256-GCM with a 16-byte tag; output of [seal] is ciphertext followed by the tag. */
internal object Gcm {
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /** Throws [AEADBadTagException] on a bad tag. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            it.updateAAD(aad)
        }
}

class EcKeyPair(val privateKey: PrivateKey, val publicUncompressed: ByteArray)

/** NIST P-256 helpers on the JCA. */
object P256 {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .also { it.init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)

    private fun fixed32(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val dst = 32 - be.size + i
            if (dst >= 0) out[dst] = be[i]
        }
        return out
    }

    fun encode(pub: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    fun generate(random: SecureRandom = SecureRandom()): EcKeyPair {
        val kp: KeyPair = KeyPairGenerator.getInstance("EC").also { it.initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        return EcKeyPair(kp.private, encode(kp.public as ECPublicKey))
    }

    /** For tests: a key pair from a known scalar and its (known) public point. */
    fun fromScalar(scalar: BigInteger, publicUncompressed: ByteArray): EcKeyPair =
        EcKeyPair(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(scalar, params)), publicUncompressed)

    /** Parses an uncompressed point and checks it is on the curve. */
    fun publicKeyFrom(uncompressed: ByteArray): PublicKey {
        if (uncompressed.size != P256_POINT_BYTES || uncompressed[0].toInt() != 0x04) throw IllegalArgumentException("invalid point")
        val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
        val p = (params.curve.field as ECFieldFp).p
        val onCurve = x < p && y < p &&
            y.multiply(y).mod(p) == x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        if (!onCurve) throw IllegalArgumentException("point not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").also { it.init(priv); it.doPhase(pub, true) }.generateSecret()
}

/**
 * One device's pairing state. Methods that advance the handshake return a nonzero
 * *generation* token; deferred work should check [isCurrent] before acting, so work from an
 * abandoned attempt can't act on a newer one.
 */
class PairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String? = null,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    private val generateKey: () -> EcKeyPair = { P256.generate() },
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
    private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
    private val lock = Any()
    private var generation = 0L

    private var state = PairingState.IDLE
    private var deadline = 0.0
    private var rxKey: ByteArray? = null
    private var txKey: ByteArray? = null
    private var sessionIdB64 = ""
    private var rxCounter = 0L
    private var txCounter = 0L

    init { synchronized(lock) { resetLocked() } }

    /** Pairing fields for the `get_device_info` response. */
    fun deviceInfo(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("mac", mac)
        .put("model", PAIRING_MODEL)
        .put("pairing_protocol", PAIRING_VERSION)
        .put("pairing_auth", AUTH_COMMUNITY)
        .put("pairing_auth_epoch", 0)
        .put("pairing_policy", POLICY_APP)

    fun currentState(): PairingState = synchronized(lock) { expireLocked(); state }

    val confirmed: Boolean
        get() = currentState().let { it == PairingState.READY || it == PairingState.PROVISIONING }

    fun isCurrent(gen: Long): Boolean = synchronized(lock) { gen != 0L && gen == generation }

    fun reset() = synchronized(lock) { resetLocked() }

    /** Starts a session from `pairing_client_hello`; returns the `pairing_ready` message. */
    fun handleHello(message: JSONObject): JSONObject = synchronized(lock) {
        val version = message.opt("version")
        if (version !is Number || version.toDouble() != PAIRING_VERSION.toDouble() ||
            message.opt("pairing_auth") != AUTH_COMMUNITY || message.opt("pairing_policy") != POLICY_APP
        ) throw PairingError(ERROR_INVALID_HELLO)

        resetLocked()
        val mobilePub: ByteArray
        val mobileNonce: ByteArray
        val peer: PublicKey
        try {
            mobilePub = b64urlDecode(message.opt("mobile_pub"))
            mobileNonce = b64urlDecode(message.opt("mobile_nonce"))
            if (mobilePub.size != P256_POINT_BYTES || mobilePub[0].toInt() != 0x04 || mobileNonce.size != NONCE_BYTES) {
                throw IllegalArgumentException("invalid hello key material")
            }
            peer = P256.publicKeyFrom(mobilePub)
        } catch (e: IllegalArgumentException) {
            resetLocked()
            throw PairingError(ERROR_INVALID_HELLO)
        }

        val deviceKey = generateKey()
        val devicePub = deviceKey.publicUncompressed
        val deviceNonce = randomBytes(NONCE_BYTES)
        val transcript = buildTranscript(
            community = true, authEpoch = 0, policy = POLICY_APP,
            deviceId = deviceId, nodeId = nodeId, mac = mac, firmwareVersion = firmwareVersion,
            mobilePub = b64urlEncode(mobilePub), devicePub = b64urlEncode(devicePub),
            mobileNonce = b64urlEncode(mobileNonce), deviceNonce = b64urlEncode(deviceNonce),
        )
        val transcriptHash = sha256(transcript.toByteArray())
        val ecdhSecret = P256.ecdh(deviceKey.privateKey, peer)
        val keys = deriveSessionKeys(ecdhSecret, mobileNonce, deviceNonce, transcriptHash)

        rxKey = keys.mobileTx
        txKey = keys.mobileRx
        sessionIdB64 = b64urlEncode(keys.sessionId)
        rxCounter = 0
        txCounter = 0
        state = PairingState.WAIT_CLIENT_FINISHED
        deadline = clock() + CLIENT_FINISHED_TIMEOUT_S
        JSONObject()
            .put("type", "pairing_ready")
            .put("version", PAIRING_VERSION)
            .put("device_id", deviceId)
            .put("node_id", nodeId)
            .put("mac", mac)
            .put("model", PAIRING_MODEL)
            .put("firmware_version", firmwareVersion)
            .put("pairing_auth", AUTH_COMMUNITY)
            .put("pairing_auth_epoch", 0)
            .put("pairing_policy", POLICY_APP)
            .put("device_pub", b64urlEncode(devicePub))
            .put("device_nonce", b64urlEncode(deviceNonce))
            .put("transcript_hash", b64urlEncode(transcriptHash))
            .put("session_id", sessionIdB64)
    }

    /**
     * Opens one mobile-to-device `pairing_encrypted` record. Any failure (wrong session,
     * skipped or replayed counter, bad tag, expiry) clears the session, as the firmware does.
     */
    fun decrypt(envelope: JSONObject): String = synchronized(lock) {
        if (expireLocked() || state == PairingState.IDLE) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
        try {
            if (envelope.opt("session_id") != sessionIdB64) throw IllegalArgumentException("wrong session")
            val counter = parseCounter(envelope.opt("counter"))
            if (counter != rxCounter) throw IllegalArgumentException("unexpected counter")
            val ciphertext = b64urlDecode(envelope.opt("ciphertext"), MAX_CIPHERTEXT_B64_CHARS)
            val tag = b64urlDecode(envelope.opt("tag"))
            if (tag.size != TAG_BYTES) throw IllegalArgumentException("invalid tag length")
            val plaintext = Gcm.open(rxKey!!, recordNonce(TO_DEVICE, counter), recordAad(sessionIdB64, TO_DEVICE, counter), ciphertext + tag)
            rxCounter += 1
            String(plaintext, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        } catch (e: AEADBadTagException) {
            resetLocked()
            throw PairingError(ERROR_DECRYPT)
        }
    }

    /**
     * Confirms the session after the first decrypted record, which must be exactly
     * `{"action":"pairing_client_finished"}`. Returns the new generation, or 0 (after clearing
     * the session) if the record is invalid.
     */
    fun handleClientFinished(command: JSONObject): Long = synchronized(lock) {
        val ok = command.length() == 1 && command.opt("action") == "pairing_client_finished" &&
            !expireLocked() && state == PairingState.WAIT_CLIENT_FINISHED && rxCounter == 1L
        if (!ok) {
            resetLocked()
            return 0L
        }
        generation += 1
        state = PairingState.READY
        deadline = clock() + CONFIRMED_TIMEOUT_S
        generation
    }

    /** Enters provisioning from a confirmed session; returns its generation, or 0. */
    fun markProvisioning(): Long = synchronized(lock) {
        if (!expireLocked() && state == PairingState.READY) {
            generation += 1
            state = PairingState.PROVISIONING
            deadline = clock() + PROVISIONING_TIMEOUT_S
        }
        if (state == PairingState.PROVISIONING) generation else 0L
    }

    fun extendProvisioning(gen: Long): Boolean = synchronized(lock) {
        val valid = provisioningLocked(gen)
        if (valid) deadline = clock() + PROVISIONING_TIMEOUT_S
        valid
    }

    /** Runs [commit] under the pairing lock if the session is still valid; it must only persist local state. */
    fun commitProvisioning(gen: Long, commit: () -> Boolean): Boolean = synchronized(lock) {
        provisioningLocked(gen) && commit()
    }

    /**
     * Seals a device-to-mobile record. Returns null when there is no active session, or when a
     * nonzero [gen] no longer matches (the work it belongs to is stale).
     */
    fun encryptJson(plaintext: String, gen: Long = 0L): JSONObject? = synchronized(lock) {
        if ((gen != 0L && gen != generation) || expireLocked() || state == PairingState.IDLE) return null
        val counter = txCounter
        val sealed = Gcm.seal(txKey!!, recordNonce(FROM_DEVICE, counter), recordAad(sessionIdB64, FROM_DEVICE, counter), plaintext.toByteArray())
        txCounter += 1
        JSONObject()
            .put("type", "pairing_encrypted")
            .put("session_id", sessionIdB64)
            .put("counter", counter.toString())
            .put("ciphertext", b64urlEncode(sealed.copyOfRange(0, sealed.size - TAG_BYTES)))
            .put("tag", b64urlEncode(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
    }

    fun encryptStatus(status: String, gen: Long = 0L): JSONObject? {
        val message = JSONObject().put("type", "status").put("status", status)
        // Apps read only type and status, so older ones ignore the token.
        if (!sdkToken.isNullOrEmpty() && status == "pairing_confirmed") message.put("sdk_token", sdkToken)
        return encryptJson(message.toString(), gen)
    }

    private fun provisioningLocked(gen: Long): Boolean =
        gen != 0L && gen == generation && !expireLocked() && state == PairingState.PROVISIONING

    private fun resetLocked() {
        generation += 1
        clearLocked()
    }

    private fun clearLocked() {
        state = PairingState.IDLE
        deadline = 0.0
        rxKey = null
        txKey = null
        sessionIdB64 = ""
        rxCounter = 0
        txCounter = 0
    }

    /** Drops the keys but keeps the generation, so the owner of an expired session can still recognise it. */
    private fun expireLocked(): Boolean {
        if (state == PairingState.IDLE || clock() <= deadline) return false
        clearLocked()
        return true
    }
}
