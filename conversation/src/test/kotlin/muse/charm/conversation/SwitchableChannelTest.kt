package muse.charm.conversation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import muse.gadget.chat.ChatEvent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableChannelTest {
    private class Source(val tag: String) : MuseChannel {
        val flow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
        override val events: Flow<ChatEvent> = flow.asSharedFlow()
        var opened = 0
        override suspend fun openVoiceNote(): VoiceNoteUpload { opened++; throw java.io.IOException(tag) }
    }

    @Test
    fun eventsFollowTheCurrentChannelAndNotesGoToIt() = runTest {
        val a = Source("a"); val b = Source("b")
        val channel = SwitchableChannel(a)
        val seen = mutableListOf<String>()
        backgroundScope.launch { channel.events.collect { seen += (it as ChatEvent.TextAppend).text } }
        runCurrent()

        a.flow.emit(ChatEvent.TextAppend("m", null, "from a")); runCurrent()
        channel.switchTo(b); runCurrent()
        a.flow.emit(ChatEvent.TextAppend("m", null, "late from a")); runCurrent()
        b.flow.emit(ChatEvent.TextAppend("m", null, "from b")); runCurrent()
        assertEquals(listOf("from a", "from b"), seen)

        runCatching { channel.openVoiceNote() }
        assertEquals(0 to 1, a.opened to b.opened)
    }
}
