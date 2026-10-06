package muse.charm.conversation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import muse.gadget.LinkSession
import muse.gadget.chat.ChatAck
import muse.gadget.chat.ChatEvent
import muse.gadget.chat.ChatEventParser
import muse.gadget.chat.parseChatAck

/** The real [MuseChannel]: voice notes and reply events on an established Muse [LinkSession]. */
class LinkMuseChannel(private val session: LinkSession) : MuseChannel {
    override val events: Flow<ChatEvent> = flow {
        val parser = ChatEventParser()
        session.subscribe().collect { line -> parser.parse(line)?.let { emit(it) } }
    }

    override suspend fun openVoiceNote(): VoiceNoteUpload {
        val stream = session.openStreamRequest("POST", "/chat/stream")
        return object : VoiceNoteUpload {
            override suspend fun write(data: ByteArray, last: Boolean) = stream.write(data, last)

            override suspend fun ack(): ChatAck? {
                stream.response.status()
                val body = java.io.ByteArrayOutputStream()
                for (chunk in stream.response.chunks) body.write(chunk)
                return parseChatAck(String(body.toByteArray(), Charsets.UTF_8))
            }

            override suspend fun cancel() = stream.cancel()
        }
    }
}
