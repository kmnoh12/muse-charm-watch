package muse.gadget

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import muse.gadget.noise.DecryptedFrame
import muse.gadget.noise.FrameBody
import muse.gadget.noise.Header
import muse.gadget.noise.NoiseTransport
import muse.gadget.noise.NoiseXXInitiator
import org.json.JSONException
import org.json.JSONObject

/**
 * One control session with a Muse VM (port of link_client.py).
 *
 * Opens a WebSocket to `/v1/noise` with the per-VM bearer, runs the Noise XX handshake, then opens a
 * long-lived `POST /link-control` stream. Both directions carry JSON messages, each prefixed with its
 * length as a little-endian u32. Messages the device sends to the Muse go as separate
 * `POST /chat/stream` requests on the same session.
 */

const val NOISE_PATH = "/v1/noise"
const val CONTROL_PATH = "/link-control"
const val CHAT_PATH = "/chat/stream"
const val APP_ID = "musegadget"
const val REQUEST_TIMEOUT_MS = 60_000L
const val MAX_RESPONSE_BYTES = 1024 * 1024
const val HANDSHAKE_TIMEOUT_MS = 20_000L
const val MAX_CONCURRENT_INVOKES = 4
const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024
const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"

enum class Outcome {
    CLOSED,         // connection ended; reconnect normally
    AUTH_REJECTED,  // edge refused the VM bearer; re-fetch VMs
    FORBIDDEN,      // authenticated but not allowed right now
    UNPAIRED,       // the Muse removed this device
    STOPPED,
}

/** What this device tells the Muse about itself in `link.register`. */
class DeviceDescription(
    val nodeId: String,
    val displayName: String,
    val version: String,
    val commands: JSONObject,
    val platform: String = "linux",
    val deviceFamily: String = "homehub",
    val modelId: String = "linux",
) {
    fun registerParams(): JSONObject = JSONObject()
        .put("node_id", nodeId)
        .put("display_name", displayName)
        .put("platform", platform)
        .put("version", version)
        .put("device_family", deviceFamily)
        .put("model_id", modelId)
        .put("is_wakeup_supported", false)
        .put("commands_v2", commands)
}

fun encodeMessage(obj: JSONObject): ByteArray {
    val data = obj.toString().toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).put(data).array()
}

/** Splits the control stream into length-prefixed JSON messages; a message may span body chunks. */
class MessageDecoder {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray): List<JSONObject> {
        buf += data
        val messages = mutableListOf<JSONObject>()
        while (buf.size >= 4) {
            val length = ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (length > MAX_INBOUND_MESSAGE) throw IllegalArgumentException("inbound message too large: $length")
            if (buf.size < 4 + length) break
            val raw = buf.copyOfRange(4, 4 + length.toInt())
            buf = buf.copyOfRange(4 + length.toInt(), buf.size)
            if (raw.isEmpty()) continue // keepalive
            try {
                messages += JSONObject(String(raw, Charsets.UTF_8))
            } catch (e: JSONException) {
                // malformed control message: drop it
            }
        }
        return messages
    }
}

private const val URI_SAFE = "-_.!~*'()"

/** Percent-encodes like JavaScript's encodeURIComponent. */
private fun encodeUriComponent(s: String): String {
    val out = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in URI_SAFE) out.append(c) else out.append("%%%02X".format(b.toInt() and 0xFF))
    }
    return out.toString()
}

fun noiseUrl(noiseHost: String, vmId: String): String = "wss://$noiseHost$NOISE_PATH?vm_id=${encodeUriComponent(vmId)}"

/** The VM refused the WebSocket upgrade with [status]. */
class UpgradeRejected(val status: Int) : Exception("HTTP $status")

interface WsConnection {
    suspend fun send(data: ByteArray)

    /** Next binary message; throws when the connection closes. */
    suspend fun receive(): ByteArray

    suspend fun close()
}

fun interface WsConnector {
    /** Throws [UpgradeRejected] when the server answers the upgrade with 401 or 403. */
    suspend fun connect(url: String, headers: Map<String, String>): WsConnection
}

