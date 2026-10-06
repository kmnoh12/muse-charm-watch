package muse.gadget

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import muse.gadget.noise.*
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkClientTest {
    private val device = DeviceDescription(
        nodeId = "homelink-abcdef", displayName = "watch", version = "0.1.0",
        commands = JSONObject().put("system.run", JSONObject().put("description", "run")),
    )

    private class Rig(
        val session: LinkSession,
        val vm: FakeVm,
        val connects: MutableList<Pair<String, Map<String, String>>>,
    )

    private fun rig(run: suspend (String, JSONObject, Long?) -> JSONObject = { c, _, _ -> unsupportedCommand(c) }): Rig {
        val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
        val toVm = Channel<ByteArray>(Channel.UNLIMITED)
        val deviceWs = Pipe(toDevice, toVm)
        val connects = mutableListOf<Pair<String, Map<String, String>>>()
        val session = LinkSession("gw.example", "vm 1&x", "tok", device, run) { url, headers ->
            connects += url to headers
            deviceWs
        }
        return Rig(session, FakeVm(Pipe(toVm, toDevice)), connects)
    }

    /** Runs [body] with a 10 s limit, then cancels any session coroutines the test left running. */
    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(10_000) {
            body()
            coroutineContext[Job]!!.children.forEach { it.cancel() }
        }
    }

    @Test
    fun registerInvokeResultAndUnpair() = test {
        val calls = mutableListOf<Triple<String, JSONObject, Long?>>()
        val r = rig { c, p, t -> calls += Triple(c, p, t); JSONObject("""{"ok":true,"payload":{"stdout":"hi\n","exit_code":0}}""") }
        val stop = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Outcome>()
        val job = launch { outcome.complete(r.session.run(stop)) }

        r.vm.handshake()
        val request = r.vm.acceptControlStream()
        val app = (request.body as FrameBody.Request).value
        assertEquals(Triple("POST", "/link-control", false), Triple(app.verb, app.path, app.endBody))

        val register = r.vm.nextMessage()
        assertEquals("link.register", register.getString("method"))
        val params = register.getJSONObject("params")
        assertEquals("homelink-abcdef", params.getString("node_id"))
        assertEquals("linux", params.getString("platform"))
        assertTrue(params.getJSONObject("commands_v2").has("system.run"))
        r.vm.sendMessage(JSONObject().put("type", "res").put("id", register.getString("id")).put("ok", true))

        r.vm.sendMessage(JSONObject("""{"method":"link.invoke","id":"inv-1","command":"system.run","params":{"command":"echo hi"},"timeout_ms":5000}"""))
        val result = r.vm.nextMessage()
        assertEquals("link.result", result.getString("method"))
        assertEquals("inv-1", result.getString("id"))
        assertTrue(result.getBoolean("ok"))
        assertEquals("hi\n", result.getJSONObject("payload").getString("stdout"))
        assertEquals("system.run", calls.single().first)
        assertEquals("echo hi", calls.single().second.getString("command"))
        assertEquals(5000L, calls.single().third)
        assertTrue(r.session.isRegistered)

        r.vm.sendMessage(JSONObject("""{"type":"evt","event":"link.unpaired"}"""))
        assertEquals(Outcome.UNPAIRED, outcome.await())
        assertEquals("wss://gw.example/v1/noise?vm_id=vm%201%26x", r.connects.single().first)
        assertEquals(mapOf("Authorization" to "Bearer tok"), r.connects.single().second)
        job.join()
    }

    @Test
    fun forbiddenControlStream() = test {
        val r = rig()
        val outcome = CompletableDeferred<Outcome>()
        launch { outcome.complete(r.session.run(CompletableDeferred())) }
        r.vm.handshake()
        r.vm.acceptControlStream(status = 403)
        assertEquals(Outcome.FORBIDDEN, outcome.await())
    }

    @Test
    fun stopEndsTheSession() = test {
        val r = rig()
        val stop = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<Outcome>()
        launch { outcome.complete(r.session.run(stop)) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()
        stop.complete(Unit)
        assertEquals(Outcome.STOPPED, outcome.await())
    }

    @Test
    fun upgradeRejectedMapsToAuthOutcomes() = test {
        for ((status, expected) in listOf(401 to Outcome.AUTH_REJECTED, 403 to Outcome.FORBIDDEN)) {
            val session = LinkSession("h", "vm", "tok", device, { c, _, _ -> unsupportedCommand(c) }) { _, _ -> throw UpgradeRejected(status) }
            assertEquals(expected, session.run(CompletableDeferred()))
        }
    }

    @Test
    fun sendChatPostsADeviceAttributedMessageOnTheSameSession() = test {
        val r = rig()
        launch { r.session.run(CompletableDeferred()) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()

        val reply = CompletableDeferred<JSONObject>()
        launch { reply.complete(r.session.sendChat("porch light on", "side-1")) }
        val request = r.vm.nextFrame()
        val app = (request.body as FrameBody.Request).value
        assertEquals(Triple("POST", "/chat/stream", true), Triple(app.verb, app.path, app.endBody))
        val sent = JSONObject(String(app.body))
        assertEquals("porch light on", sent.getString("message"))
        assertEquals("text", sent.getString("output_modality"))
        assertEquals("homelink-abcdef", sent.getString("device_id"))
        assertEquals("side-1", sent.getString("session_id"))
        val headers = app.headers.associate { it.key.lowercase() to it.value }
        assertEquals("application/json", headers["content-type"])
        assertEquals("musegadget", headers["x-app-id"])

        r.vm.sendFrame(ServiceFrame(request.streamId, FrameBody.Response(ApplicationResponse(200, body = """{"accepted":true}""".toByteArray(), endBody = true))))
        val answer = reply.await()
        assertTrue(answer.getBoolean("ok"))
        assertEquals(200, answer.getInt("status"))
        assertTrue(answer.getJSONObject("response").getBoolean("accepted"))
    }

    @Test
    fun chatResponseCanBeReadAsItStreamsIn() = test {
        val r = rig()
        launch { r.session.run(CompletableDeferred()) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()

        val streamDeferred = CompletableDeferred<ChatStream>()
        launch { streamDeferred.complete(r.session.openChat("hello")) }
        val request = r.vm.nextFrame()
        val stream = streamDeferred.await()

        // The VM answers in pieces; the device must see each piece before the stream ends.
        r.vm.sendFrame(ServiceFrame(request.streamId, FrameBody.Response(ApplicationResponse(200, body = "First sentence. ".toByteArray()))))
        assertEquals(200, stream.status())
        assertEquals("First sentence. ", String(stream.chunks.receive()))
        r.vm.sendFrame(ServiceFrame(request.streamId, FrameBody.Chunk(BodyChunk("Second one.".toByteArray()))))
        assertEquals("Second one.", String(stream.chunks.receive()))
        r.vm.sendFrame(ServiceFrame(request.streamId, FrameBody.Chunk(BodyChunk(endBody = true))))
        assertTrue(stream.chunks.receiveCatching().isClosed)
    }

    @Test
    fun chatStreamFailsWhenTheVmResetsIt() = test {
        val r = rig()
        launch { r.session.run(CompletableDeferred()) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()
        val streamDeferred = CompletableDeferred<ChatStream>()
        launch { streamDeferred.complete(r.session.openChat("hello")) }
        val request = r.vm.nextFrame()
        val stream = streamDeferred.await()
        r.vm.sendFrame(ServiceFrame(request.streamId, FrameBody.Rst(Reset(ResetCode.INTERNAL_ERROR, "boom"))))
        assertFailsWith<java.io.IOException> { stream.status() }
    }

    @Test
    fun decoderHandlesSplitAndBatchedMessages() {
        val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        val data = encodeMessage(JSONObject("""{"a":1}""")) + encodeMessage(JSONObject("""{"b":2}""")) + zero
        val d = MessageDecoder()
        assertTrue(d.feed(data.copyOfRange(0, 5)).isEmpty())
        val out = d.feed(data.copyOfRange(5, data.size))
        assertEquals(listOf(1, 2), listOf(out[0].getInt("a"), out[1].getInt("b")))
    }

    @Test
    fun decoderRejectsOversizeMessages() {
        val huge = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1 shl 30).array()
        assertFailsWith<IllegalArgumentException> { MessageDecoder().feed(huge) }
    }

    @Test
    fun decoderDropsMessagesThatAreNotJson() {
        val garbage = byteArrayOf('{'.code.toByte(), 0xFF.toByte(), '}'.code.toByte())
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(garbage.size).array()
        val out = MessageDecoder().feed(header + garbage + encodeMessage(JSONObject("""{"b":2}""")))
        assertEquals(1, out.size)
        assertEquals(2, out[0].getInt("b"))
    }

    @Test
    fun vmIdIsEscapedLikeEncodeUriComponent() {
        assertEquals("wss://h/v1/noise?vm_id=a-b_c.d!~*'()%3F%26%3D", noiseUrl("h", "a-b_c.d!~*'()?&="))
    }

    @Test
    fun registerParamsCarryTheDeviceDescription() {
        val p = device.registerParams()
        assertEquals(false, p.getBoolean("is_wakeup_supported"))
        assertEquals("homehub", p.getString("device_family"))
        assertNotNull(p.getJSONObject("commands_v2"))
    }

    @Test
    fun messageRoundTrip() {
        val enc = encodeMessage(JSONObject("""{"k":"v"}"""))
        assertContentEquals(byteArrayOf(9, 0, 0, 0), enc.copyOfRange(0, 4))
    }
    @Test
    fun subscribeStreamsReplyEventsAsNdjson() = test {
        val r = rig()
        launch { r.session.run(CompletableDeferred()) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()

        val events = CompletableDeferred<List<JSONObject>>()
        launch {
            val flow = r.session.subscribe()
            val got = mutableListOf<JSONObject>()
            flow.collect { got += it; if (got.size == 3) events.complete(got) }
        }
        val start = r.vm.nextFrame()
        val app = (start.body as FrameBody.Request).value
        assertEquals(Triple("POST", "/chat/subscribe", false), Triple(app.verb, app.path, app.endBody))
        val headers = app.headers.associate { it.key.lowercase() to it.value }
        assertEquals("application/x-ndjson", headers["accept"])
        assertEquals("hatch-web", headers["x-app-id"])
        val body = r.vm.nextFrame().body as FrameBody.Chunk
        assertEquals("{}", String(body.value.data))
        assertTrue(body.value.endBody)

        // The VM streams lines; one of them is split across two frames.
        val l1 = """{"type":"subscribed"}""" + "\n"
        val l2 = """{"type":"event","seq":1,"event":"delta.text_append","payload":{"message_id":"m1","text":"Hi"}}""" + "\n"
        val l3 = """{"type":"event","seq":2,"event":"delta.message_done","payload":{"message_id":"m1"}}""" + "\n"
        r.vm.sendFrame(ServiceFrame(start.streamId, FrameBody.Response(ApplicationResponse(200, body = (l1 + l2.take(30)).toByteArray()))))
        r.vm.sendFrame(ServiceFrame(start.streamId, FrameBody.Chunk(BodyChunk((l2.drop(30) + l3).toByteArray()))))
        val got = events.await()
        assertEquals("subscribed", got[0].getString("type"))
        assertEquals("delta.text_append", got[1].getString("event"))
        assertEquals("delta.message_done", got[2].getString("event"))
    }

    @Test
    fun aVoiceNoteIsUploadedAsAStreamingJsonBody() = test {
        val r = rig()
        launch { r.session.run(CompletableDeferred()) }
        r.vm.handshake(); r.vm.acceptControlStream(); r.vm.nextMessage()

        val pcm = ByteArray(4801) { (it * 5).toByte() }
        val uploaded = CompletableDeferred<OutboundStream>()
        launch {
            val note = r.session.openStreamRequest("POST", "/chat/stream")
            val body = muse.gadget.chat.VoiceNoteBody()
            note.write(body.append(pcm.copyOfRange(0, 2000)))
            note.write(body.append(pcm.copyOfRange(2000, pcm.size)))
            note.write(body.finish(), last = true)
            uploaded.complete(note)
        }
        val start = r.vm.nextFrame()
        val app = (start.body as FrameBody.Request).value
        assertEquals(Triple("POST", "/chat/stream", false), Triple(app.verb, app.path, app.endBody))
        assertEquals("hatch-web", app.headers.associate { it.key.lowercase() to it.value }["x-app-id"])
        val received = java.io.ByteArrayOutputStream()
        while (true) {
            val chunk = (r.vm.nextFrame().body as FrameBody.Chunk).value
            received.write(chunk.data)
            if (chunk.endBody) break
        }
        val json = JSONObject(String(received.toByteArray()))
        val audio = java.util.Base64.getDecoder().decode(json.getJSONArray("items").getJSONObject(0).getString("data_base64"))
        assertContentEquals(muse.gadget.chat.VoiceNote.wavHeader() + pcm, audio)

        // The VM acknowledges the note with the user message's id.
        r.vm.sendFrame(ServiceFrame(start.streamId, FrameBody.Response(ApplicationResponse(200, body = """{"result":{"message_id":"u1"}}""".toByteArray(), endBody = true))))
        val note = uploaded.await()
        assertEquals(200, note.response.status())
        val ack = muse.gadget.chat.parseChatAck(String(note.response.chunks.receive()))
        assertEquals("u1", ack?.messageId)
    }
}
