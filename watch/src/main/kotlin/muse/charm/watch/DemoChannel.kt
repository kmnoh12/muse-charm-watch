package muse.charm.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import muse.charm.conversation.MuseChannel
import muse.charm.conversation.VoiceNoteUpload
import muse.gadget.chat.ChatAck
import muse.gadget.chat.ChatEvent

/**
 * A stand-in for Muse so the whole voice loop can be tried without a server or pairing: it accepts a
 * voice note and streams a canned reply back in small pieces, the way the real subscription would.
 */
class DemoChannel(private val scope: CoroutineScope) : MuseChannel {
    private val flow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256)
    override val events: Flow<ChatEvent> = flow.asSharedFlow()
    private var turn = 0

    private val replies = listOf(
        "안녕하세요! 저는 뮤즈 참이에요. 지금은 시연 모드라서 서버 없이 대답하고 있어요. 곧 진짜 뮤즈랑 연결될 거예요.",
        "잘 들었어요. 다시 한 번 말해볼래요? 말하는 동안 화면을 누르고 있으면 돼요.",
        "좋아요! 이 대답은 문장 단위로 끊어서 읽고 있어요. 그래서 끝까지 기다리지 않아도 바로 말이 나와요.",
    )

    override suspend fun openVoiceNote(): VoiceNoteUpload {
        val id = "demo-user-${++turn}"
        val reply = replies[(turn - 1) % replies.size]
        return object : VoiceNoteUpload {
            override suspend fun write(data: ByteArray, last: Boolean) {
                if (last) scope.launch { streamReply(id, reply) }
            }

            override suspend fun ack(): ChatAck = ChatAck(id, null)

            override suspend fun cancel() {}
        }
    }

    private suspend fun streamReply(userId: String, text: String) {
        val msg = "demo-reply-$turn"
        delay(900) // the "thinking" pause
        flow.emit(ChatEvent.MessageStart(msg, userId))
        for (piece in text.chunked(7)) {
            flow.emit(ChatEvent.TextAppend(msg, userId, piece))
            delay(110)
        }
        flow.emit(ChatEvent.MessageDone(msg, userId, null))
    }
}
