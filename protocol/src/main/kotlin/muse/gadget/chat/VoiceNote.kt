package muse.gadget.chat

import java.io.ByteArrayOutputStream

/**
 * A voice note is a `POST /chat/stream` JSON body: [HEAD], a base64 WAV, [TAIL]. The server
 * transcribes it, so the watch needs no speech recognition of its own. Layout from Meta's ESP32
 * reference (components/muse/muse_chat_priv.h, muse_chat_text.c).
 *
 * The note is streamed while it is recorded, so its length is unknown until the end: the WAV
 * header carries the streaming "unknown size" value and the server reads to the end of the data.
 */
object VoiceNote {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    /** Muse stops a note at 15 s (the firmware caps recording at 20 s). */
    const val MAX_SECONDS = 15

    /** Shorter notes are dropped as "didn't catch that". */
    const val MIN_SECONDS = 0.3

    const val HEAD = """{"message":"","output_modality":"text","items":[{"type":"file","mime_type":"audio/wav","filename":"voice_note.wav","data_base64":""""
    const val TAIL = """"}]}"""
    const val WAV_HEADER_BYTES = 44

    /** 44-byte PCM16 mono WAV header with unknown (0xFFFFFFFF) sizes. */
    fun wavHeader(rate: Int = SAMPLE_RATE): ByteArray {
        val h = ByteArray(WAV_HEADER_BYTES)
        fun put(offset: Int, value: Long, bytes: Int) {
            for (i in 0 until bytes) h[offset + i] = (value ushr (8 * i)).toByte()
        }
        "RIFF".toByteArray().copyInto(h, 0)
        put(4, 0xFFFFFFFFL, 4)
        "WAVEfmt ".toByteArray().copyInto(h, 8)
        put(16, 16, 4)
        put(20, 1, 2) // PCM
        put(22, 1, 2) // mono
        put(24, rate.toLong(), 4)
        put(28, rate * 2L, 4)
        put(32, 2, 2)
        put(34, 16, 2)
        "data".toByteArray().copyInto(h, 36)
        put(40, 0xFFFFFFFFL, 4)
        return h
    }
}

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Builds the note's request body piece by piece. Feed PCM16 as it is recorded; each [append] returns
 * the next bytes to send (the first one includes [VoiceNote.HEAD] and the WAV header), and [finish]
 * returns the last bytes (base64 padding and [VoiceNote.TAIL]).
 */
class VoiceNoteBody(rate: Int = VoiceNote.SAMPLE_RATE) {
    private var pending = VoiceNote.wavHeader(rate) // bytes not yet base64-encoded (always < 3 after encode)
    private var started = false
    private var finished = false
    var pcmBytes = 0L
        private set

    /** Bytes of the JSON body produced so far. */
    var bodyBytes = 0L
        private set

    private fun encodeFull(): String {
        val usable = pending.size / 3 * 3
        val out = StringBuilder(usable / 3 * 4)
        var i = 0
        while (i < usable) {
            val v = (pending[i].toInt() and 0xFF shl 16) or (pending[i + 1].toInt() and 0xFF shl 8) or (pending[i + 2].toInt() and 0xFF)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63]).append(B64[v ushr 6 and 63]).append(B64[v and 63])
            i += 3
        }
        pending = pending.copyOfRange(usable, pending.size)
        return out.toString()
    }

    private fun emit(text: String): ByteArray {
        val body = if (started) text else { started = true; VoiceNote.HEAD + text }
        return body.toByteArray(Charsets.US_ASCII).also { bodyBytes += it.size }
    }

    fun append(pcm: ByteArray): ByteArray {
        check(!finished) { "note already finished" }
        pcmBytes += pcm.size
        pending += pcm
        return emit(encodeFull())
    }

    fun finish(): ByteArray {
        check(!finished) { "note already finished" }
        finished = true
        val head = encodeFull()
        val out = StringBuilder(head)
        if (pending.isNotEmpty()) {
            val a = pending[0].toInt() and 0xFF
            val b = if (pending.size > 1) pending[1].toInt() and 0xFF else 0
            val v = (a shl 16) or (b shl 8)
            out.append(B64[v ushr 18]).append(B64[v ushr 12 and 63])
            out.append(if (pending.size > 1) B64[v ushr 6 and 63] else '=').append('=')
            pending = ByteArray(0)
        }
        out.append(VoiceNote.TAIL)
        return emit(out.toString())
    }

    val seconds: Double get() = pcmBytes.toDouble() / VoiceNote.BYTES_PER_SECOND
}

/** Convenience for tests and one-shot sends: the whole body for [pcm]. */
fun voiceNoteBody(pcm: ByteArray, rate: Int = VoiceNote.SAMPLE_RATE): ByteArray {
    val body = VoiceNoteBody(rate)
    val out = ByteArrayOutputStream()
    out.write(body.append(pcm))
    out.write(body.finish())
    return out.toByteArray()
}
