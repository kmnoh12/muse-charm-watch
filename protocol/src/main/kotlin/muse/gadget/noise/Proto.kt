package muse.gadget.noise

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire helpers for the Hatch Noise envelopes (port of noise/_proto.py). */
class ProtoError(message: String) : IllegalArgumentException(message)

const val WIRE_VARINT = 0
const val WIRE_FIXED64 = 1
const val WIRE_DELIMITED = 2
const val WIRE_FIXED32 = 5

private const val MAX_FIELD_NUMBER = (1L shl 29) - 1
private const val FIRST_RESERVED_FIELD_NUMBER = 19000L
private const val LAST_RESERVED_FIELD_NUMBER = 19999L

private fun validFieldNumber(n: Long) =
    n != 0L && n <= MAX_FIELD_NUMBER && n !in FIRST_RESERVED_FIELD_NUMBER..LAST_RESERVED_FIELD_NUMBER

private fun validWireType(w: Int) = w == WIRE_VARINT || w == WIRE_FIXED64 || w == WIRE_DELIMITED || w == WIRE_FIXED32

/** Encodes the 64 raw bits of [value] as an unsigned varint. */
fun encodeVarint(value: Long): ByteArray {
    val out = ByteArrayOutputStream(10)
    var v = value
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) {
            out.write(b or 0x80)
        } else {
            out.write(b)
            return out.toByteArray()
        }
    }
}

private fun encodeKey(field: Int, wire: Int): ByteArray {
    if (!validFieldNumber(field.toLong())) throw ProtoError("invalid field number")
    if (!validWireType(wire)) throw ProtoError("invalid wire type")
    return encodeVarint(((field.toLong()) shl 3) or wire.toLong())
}

fun varintField(field: Int, value: Long): ByteArray = encodeKey(field, WIRE_VARINT) + encodeVarint(value)

/** int64 is sent as its two's-complement bits. */
fun int64Field(field: Int, value: Long): ByteArray = varintField(field, value)

/** int32 is sign-extended to 64 bits, like protobuf. */
fun int32Field(field: Int, value: Int): ByteArray = varintField(field, value.toLong())

fun uint32Field(field: Int, value: Long): ByteArray {
    if (value < 0 || value > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
    return varintField(field, value)
}

fun boolField(field: Int, value: Boolean): ByteArray = varintField(field, if (value) 1 else 0)

fun delimitedField(field: Int, payload: ByteArray): ByteArray =
    encodeKey(field, WIRE_DELIMITED) + encodeVarint(payload.size.toLong()) + payload

fun stringField(field: Int, value: String): ByteArray = delimitedField(field, value.toByteArray(Charsets.UTF_8))

fun bytesField(field: Int, value: ByteArray): ByteArray = delimitedField(field, value)

fun decodeInt64(raw: Long): Long = raw

fun decodeInt32(raw: Long): Int {
    if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) throw ProtoError("int32 value out of range")
    return raw.toInt()
}

fun decodeUint32(raw: Long): Long {
    if (raw ushr 32 != 0L) throw ProtoError("uint32 value out of range")
    return raw
}

/** Cursor over a protobuf message. */
class ProtoReader(private val data: ByteArray) {
    var pos = 0
        private set

    fun hasMore(): Boolean = pos < data.size

    fun readVarint(): Long {
        var value = 0L
        var shift = 0
        for (i in 0 until 10) {
            if (pos >= data.size) throw ProtoError("truncated varint")
            val b = data[pos++].toInt() and 0xFF
            if (i == 9 && (b and 0xFE) != 0) throw ProtoError("malformed varint")
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
        throw ProtoError("malformed varint")
    }

    /** Returns (fieldNumber, wireType). */
    fun readKey(): Pair<Int, Int> {
        val key = readVarint()
        val field = key ushr 3
        val wire = (key and 0x07).toInt()
        if (field < 0 || field > Int.MAX_VALUE || !validFieldNumber(field)) throw ProtoError("invalid field number")
        if (!validWireType(wire)) throw ProtoError("invalid wire type")
        return field.toInt() to wire
    }

    fun readDelimited(): ByteArray {
        val length = readVarint()
        if (length < 0 || length > data.size - pos) throw ProtoError("truncated delimited field")
        val out = data.copyOfRange(pos, pos + length.toInt())
        pos += length.toInt()
        return out
    }

    fun skip(wire: Int) {
        when (wire) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> advance(8, "fixed64")
            WIRE_DELIMITED -> readDelimited()
            WIRE_FIXED32 -> advance(4, "fixed32")
            else -> throw ProtoError("invalid wire type")
        }
    }

    private fun advance(n: Int, what: String) {
        if (pos + n > data.size) throw ProtoError("truncated $what field")
        pos += n
    }
}
