package muse.charm.conversation

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import muse.gadget.LinkSession
import muse.gadget.chat.ChatEvent

/**
 * A [MuseChannel] that follows the connection service across reconnects: reply events are subscribed
 * on whichever [LinkSession] is current (once it has registered), and voice notes go out on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceMuseChannel(private val sessions: StateFlow<LinkSession?>) : MuseChannel {
    override val events: Flow<ChatEvent> = sessions.flatMapLatest { session ->
        if (session == null) {
            emptyFlow()
        } else {
            flow {
                session.registered.first { it }
                emitAll(LinkMuseChannel(session).events)
            }
        }
    }

    override suspend fun openVoiceNote(): VoiceNoteUpload {
        val session = sessions.value?.takeIf { it.isRegistered } ?: throw IOException("not connected to Muse")
        return LinkMuseChannel(session).openVoiceNote()
    }
}

/** Lets the app switch between channels (the demo and the real one) without restarting the controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableChannel(initial: MuseChannel) : MuseChannel {
    private val current = MutableStateFlow(initial)

    override val events: Flow<ChatEvent> = current.flatMapLatest { it.events }

    fun switchTo(channel: MuseChannel) {
        current.value = channel
    }

    override suspend fun openVoiceNote(): VoiceNoteUpload = current.value.openVoiceNote()
}
