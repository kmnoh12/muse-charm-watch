package muse.gadget

import java.security.MessageDigest
import muse.gadget.noise.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Known-answer tests: every byte must match what the reference Python SDK produced. */
class NoiseVectorsTest {
    private val v = JSONObject(javaClass.getResource("/noise_vectors.json")!!.readText())

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun initiatorWithPinnedKeys(): NoiseXXInitiator {
        val keys = strings(v.getJSONArray("keys")).map { X25519.fromPrivateBytes(it.unhex()) }
        val seq = listOf(keys[0], keys[3]).iterator() // e_i, s_i
        return NoiseXXInitiator { seq.next() }
    }

    private fun handshake(): Pair<NoiseXXInitiator, ByteArray> {
        val init = initiatorWithPinnedKeys()
        init.initialize()
        assertEquals(v.getString("msg1"), init.writeMessage1().hex())
        val payload = init.readMessage2(v.getString("msg2").unhex())
        return init to payload
    }

    @Test
    fun handshakeMatchesReference() {
        val (init, payload) = handshake()
        assertEquals(v.getString("payload2"), payload.hex())
        assertEquals(v.getString("msg3"), init.writeMessage3().hex())
        assertEquals(v.getString("handshake_hash"), init.handshakeHash().hex())
    }

    private fun transport(): NoiseTransport {
        val (init, _) = handshake()
        init.writeMessage3()
        val (send, recv) = init.split()
        return NoiseTransport(send, recv, chunkIds = { v.getLong("chunk_id") })
    }

    @Test
    fun clientFramesMatchReference() {
        val tr = transport()
        val cf = v.getJSONObject("client_frames")

        val start = tr.startStreamRequest("POST", "/link-control")
        assertEquals(cf.getJSONObject("start_stream").getLong("stream_id"), start.streamId)
        assertEquals(strings(cf.getJSONObject("start_stream").getJSONArray("frames")), start.frames.map { it.hex() })

        assertEquals(strings(cf.getJSONArray("body_chunk")), tr.encryptBodyChunk(start.streamId, "hello".toByteArray()).map { it.hex() })

        val http = tr.encryptHttpRequest("POST", "/chat/stream", """{"message":"hi"}""".toByteArray(), headers = listOf(Header("x-app-id", "musegadget")))
        assertEquals(cf.getJSONObject("http_request").getLong("stream_id"), http.streamId)
        assertEquals(strings(cf.getJSONObject("http_request").getJSONArray("frames")), http.frames.map { it.hex() })

        assertEquals(strings(cf.getJSONArray("reset")), tr.encryptReset(http.streamId, reason = "bye").map { it.hex() })
    }

    @Test
    fun decryptsServerFramesFromReference() {
        val tr = transport()
        val sf = v.getJSONObject("server_frames")

        val resp = assertNotNull(tr.decryptFrame(strings(sf.getJSONArray("response"))[0].unhex()))
        assertEquals("response", resp.kind)
        assertEquals(1L, resp.streamId)
        val r = (resp.body as FrameBody.Response).value
        assertEquals(200, r.status)
        assertEquals("ok", String(r.body))
        assertTrue(r.endBody)

        val chunk = assertNotNull(tr.decryptFrame(strings(sf.getJSONArray("body_chunk"))[0].unhex()))
        assertEquals("chunk-data", String((chunk.body as FrameBody.Chunk).value.data))

        val rst = assertNotNull(tr.decryptFrame(strings(sf.getJSONArray("reset"))[0].unhex()))
        val reset = (rst.body as FrameBody.Rst).value
        assertEquals(ResetCode.CANCELLED, reset.code)
        assertEquals("nope", reset.reason)

        val big = strings(sf.getJSONArray("big_chunk"))
        assertEquals(2, big.size)
        assertNull(tr.decryptFrame(big[0].unhex()))
        val whole = assertNotNull(tr.decryptFrame(big[1].unhex()))
        val data = (whole.body as FrameBody.Chunk).value.data
        assertEquals(70000, data.size)
        assertEquals(v.getString("big_sha256"), MessageDigest.getInstance("SHA-256").digest(data).hex())
    }

    @Test
    fun tamperedFrameKillsTheTransport() {
        val tr = transport()
        val frame = strings(v.getJSONObject("server_frames").getJSONArray("response"))[0].unhex()
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 1).toByte()
        assertFailsWith<NoiseProtocolError> { tr.decryptFrame(frame) }
        assertFailsWith<NoiseProtocolError> { tr.encryptBodyChunk(1, byteArrayOf(1)) }
    }

    @Test
    fun x25519RejectsLowOrderPoints() {
        val kp = X25519.generate()
        assertFailsWith<NoiseProtocolError> { X25519.dh(kp.privateKey, ByteArray(32)) }
        assertFailsWith<NoiseProtocolError> { X25519.dh(kp.privateKey, ByteArray(31)) }
    }

    @Test
    fun responderAndInitiatorInteroperateInProcess() {
        val i = NoiseXXInitiator(); i.initialize()
        val r = NoiseXXResponder(payload = "p".toByteArray()); r.initialize()
        val m2 = r.readMessage1AndWriteMessage2(i.writeMessage1())
        assertContentEquals("p".toByteArray(), i.readMessage2(m2))
        r.readMessage3(i.writeMessage3())
        assertContentEquals(i.handshakeHash(), r.handshakeHash())
        val (is_, ir) = i.split(); val (rs, rr) = r.split()
        assertContentEquals("ping".toByteArray(), rr.decryptWithAd(ByteArray(0), is_.encryptWithAd(ByteArray(0), "ping".toByteArray())))
        assertContentEquals("pong".toByteArray(), ir.decryptWithAd(ByteArray(0), rs.encryptWithAd(ByteArray(0), "pong".toByteArray())))
    }
}
