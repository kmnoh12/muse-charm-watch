package muse.gadget.noise

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Noise_XX_25519_AESGCM_SHA256 (port of noise/noise_xx.py). */

const val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256"
const val DH_KEY_LEN = 32
const val AEAD_TAG_LEN = 16
const val MIN_MSG2_LEN = DH_KEY_LEN + (DH_KEY_LEN + AEAD_TAG_LEN) + AEAD_TAG_LEN
const val MIN_MSG3_LEN = DH_KEY_LEN + AEAD_TAG_LEN + AEAD_TAG_LEN
private const val MAX_SAFE_NONCE = (1L shl 53) - 1

class NoiseProtocolError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private val X25519_LOW_ORDER_POINTS: List<ByteArray> = listOf(
    ByteArray(32),
    ByteArray(32).also { it[0] = 1 },
    hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
    hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
    hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
)

class X25519KeyPair(val privateKey: PrivateKey, val publicBytes: ByteArray)

/** X25519 on the JDK's XDH provider (raw 32-byte little-endian encodings, as Noise wants). */
object X25519 {
    private val spec = NamedParameterSpec.X25519
    private val basePoint = ByteArray(32).also { it[0] = 9 }

    private fun toLittleEndian32(u: BigInteger): ByteArray {
        val be = u.toByteArray()
        val out = ByteArray(32)
        for (i in be.indices) {
            val dst = be.size - 1 - i
            if (dst < 32) out[dst] = be[i]
        }
        return out
    }

    private fun publicKeyOf(raw: ByteArray): java.security.PublicKey {
        val copy = raw.copyOf()
        copy[31] = (copy[31].toInt() and 0x7F).toByte() // RFC 7748: mask the top bit
        val u = BigInteger(1, copy.reversedArray())
        return KeyFactory.getInstance("XDH").generatePublic(XECPublicKeySpec(spec, u))
    }

    private fun rawDh(priv: PrivateKey, pub: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("XDH")
        ka.init(priv)
        ka.doPhase(publicKeyOf(pub), true)
        return ka.generateSecret()
    }

    fun generate(): X25519KeyPair {
        val kp = KeyPairGenerator.getInstance("XDH").also { it.initialize(spec) }.generateKeyPair()
        return X25519KeyPair(kp.private, toLittleEndian32((kp.public as XECPublicKey).u))
    }

    /** Deterministic key pair, for tests and known-answer vectors. */
    fun fromPrivateBytes(raw: ByteArray): X25519KeyPair {
        val priv = KeyFactory.getInstance("XDH").generatePrivate(XECPrivateKeySpec(spec, raw))
        return X25519KeyPair(priv, rawDh(priv, basePoint))
    }

    fun dh(priv: PrivateKey, pub: ByteArray): ByteArray {
        if (pub.size != DH_KEY_LEN) throw NoiseProtocolError("x25519: invalid public key length")
        for (low in X25519_LOW_ORDER_POINTS) {
            if (MessageDigest.isEqual(pub, low)) throw NoiseProtocolError("x25519: rejected low-order public key")
        }
        val shared = try {
            rawDh(priv, pub)
        } catch (e: java.security.GeneralSecurityException) {
            throw NoiseProtocolError("x25519: key agreement failed", e)
        }
        if (MessageDigest.isEqual(shared, ByteArray(32))) throw NoiseProtocolError("x25519: DH produced all-zeros output")
        return shared
    }
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    parts.forEach { md.update(it) }
    return md.digest()
}

/** Noise HKDF with 2 or 3 outputs. */
internal fun noiseHkdf(chainingKey: ByteArray, ikm: ByteArray, numOutputs: Int): List<ByteArray> {
    require(numOutputs == 2 || numOutputs == 3) { "hkdf supports 2 or 3 outputs" }
    val tempKey = hmacSha256(chainingKey, ikm)
    val o1 = hmacSha256(tempKey, byteArrayOf(1))
    val o2 = hmacSha256(tempKey, o1 + byteArrayOf(2))
    if (numOutputs == 2) return listOf(o1, o2)
    val o3 = hmacSha256(tempKey, o2 + byteArrayOf(3))
    return listOf(o1, o2, o3)
}

internal fun buildNonceIv(nonce: Long): ByteArray {
    if (nonce < 0) throw NoiseProtocolError("nonce outside uint64 range")
    val iv = ByteArray(12)
    for (i in 0 until 8) iv[11 - i] = (nonce ushr (8 * i)).toByte()
    return iv
}

