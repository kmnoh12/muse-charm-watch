package muse.charm.conversation

import kotlinx.coroutines.flow.Flow
import muse.gadget.chat.ChatAck
import muse.gadget.chat.ChatEvent

/** Captures the microphone as 16 kHz mono PCM16. */
interface Recorder {
    /**
     * Records until the calling coroutine is cancelled, passing each piece to [sink] in order.
     * Implementations release the microphone when cancelled.
     */
    suspend fun record(sink: suspend (ByteArray) -> Unit)
}

/** Speaks text with the device's own engine. */
interface Speaker {
    /** Speaks [sentence] and returns when it has finished (or was stopped). */
    suspend fun speak(sentence: String)

    /** Cuts off anything being spoken. */
    fun stop()
}

/** One voice note being uploaded. */
interface VoiceNoteUpload {
    suspend fun write(data: ByteArray, last: Boolean)

    /** The server's acknowledgement carrying the user message id, or null if there was none. */
    suspend fun ack(): ChatAck?

    suspend fun cancel()
}

/** The Muse side of a turn: where voice notes go and where replies come from. */
interface MuseChannel {
    /** Reply events, live. Collected once for the whole session. */
    val events: Flow<ChatEvent>

    suspend fun openVoiceNote(): VoiceNoteUpload
}
