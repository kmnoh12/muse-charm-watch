package muse.gadget.noise

/** Service envelopes carried inside Noise frames (port of noise/envelope.py). */

enum class ServiceType(val code: Int) {
    DAEMON(0), SENTINEL(1), VAULT(2), AUTHD(3);

    companion object {
        fun of(code: Long): ServiceType =
            entries.firstOrNull { it.code.toLong() == code } ?: throw ProtoError("unknown service type")
    }
}

enum class ResetCode(val code: Int) {
    CODE_UNSPECIFIED(0), CANCELLED(1), TIMEOUT(2), PROTOCOL_ERROR(3),
    REFUSED_STREAM(4), INTERNAL_ERROR(5), SERVICE_UNAVAILABLE(6);

    companion object {
        fun of(code: Int): ResetCode =
            entries.firstOrNull { it.code == code } ?: throw ProtoError("unknown reset code")
    }
}

data class Header(val key: String = "", val value: String = "")

class ApplicationRequest(
    val verb: String = "",
    val path: String = "",
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
)

class ApplicationResponse(
    val status: Int = 0,
    val headers: List<Header> = emptyList(),
    val body: ByteArray = ByteArray(0),
    val endBody: Boolean = false,
)

class BodyChunk(val data: ByteArray = ByteArray(0), val endBody: Boolean = false)

data class Reset(val code: ResetCode = ResetCode.CODE_UNSPECIFIED, val reason: String = "")

/** One of the four payload kinds a [ServiceFrame] can carry. */
sealed class FrameBody {
    class Request(val value: ApplicationRequest) : FrameBody()
    class Response(val value: ApplicationResponse) : FrameBody()
    class Chunk(val value: BodyChunk) : FrameBody()
    class Rst(val value: Reset) : FrameBody()
}

class ServiceFrame(val streamId: Long = 0, val body: FrameBody? = null) {
    val kind: String?
        get() = when (body) {
            is FrameBody.Request -> "request"
            is FrameBody.Response -> "response"
            is FrameBody.Chunk -> "body_chunk"
            is FrameBody.Rst -> "reset"
            null -> null
        }
}

class ServiceRequest(val service: ServiceType = ServiceType.DAEMON, val payload: ByteArray = ByteArray(0))

class ServiceResponse(val payload: ByteArray = ByteArray(0))

private fun decodeString(raw: ByteArray): String =
    try {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw ProtoError("invalid utf-8 string")
    }

private fun expect(wire: Int, wanted: Int, what: String) {
    if (wire != wanted) throw ProtoError("$what wrong wire type")
}

fun encodeHeader(h: Header): ByteArray {
    var out = ByteArray(0)
    if (h.key.isNotEmpty()) out += stringField(1, h.key)
    if (h.value.isNotEmpty()) out += stringField(2, h.value)
    return out
}

fun decodeHeader(data: ByteArray): Header {
    var key = ""
    var value = ""
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_DELIMITED, "Header.key"); key = decodeString(r.readDelimited()) }
            2 -> { expect(wire, WIRE_DELIMITED, "Header.value"); value = decodeString(r.readDelimited()) }
            else -> r.skip(wire)
        }
    }
    return Header(key, value)
}

fun encodeApplicationRequest(req: ApplicationRequest): ByteArray {
    var out = ByteArray(0)
    if (req.verb.isNotEmpty()) out += stringField(1, req.verb)
    if (req.path.isNotEmpty()) out += stringField(2, req.path)
    for (h in req.headers) out += delimitedField(3, encodeHeader(h))
    if (req.body.isNotEmpty()) out += bytesField(4, req.body)
    if (req.endBody) out += boolField(5, true)
    return out
}

fun decodeApplicationRequest(data: ByteArray): ApplicationRequest {
    var verb = ""
    var path = ""
    val headers = mutableListOf<Header>()
    var body = ByteArray(0)
    var endBody = false
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_DELIMITED, "ApplicationRequest.verb"); verb = decodeString(r.readDelimited()) }
            2 -> { expect(wire, WIRE_DELIMITED, "ApplicationRequest.path"); path = decodeString(r.readDelimited()) }
            3 -> { expect(wire, WIRE_DELIMITED, "ApplicationRequest.headers"); headers += decodeHeader(r.readDelimited()) }
            4 -> { expect(wire, WIRE_DELIMITED, "ApplicationRequest.body"); body = r.readDelimited() }
            5 -> { expect(wire, WIRE_VARINT, "ApplicationRequest.end_body"); endBody = r.readVarint() != 0L }
            else -> r.skip(wire)
        }
    }
    return ApplicationRequest(verb, path, headers, body, endBody)
}

fun encodeApplicationResponse(resp: ApplicationResponse): ByteArray {
    var out = ByteArray(0)
    if (resp.status != 0) out += int32Field(1, resp.status)
    for (h in resp.headers) out += delimitedField(2, encodeHeader(h))
    if (resp.body.isNotEmpty()) out += bytesField(3, resp.body)
    if (resp.endBody) out += boolField(4, true)
    return out
}