class CipherState {
    private var key: ByteArray? = null
    private var nonce = 0L
    private var poisoned = false

    private fun assertAlive() {
        if (poisoned) throw NoiseProtocolError("CipherState: poisoned after prior failure")
    }

    @Synchronized
    fun initializeKey(newKey: ByteArray) {
        assertAlive()
        if (newKey.size != 32) throw NoiseProtocolError("CipherState: AES-GCM key must be 32 bytes")
        key = newKey.copyOf()
        nonce = 0
    }

    fun hasKey(): Boolean = key != null

    private fun nextNonce(): Long {
        if (nonce >= MAX_SAFE_NONCE) {
            poisoned = true
            throw NoiseProtocolError("CipherState: nonce exhausted")
        }
        return nonce++
    }

    private fun cipher(mode: Int, n: Long, ad: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, buildNonceIv(n)))
        c.updateAAD(ad)
        return c
    }

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        assertAlive()
        if (key == null) return plaintext
        val n = nextNonce()
        try {
            return cipher(Cipher.ENCRYPT_MODE, n, ad).doFinal(plaintext)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        assertAlive()
        if (key == null) return ciphertext
        val n = nextNonce()
        try {
            return cipher(Cipher.DECRYPT_MODE, n, ad).doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            poisoned = true
            throw NoiseProtocolError("CipherState: decrypt failed", e)
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }
}

private class SymmetricState {
    private var ck = ByteArray(32)
    private var h = ByteArray(32)
    private var cipher = CipherState()

    fun initialize() {
        val padded = ByteArray(32)
        val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        name.copyInto(padded)
        h = padded
        ck = h
        mixHash(ByteArray(0))
    }

    fun mixHash(data: ByteArray) {
        h = sha256(h, data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = noiseHkdf(ck, ikm, 2)
        ck = newCk
        cipher = CipherState().also { it.initializeKey(tempK) }
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = noiseHkdf(ck, ByteArray(0), 2)
        ck = ByteArray(32)
        h = ByteArray(32)
        return CipherState().also { it.initializeKey(k1) } to CipherState().also { it.initializeKey(k2) }
    }

    fun handshakeHash(): ByteArray = h.copyOf()
}

private enum class Phase { CREATED, INITIALIZED, MSG1_SENT, MSG2_READ, MSG3_SENT, SPLIT, DEAD }

private fun requirePhase(actual: Phase, expected: Phase, method: String) {
    if (actual == Phase.DEAD) throw NoiseProtocolError("NoiseXX: $method called on dead handshake")
    if (actual != expected) {
        throw NoiseProtocolError("NoiseXX: $method called in wrong phase (expected ${expected.ordinal}, got ${actual.ordinal})")
    }
}

class NoiseXXInitiator(private val newKeyPair: () -> X25519KeyPair = X25519::generate) {
    private val ss = SymmetricState()
    private var e: X25519KeyPair? = null
    private var s: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var phase = Phase.CREATED

    fun initialize() {
        requirePhase(phase, Phase.CREATED, "initialize")
        ss.initialize()
        phase = Phase.INITIALIZED
    }