/** The answer to a request the device sent: status first, then body chunks until the stream ends. */
class ChatStream internal constructor() {
    internal val statusDeferred = CompletableDeferred<Int>()
    val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    suspend fun status(): Int = statusDeferred.await()

    internal fun onFrame(frame: DecryptedFrame) {
        when (val b = frame.body) {
            is FrameBody.Rst -> fail(java.io.IOException("stream reset: ${b.value.reason}"))
            is FrameBody.Response -> {
                statusDeferred.complete(b.value.status)
                push(b.value.body, b.value.endBody)
            }
            is FrameBody.Chunk -> push(b.value.data, b.value.endBody)
            is FrameBody.Request -> {}
        }
    }

    private fun push(data: ByteArray, end: Boolean) {
        if (data.isNotEmpty()) chunks.trySend(data)
        if (end) chunks.close()
    }

    internal fun fail(cause: Throwable) {
        statusDeferred.completeExceptionally(cause)
        chunks.close(cause)
    }
}

class LinkSession(
    private val noiseHost: String,
    private val vmId: String,
    private val vmAuthToken: String,
    private val device: DeviceDescription,
    private val runCommand: suspend (command: String, params: JSONObject, timeoutMs: Long?) -> JSONObject,
    private val connector: WsConnector,
) {
    private val url = noiseUrl(noiseHost, vmId)
    private val sendLock = Mutex()
    private val invokes = Semaphore(MAX_CONCURRENT_INVOKES)
    private val requests = ConcurrentHashMap<Long, ChatStream>()
    private lateinit var ws: WsConnection
    private lateinit var transport: NoiseTransport
    private var controlStreamId = 0L
    private var registerId = ""

    /** Set (monotonic nanoseconds) once the VM accepted `link.register`. */
    @Volatile var registeredAtNanos: Long? = null
        private set

    val isRegistered: Boolean get() = registeredAtNanos != null

    /** Runs the session until it ends or [stop] completes. */
    suspend fun run(stop: CompletableDeferred<Unit>): Outcome {
        ws = try {
            connector.connect(url, mapOf("Authorization" to "Bearer $vmAuthToken"))
        } catch (e: UpgradeRejected) {
            return if (e.status == 401) Outcome.AUTH_REJECTED else Outcome.FORBIDDEN
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            transport = withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake() }
            openControlStream()
            val reader = scope.async { readLoop(scope) }
            return select {
                reader.onAwait { it }
                stop.onAwait { reader.cancel(); Outcome.STOPPED }
            }
        } finally {
            scope.cancel()
            requests.values.forEach { it.fail(java.io.IOException("session ended")) }
            requests.clear()
            ws.close()
        }
    }

    private suspend fun handshake(): NoiseTransport {
        val initiator = NoiseXXInitiator()
        initiator.initialize()
        ws.send(initiator.writeMessage1())
        initiator.readMessage2(ws.receive())
        // The bearer already authenticated us at the upgrade; message 3 carries an empty payload.
        ws.send(initiator.writeMessage3())
        val (send, recv) = initiator.split()
        return NoiseTransport(send, recv)
    }

    private suspend fun openControlStream() {
        val encrypted = transport.startStreamRequest("POST", CONTROL_PATH)
        controlStreamId = encrypted.streamId
        sendFrames(encrypted.frames)
        registerId = UUID.randomUUID().toString()
        send(JSONObject().put("type", "req").put("id", registerId).put("method", "link.register").put("params", device.registerParams()))
    }

    suspend fun send(message: JSONObject) = sendFrames(transport.encryptBodyChunk(controlStreamId, encodeMessage(message)))

    private suspend fun sendFrames(frames: List<ByteArray>) = sendLock.withLock { frames.forEach { ws.send(it) } }

    /**
     * Posts a user message to the Muse as coming from this device and returns the open response
     * stream, so a caller can act on body chunks as they arrive. [sessionId] targets a side chat;
     * an id the Muse has not seen before starts a new one.
     */
    suspend fun openChat(message: String, sessionId: String? = null): ChatStream {
        val body = JSONObject().put("message", message).put("output_modality", "text").put("device_id", device.nodeId)
        if (!sessionId.isNullOrEmpty()) body.put("session_id", sessionId)
        val headers = listOf(
            Header("Content-Type", "application/json"),
            Header("x-request-id", UUID.randomUUID().toString()),
            Header("x-app-id", APP_ID),
        )
        val encrypted = transport.encryptHttpRequest("POST", CHAT_PATH, body.toString().toByteArray(Charsets.UTF_8), headers = headers)
        val stream = ChatStream()
        requests[encrypted.streamId] = stream
        try {
            sendFrames(encrypted.frames)
        } catch (e: Exception) {
            requests.remove(encrypted.streamId)
            throw e
        }
        return stream
    }

    /** Like [openChat], but waits for the whole response: `{ok, status, response}`. */
    suspend fun sendChat(message: String, sessionId: String? = null): JSONObject {
        val stream = openChat(message, sessionId)
        val (status, bytes) = withTimeout(REQUEST_TIMEOUT_MS) {
            val status = stream.status()
            val out = ByteArrayOutputStream()
            for (chunk in stream.chunks) {
                out.write(chunk)
                if (out.size() > MAX_RESPONSE_BYTES) throw IllegalStateException("response too large")
            }
            status to out.toByteArray()
        }
        val text = String(bytes, Charsets.UTF_8)
        val decoded: Any? = if (text.isEmpty()) JSONObject.NULL else try { JSONObject(text) } catch (e: JSONException) { text.take(2000) }
        return JSONObject().put("ok", status in 200..299).put("status", status).put("response", decoded)
    }

    private suspend fun readLoop(scope: CoroutineScope): Outcome {
        val decoder = MessageDecoder()
        while (true) {
            val raw = try {
                ws.receive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.CLOSED
            }
            val frame = transport.decryptFrame(raw) ?: continue
            if (frame.streamId != controlStreamId) {
                requests[frame.streamId]?.onFrame(frame)
                continue
            }
            val data: ByteArray
            val ended: Boolean
            when (val b = frame.body) {
                is FrameBody.Rst -> return Outcome.CLOSED
                is FrameBody.Response -> {
                    if (b.value.status >= 400) return if (b.value.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
                    data = b.value.body; ended = b.value.endBody
                }
                is FrameBody.Chunk -> { data = b.value.data; ended = b.value.endBody }
                is FrameBody.Request -> continue
            }
            for (message in decoder.feed(data)) handle(message, scope)?.let { return it }
            if (ended) return Outcome.CLOSED
        }
    }

    private fun handle(message: JSONObject, scope: CoroutineScope): Outcome? {
        if (message.optString("id") == registerId && !message.has("method")) {
            if (!message.has("error") || message.isNull("error")) registeredAtNanos = System.nanoTime()
            return null
        }
        val event = message.optString("event")
        if (event == "link.unpaired" || event == "node.unpaired") return Outcome.UNPAIRED
        if (message.optString("method") == "link.invoke") scope.launch { invoke(message) }
        return null
    }

    private suspend fun invoke(message: JSONObject) {
        val invokeId = message.optString("id")
        if (invokeId.isEmpty()) return
        val command = message.optString("command")
        val params = message.optJSONObject("params") ?: JSONObject()
        val timeoutMs = message.optLong("timeout_ms", 0L).takeIf { it > 0 }
        invokes.acquire()
        val result = try {
            withContext(Dispatchers.Default) { runCommand(command, params, timeoutMs) }
        } finally {
            invokes.release()
        }
        val reply = JSONObject().put("method", "link.result").put("id", invokeId)
        result.keys().forEach { reply.put(it, result.get(it)) }
        send(reply)
    }
}

/** `{"ok":false,"error":...}` for commands this device does not run (a watch runs none). */
fun unsupportedCommand(command: String): JSONObject = JSONObject().put("ok", false).put("error", "unsupported command: $command")
