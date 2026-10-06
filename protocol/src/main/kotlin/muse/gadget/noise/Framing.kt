package muse.gadget.noise

import java.security.SecureRandom

/** Chunking of encrypted payloads into Noise transport frames (port of noise/framing.py). */

const val MAX_CHUNK_PAYLOAD = 65489
const val MAX_PENDING_ASSEMBLIES = 16
const val MAX_TOTAL_CHUNKS = 256
const val MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024
const val ASSEMBLY_TTL_SECONDS = 60

class NoiseTransportFrame(
    val chunkId: Long = 0,
    val chunkIndex: Long = 0,
    val totalChunks: Long = 1,
    val payload: ByteArray = ByteArray(0),
)

fun encodeNoiseFrame(frame: NoiseTransportFrame): ByteArray {
    var out = ByteArray(0)
    if (frame.chunkId != 0L) out += int64Field(1, frame.chunkId)
    if (frame.chunkIndex != 0L) out += uint32Field(2, frame.chunkIndex)
    if (frame.totalChunks != 0L) out += uint32Field(3, frame.totalChunks)
    if (frame.payload.isNotEmpty()) out += bytesField(4, frame.payload)
    return out
}

fun decodeNoiseFrame(data: ByteArray): NoiseTransportFrame {
    var chunkId = 0L
    var chunkIndex = 0L
    var totalChunks = 1L
    var payload = ByteArray(0)
    val r = ProtoReader(data)
    while (r.hasMore()) {
        val (field, wire) = r.readKey()
        when (field) {
            1 -> { if (wire != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.chunk_id wrong wire type"); chunkId = decodeInt64(r.readVarint()) }
            2 -> { if (wire != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.chunk_index wrong wire type"); chunkIndex = decodeUint32(r.readVarint()) }
            3 -> { if (wire != WIRE_VARINT) throw ProtoError("NoiseTransportFrame.total_chunks wrong wire type"); totalChunks = decodeUint32(r.readVarint()) }
            4 -> { if (wire != WIRE_DELIMITED) throw ProtoError("NoiseTransportFrame.payload wrong wire type"); payload = r.readDelimited() }
            else -> r.skip(wire)
        }
    }
    return NoiseTransportFrame(chunkId, chunkIndex, totalChunks, payload)
}

private val secureRandom = SecureRandom()

/** Splits [data] into encoded frames. [chunkId] is random unless given (tests pin it). */
fun encodeNoiseFrames(data: ByteArray, chunkId: Long? = null): List<ByteArray> {
    val id = chunkId ?: secureRandom.nextLong()
    val total = maxOf(1, (data.size + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD)
    if (total > MAX_TOTAL_CHUNKS) {
        throw IllegalArgumentException("payload too large for noise framing (${data.size} bytes, $total chunks > $MAX_TOTAL_CHUNKS)")
    }
    if (data.isEmpty()) {
        return listOf(encodeNoiseFrame(NoiseTransportFrame(id, 0, 1, ByteArray(0))))
    }
    return (0 until total).map { index ->
        val start = index * MAX_CHUNK_PAYLOAD
        val chunk = data.copyOfRange(start, minOf(start + MAX_CHUNK_PAYLOAD, data.size))
        encodeNoiseFrame(NoiseTransportFrame(id, index.toLong(), total.toLong(), chunk))
    }
}

private class Assembly(val total: Int, val createdAt: Long) {
    val chunks = HashMap<Int, ByteArray>()
    var totalBytes = 0
}

/** Reassembles chunked frames. Any malformed frame poisons the decoder, as in the reference. */
class NoiseFrameDecoder(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val pending = HashMap<Long, Assembly>()
    private var poisoned = false

    fun decode(frameBytes: ByteArray): ByteArray? {
        if (poisoned) throw IllegalStateException("NoiseFrameDecoder: poisoned after prior failure")
        try {
            val frame = decodeNoiseFrame(frameBytes)
            if (frame.totalChunks < 1 || frame.totalChunks > MAX_TOTAL_CHUNKS) {
                throw IllegalArgumentException("invalid totalChunks: ${frame.totalChunks}")
            }
            if (frame.chunkIndex < 0 || frame.chunkIndex >= frame.totalChunks) {
                throw IllegalArgumentException("chunkIndex ${frame.chunkIndex} out of range [0, ${frame.totalChunks})")
            }
            if (frame.payload.size > MAX_CHUNK_PAYLOAD) {
                throw IllegalArgumentException("payload too large for noise frame (${frame.payload.size} bytes)")
            }
            evictExpired()

            var assembly = pending[frame.chunkId]
            if (assembly == null) {
                if (pending.size >= MAX_PENDING_ASSEMBLIES) {
                    throw IllegalArgumentException("too many pending noise frame assemblies")
                }
                assembly = Assembly(frame.totalChunks.toInt(), nowMillis())
                pending[frame.chunkId] = assembly
            }
            if (assembly.total.toLong() != frame.totalChunks) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("inconsistent totalChunks for chunkId: expected ${assembly.total}, got ${frame.totalChunks}")
            }
            val index = frame.chunkIndex.toInt()
            if (assembly.chunks.containsKey(index)) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("duplicate chunkIndex $index")
            }
            assembly.totalBytes += frame.payload.size
            if (assembly.totalBytes > MAX_ASSEMBLY_BYTES) {
                pending.remove(frame.chunkId)
                throw IllegalArgumentException("assembly exceeded byte budget")
            }
            assembly.chunks[index] = frame.payload
            if (assembly.chunks.size < assembly.total) return null

            pending.remove(frame.chunkId)
            if (assembly.total == 1) return assembly.chunks.getValue(0)
            val out = java.io.ByteArrayOutputStream(assembly.totalBytes)
            for (i in 0 until assembly.total) out.write(assembly.chunks.getValue(i))
            return out.toByteArray()
        } catch (e: Exception) {
            poisoned = true
            throw e
        }
    }

    private fun evictExpired() {
        val now = nowMillis()
        pending.entries.removeIf { now - it.value.createdAt > ASSEMBLY_TTL_SECONDS * 1000L }
    }
}