    fun writeMessage1(): ByteArray {
        requirePhase(phase, Phase.INITIALIZED, "write_message1")
        try {
            val eph = newKeyPair().also { e = it }
            ss.mixHash(eph.publicBytes)
            ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG1_SENT
            return eph.publicBytes
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    fun readMessage2(msg: ByteArray): ByteArray {
        requirePhase(phase, Phase.MSG1_SENT, "read_message2")
        if (msg.size < MIN_MSG2_LEN) {
            phase = Phase.DEAD
            throw NoiseProtocolError("NoiseXX: message 2 too short (${msg.size} < $MIN_MSG2_LEN)")
        }
        try {
            var offset = 0
            val remoteE = msg.copyOfRange(offset, offset + DH_KEY_LEN).also { re = it }
            ss.mixHash(remoteE)
            offset += DH_KEY_LEN

            val eph = e ?: throw NoiseProtocolError("NoiseXX: missing initiator ephemeral key")
            ss.mixKey(X25519.dh(eph.privateKey, remoteE))

            val remoteS = ss.decryptAndHash(msg.copyOfRange(offset, offset + DH_KEY_LEN + AEAD_TAG_LEN)).also { rs = it }
            offset += DH_KEY_LEN + AEAD_TAG_LEN

            ss.mixKey(X25519.dh(eph.privateKey, remoteS))

            val payload = ss.decryptAndHash(msg.copyOfRange(offset, msg.size))
            phase = Phase.MSG2_READ
            return payload
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    fun writeMessage3(): ByteArray {
        requirePhase(phase, Phase.MSG2_READ, "write_message3")
        try {
            val stat = newKeyPair().also { s = it }
            val encS = ss.encryptAndHash(stat.publicBytes)
            val remoteE = re ?: throw NoiseProtocolError("NoiseXX: missing responder ephemeral key")
            ss.mixKey(X25519.dh(stat.privateKey, remoteE))
            val encPayload = ss.encryptAndHash(ByteArray(0))
            phase = Phase.MSG3_SENT
            return encS + encPayload
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    /** Returns (send, receive) cipher states. */
    fun split(): Pair<CipherState, CipherState> {
        requirePhase(phase, Phase.MSG3_SENT, "split")
        phase = Phase.SPLIT
        val result = ss.split()
        e = null; s = null; re = null; rs = null
        return result
    }

    fun destroy() {
        e = null; s = null; re = null; rs = null
        phase = Phase.DEAD
    }

    fun remoteStaticPublicKey(): ByteArray? = rs?.copyOf()

    fun handshakeHash(): ByteArray = ss.handshakeHash()
}

/** Test responder, used by the fake VM in tests. */
class NoiseXXResponder(
    private val payload: ByteArray = ByteArray(0),
    private val newKeyPair: () -> X25519KeyPair = X25519::generate,
) {
    private val ss = SymmetricState()
    private var e: X25519KeyPair? = null
    private var s: X25519KeyPair? = null
    private var re: ByteArray? = null
    private var phase = Phase.CREATED

    fun initialize() {
        requirePhase(phase, Phase.CREATED, "initialize")
        ss.initialize()
        phase = Phase.INITIALIZED
    }

    fun readMessage1AndWriteMessage2(msg1: ByteArray): ByteArray {
        requirePhase(phase, Phase.INITIALIZED, "read_message1_and_write_message2")
        if (msg1.size < DH_KEY_LEN) {
            phase = Phase.DEAD
            throw NoiseProtocolError("NoiseXX: message 1 too short (${msg1.size} < $DH_KEY_LEN)")
        }
        try {
            val remoteE = msg1.copyOfRange(0, DH_KEY_LEN).also { re = it }
            ss.mixHash(remoteE)
            ss.decryptAndHash(msg1.copyOfRange(DH_KEY_LEN, msg1.size))

            val eph = newKeyPair().also { e = it }
            ss.mixHash(eph.publicBytes)
            ss.mixKey(X25519.dh(eph.privateKey, remoteE))

            val stat = newKeyPair().also { s = it }
            val encS = ss.encryptAndHash(stat.publicBytes)
            ss.mixKey(X25519.dh(stat.privateKey, remoteE))

            val encPayload = ss.encryptAndHash(payload)
            phase = Phase.MSG2_READ
            return eph.publicBytes + encS + encPayload
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    fun readMessage3(msg3: ByteArray) {
        requirePhase(phase, Phase.MSG2_READ, "read_message3")
        if (msg3.size < MIN_MSG3_LEN) {
            phase = Phase.DEAD
            throw NoiseProtocolError("NoiseXX: message 3 too short (${msg3.size} < $MIN_MSG3_LEN)")
        }
        try {
            var offset = 0
            val remoteS = ss.decryptAndHash(msg3.copyOfRange(offset, offset + DH_KEY_LEN + AEAD_TAG_LEN))
            offset += DH_KEY_LEN + AEAD_TAG_LEN
            val eph = e ?: throw NoiseProtocolError("NoiseXX: missing responder ephemeral key")
            ss.mixKey(X25519.dh(eph.privateKey, remoteS))
            ss.decryptAndHash(msg3.copyOfRange(offset, msg3.size))
            phase = Phase.MSG3_SENT
        } catch (ex: Exception) {
            phase = Phase.DEAD
            throw ex
        }
    }

    /** Returns (send, receive) from the responder's point of view. */
    fun split(): Pair<CipherState, CipherState> {
        requirePhase(phase, Phase.MSG3_SENT, "split")
        phase = Phase.SPLIT
        val (c1, c2) = ss.split()
        e = null; s = null; re = null
        return c2 to c1
    }

    fun handshakeHash(): ByteArray = ss.handshakeHash()
}
