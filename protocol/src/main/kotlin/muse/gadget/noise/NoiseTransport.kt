package muse.gadget.noise

/** Encrypted request/response streams over an established Noise session (port of noise/transport.py). */

private val EMPTY_AD = ByteArray(0)

class EncryptedFrames(val streamId: Long, val frames: List<ByteArray>)

class DecryptedFrame(val streamId: Long, val body: FrameBody) {
    val kind: String
        get() = when (body) {
            is FrameBody.Response -> "response"
            is FrameBody.Chunk -> "body_chunk"
            is FrameBody.Rst -> "reset"
            is FrameBody.Request -> "request"
        }
}

/** [chunkIds] pins the random chunk id, for known-answer tests; production leaves it null. */
class NoiseTransport(
    private val send: CipherState,
    private val recv: CipherState,
    private val chunkIds: (() -> Long)? = null,
) {
    private val decoder = NoiseFrameDecoder()
    private var nextStreamId = 1L
    private var dead = false

    private fun assertAlive() {
        if (dead) throw NoiseProtocolError("NoiseTransport: dead after prior failure")
    }

    @Synchronized
    fun encryptHttpRequest(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        service: ServiceType = ServiceType.DAEMON,
        headers: List<Header> = emptyList(),
    ): EncryptedFrames = sendApplicationRequest(method, path, body, service, headers, endBody = true)

    @Synchronized
    fun startStreamRequest(
        method: String,
        path: String,
        service: ServiceType = ServiceType.DAEMON,
        headers: List<Header> = emptyList(),
    ): EncryptedFrames = sendApplicationRequest(method, path, ByteArray(0), service, headers, endBody = false)

    private fun sendApplicationRequest(
        method: String, path: String, body: ByteArray, service: ServiceType,
        headers: List<Header>, endBody: Boolean,
    ): EncryptedFrames {
        assertAlive()
        try {
            val streamId = nextStreamId++
            val request = ApplicationRequest(method, path, headers, body, endBody)
            val frames = encryptRequest(service, ServiceFrame(streamId, FrameBody.Request(request)))
            return EncryptedFrames(streamId, frames)
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    @Synchronized
    fun encryptBodyChunk(
        streamId: Long,
        data: ByteArray,
        service: ServiceType = ServiceType.DAEMON,
        endBody: Boolean = false,
    ): List<ByteArray> {
        assertAlive()
        try {
            return encryptRequest(service, ServiceFrame(streamId, FrameBody.Chunk(BodyChunk(data, endBody))))
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    @Synchronized
    fun encryptReset(
        streamId: Long,
        service: ServiceType = ServiceType.DAEMON,
        reason: String = "",
        code: ResetCode = ResetCode.CANCELLED,
    ): List<ByteArray> {
        assertAlive()
        try {
            return encryptRequest(service, ServiceFrame(streamId, FrameBody.Rst(Reset(code, reason))))
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    /** Decrypts one WebSocket message; returns null while a chunked message is still incomplete. */
    @Synchronized
    fun decryptFrame(ciphertext: ByteArray): DecryptedFrame? {
        assertAlive()
        try {
            val plainFrame = recv.decryptWithAd(EMPTY_AD, ciphertext)
            val reassembled = decoder.decode(plainFrame) ?: return null
            val response = decodeServiceResponse(reassembled)
            if (response.payload.isEmpty()) throw NoiseProtocolError("empty ServiceResponse payload")
            val frame = decodeServiceFrame(response.payload)
            return when (val b = frame.body) {
                is FrameBody.Response, is FrameBody.Chunk, is FrameBody.Rst -> DecryptedFrame(frame.streamId, b)
                is FrameBody.Request -> throw NoiseProtocolError("NoiseTransport: unexpected request frame from server")
                null -> null
            }
        } catch (e: Exception) {
            dead = true
            throw e
        }
    }

    private fun encryptRequest(service: ServiceType, frame: ServiceFrame): List<ByteArray> {
        val frameBytes = encodeServiceFrame(frame)
        val requestBytes = encodeServiceRequest(ServiceRequest(service, frameBytes))
        return encodeNoiseFrames(requestBytes, chunkIds?.invoke()).map { send.encryptWithAd(EMPTY_AD, it) }
    }
}

fun decodeRequestEnvelope(data: ByteArray): ServiceFrame = decodeServiceFrame(decodeServiceRequest(data).payload)

fun encodeResponseEnvelope(frame: ServiceFrame): ByteArray =
    encodeServiceResponse(ServiceResponse(encodeServiceFrame(frame)))
