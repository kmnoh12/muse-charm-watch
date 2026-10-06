package muse.gadget

import kotlinx.coroutines.channels.Channel
import muse.gadget.noise.*
import org.json.JSONObject

internal class Pipe(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) : WsConnection {
    override suspend fun send(data: ByteArray) = outbox.send(data)
    override suspend fun receive(): ByteArray = try { inbox.receive() } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) { throw java.io.IOException("closed") }
    override suspend fun close() { outbox.close() }
}

/** Just enough of the Muse VM: a Noise responder plus the control stream. */
internal class FakeVm(private val ws: Pipe) {
    private val decoder = NoiseFrameDecoder()
    private val messages = MessageDecoder()
    private var streamId = 0L
    private lateinit var sendCipher: CipherState
    private lateinit var recvCipher: CipherState

    suspend fun handshake() {
        val r = NoiseXXResponder(); r.initialize()
        ws.send(r.readMessage1AndWriteMessage2(ws.receive()))
        r.readMessage3(ws.receive())
        val (s, rc) = r.split(); sendCipher = s; recvCipher = rc
    }

    suspend fun nextFrame(): ServiceFrame {
        while (true) {
            val plain = recvCipher.decryptWithAd(ByteArray(0), ws.receive())
            decoder.decode(plain)?.let { return decodeRequestEnvelope(it) }
        }
    }

    suspend fun nextMessage(): JSONObject {
        while (true) {
            val frame = nextFrame()
            val body = frame.body as FrameBody.Chunk
            messages.feed(body.value.data).firstOrNull()?.let { return it }
        }
    }

    suspend fun sendFrame(frame: ServiceFrame) {
        for (chunk in encodeNoiseFrames(encodeResponseEnvelope(frame))) ws.send(sendCipher.encryptWithAd(ByteArray(0), chunk))
    }

    suspend fun acceptControlStream(status: Int = 200): ServiceFrame {
        val request = nextFrame()
        streamId = request.streamId
        sendFrame(ServiceFrame(streamId, FrameBody.Response(ApplicationResponse(status = status, endBody = status >= 400))))
        return request
    }

    suspend fun sendMessage(message: JSONObject) =
        sendFrame(ServiceFrame(streamId, FrameBody.Chunk(BodyChunk(data = encodeMessage(message)))))
}