fun decodeApplicationResponse(data: ByteArray): ApplicationResponse {
    var status = 0
    val headers = mutableListOf<Header>()
    var body = ByteArray(0)
    var endBody = false
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_VARINT, "ApplicationResponse.status"); status = decodeInt32(r.readVarint()) }
            2 -> { expect(wire, WIRE_DELIMITED, "ApplicationResponse.headers"); headers += decodeHeader(r.readDelimited()) }
            3 -> { expect(wire, WIRE_DELIMITED, "ApplicationResponse.body"); body = r.readDelimited() }
            4 -> { expect(wire, WIRE_VARINT, "ApplicationResponse.end_body"); endBody = r.readVarint() != 0L }
            else -> r.skip(wire)
        }
    }
    return ApplicationResponse(status, headers, body, endBody)
}

fun encodeBodyChunk(chunk: BodyChunk): ByteArray {
    var out = ByteArray(0)
    if (chunk.data.isNotEmpty()) out += bytesField(1, chunk.data)
    if (chunk.endBody) out += boolField(2, true)
    return out
}

fun decodeBodyChunk(data: ByteArray): BodyChunk {
    var chunkData = ByteArray(0)
    var endBody = false
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_DELIMITED, "BodyChunk.data"); chunkData = r.readDelimited() }
            2 -> { expect(wire, WIRE_VARINT, "BodyChunk.end_body"); endBody = r.readVarint() != 0L }
            else -> r.skip(wire)
        }
    }
    return BodyChunk(chunkData, endBody)
}

fun encodeReset(reset: Reset): ByteArray {
    var out = ByteArray(0)
    if (reset.code != ResetCode.CODE_UNSPECIFIED) out += int32Field(1, reset.code.code)
    if (reset.reason.isNotEmpty()) out += stringField(2, reset.reason)
    return out
}

fun decodeReset(data: ByteArray): Reset {
    var code = ResetCode.CODE_UNSPECIFIED
    var reason = ""
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_VARINT, "Reset.code"); code = ResetCode.of(decodeInt32(r.readVarint())) }
            2 -> { expect(wire, WIRE_DELIMITED, "Reset.reason"); reason = decodeString(r.readDelimited()) }
            else -> r.skip(wire)
        }
    }
    return Reset(code, reason)
}

fun encodeServiceFrame(frame: ServiceFrame): ByteArray {
    var out = ByteArray(0)
    if (frame.streamId != 0L) out += int64Field(1, frame.streamId)
    when (val b = frame.body) {
        null -> {}
        is FrameBody.Request -> out += delimitedField(2, encodeApplicationRequest(b.value))
        is FrameBody.Response -> out += delimitedField(3, encodeApplicationResponse(b.value))
        is FrameBody.Chunk -> out += delimitedField(4, encodeBodyChunk(b.value))
        is FrameBody.Rst -> out += delimitedField(5, encodeReset(b.value))
    }
    return out
}

fun decodeServiceFrame(data: ByteArray): ServiceFrame {
    var streamId = 0L
    var body: FrameBody? = null
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_VARINT, "ServiceFrame.stream_id"); streamId = decodeInt64(r.readVarint()) }
            2 -> { expect(wire, WIRE_DELIMITED, "ServiceFrame.request"); body = FrameBody.Request(decodeApplicationRequest(r.readDelimited())) }
            3 -> { expect(wire, WIRE_DELIMITED, "ServiceFrame.response"); body = FrameBody.Response(decodeApplicationResponse(r.readDelimited())) }
            4 -> { expect(wire, WIRE_DELIMITED, "ServiceFrame.body_chunk"); body = FrameBody.Chunk(decodeBodyChunk(r.readDelimited())) }
            5 -> { expect(wire, WIRE_DELIMITED, "ServiceFrame.reset"); body = FrameBody.Rst(decodeReset(r.readDelimited())) }
            else -> r.skip(wire)
        }
    }
    return ServiceFrame(streamId, body)
}

fun encodeServiceRequest(req: ServiceRequest): ByteArray {
    var out = ByteArray(0)
    if (req.service != ServiceType.DAEMON) out += varintField(1, req.service.code.toLong())
    if (req.payload.isNotEmpty()) out += bytesField(2, req.payload)
    return out
}

fun decodeServiceRequest(data: ByteArray): ServiceRequest {
    var service = ServiceType.DAEMON
    var payload = ByteArray(0)
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_VARINT, "ServiceRequest.service"); service = ServiceType.of(r.readVarint()) }
            2 -> { expect(wire, WIRE_DELIMITED, "ServiceRequest.payload"); payload = r.readDelimited() }
            else -> r.skip(wire)
        }
    }
    return ServiceRequest(service, payload)
}

fun encodeServiceResponse(resp: ServiceResponse): ByteArray =
    if (resp.payload.isEmpty()) ByteArray(0) else bytesField(1, resp.payload)

fun decodeServiceResponse(data: ByteArray): ServiceResponse {
    var payload = ByteArray(0)
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { expect(wire, WIRE_DELIMITED, "ServiceResponse.payload"); payload = r.readDelimited() }
            else -> r.skip(wire)
        }
    }
    return ServiceResponse(payload)
}
