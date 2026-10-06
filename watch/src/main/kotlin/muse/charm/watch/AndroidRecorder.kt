package muse.charm.watch

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import muse.charm.conversation.Recorder

/** The microphone as 16 kHz mono PCM16 in 100 ms pieces. Needs the RECORD_AUDIO permission. */
class AndroidRecorder : Recorder {
    @SuppressLint("MissingPermission") // checked before the first press
    override suspend fun record(sink: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val rate = 16_000
        val chunk = rate / 10 * 2 // 100 ms
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("microphone unavailable")
        }
        rec.startRecording()
        try {
            val buf = ByteArray(chunk)
            while (isActive) {
                val n = rec.read(buf, 0, chunk)
                if (n < 0) throw IOException("microphone read failed ($n)")
                if (n > 0) sink(buf.copyOf(n))
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }
}
