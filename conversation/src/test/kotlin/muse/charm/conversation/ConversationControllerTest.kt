package muse.charm.conversation

import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import muse.gadget.chat.ChatAck
import muse.gadget.chat.ChatEvent
import muse.gadget.chat.VoiceNote
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeUpload(val ack: ChatAck? = ChatAck("u1", null), val failWrites: Boolean = false) : VoiceNoteUpload {
    val parts = mutableListOf<ByteArray>()
    var finished = false
    var cancelled = false
    override suspend fun write(data: ByteArray, last: Boolean) {
        if (failWrites) throw java.io.IOException("offline")
        parts += data
        if (last) finished = true
    }
    override suspend fun ack(): ChatAck? = ack
    override suspend fun cancel() { cancelled = true }
    fun body(): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }
}

private class FakeChannel : MuseChannel {
    private val flow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 64)
    override val events: Flow<ChatEvent> = flow.asSharedFlow()
    val uploads = mutableListOf<FakeUpload>()
    var next: () -> FakeUpload = { FakeUpload() }
    var openFails = false
    override suspend fun openVoiceNote(): VoiceNoteUpload {
        if (openFails) throw java.io.IOException("no route")
        return next().also { uploads += it }
    }
    suspend fun emit(e: ChatEvent) = flow.emit(e)
}

/** Delivers 100 ms of audio (3200 bytes) every 100 ms until cancelled. */
private class FakeRecorder : Recorder {
    var recording = false
    override suspend fun record(sink: suspend (ByteArray) -> Unit) {
        recording = true
        try {
            while (true) {
                delay(100)
                sink(ByteArray(3200) { 1 })
            }
        } finally {
            recording = false
        }
    }
}

private class FakeSpeaker(private val msPerSentence: Long = 500) : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    override suspend fun speak(sentence: String) {
        spoken += sentence
        delay(msPerSentence)
    }
    override fun stop() { stops++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationControllerTest {
    private class Rig(val scope: TestScope) {
        val channel = FakeChannel()
        val recorder = FakeRecorder()
        val speaker = FakeSpeaker()
        val controller = ConversationController(scope.backgroundScope, channel, recorder, speaker, nowMs = { scope.currentTime })
            .also { it.start() }
        val states = mutableListOf<CharmState>()
    }

    private fun TestScope.rig() = Rig(this)

    /** Presses, records for [ms], releases and lets the upload finish. */
    private suspend fun TestScope.talk(r: Rig, ms: Long) {
        r.controller.pressStart()
        runCurrent()
        advanceTimeBy(ms)
        r.controller.pressEnd()
        runCurrent()
    }

    private suspend fun TestScope.reply(r: Rig, parent: String = "u1", id: String = "m1", vararg pieces: String) {
        r.channel.emit(ChatEvent.MessageStart(id, parent))
        for (p in pieces) { r.channel.emit(ChatEvent.TextAppend(id, parent, p)); runCurrent(); advanceTimeBy(50) }
        r.channel.emit(ChatEvent.MessageDone(id, parent, null))
        runCurrent()
    }

    @Test
    fun aFullTurnRecordsUploadsAndSpeaksTheReplySentenceBySentence() = runTest {
        val r = rig()
        assertEquals(CharmState.IDLE, r.controller.state.value)

        r.controller.pressStart(); runCurrent()
        assertEquals(CharmState.LISTENING, r.controller.state.value)
        assertTrue(r.recorder.recording)
        advanceTimeBy(1_050)
        r.controller.pressEnd(); runCurrent()
        assertEquals(CharmState.THINKING, r.controller.state.value)
        assertFalse(r.recorder.recording)

        val upload = r.channel.uploads.single()
        assertTrue(upload.finished)
        val json = JSONObject(String(upload.body()))
        val audio = Base64.getDecoder().decode(json.getJSONArray("items").getJSONObject(0).getString("data_base64"))
        assertEquals(VoiceNote.WAV_HEADER_BYTES + 10 * 3200, audio.size)

        r.channel.emit(ChatEvent.MessageStart("m1", "u1"))
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "안녕하세요. 만나서 "))
        runCurrent()
        assertEquals(CharmState.SPEAKING, r.controller.state.value)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "반가워요."))
        r.channel.emit(ChatEvent.MessageDone("m1", "u1", null))
        runCurrent()

        advanceTimeBy(5_000)
        assertEquals(listOf("안녕하세요.", "만나서 반가워요."), r.speaker.spoken)
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertNull(r.controller.error.value)
        assertEquals("안녕하세요. 만나서 반가워요.\n", r.controller.reply.value)
    }

    @Test
    fun speechStartsBeforeTheReplyHasFinishedStreaming() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "첫 문장이에요. 아직 "))
        runCurrent()
        assertEquals(listOf("첫 문장이에요."), r.speaker.spoken)
        assertEquals(CharmState.SPEAKING, r.controller.state.value)
    }

    @Test
    fun stateReturnsToThinkingWhileWaitingForMoreOfTheReply() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "하나. 둘"))
        runCurrent()
        advanceTimeBy(600) // "하나." has been spoken, "둘" is still being typed
        assertEquals(CharmState.THINKING, r.controller.state.value)
    }

    @Test
    fun aTooShortPressSendsNothingAndSaysSo() = runTest {
        val r = rig()
        talk(r, 150)
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertEquals(ConversationController.ERROR_TOO_SHORT, r.controller.error.value)
        assertTrue(r.channel.uploads.single().cancelled)
        assertFalse(r.channel.uploads.single().finished)
    }

    @Test
    fun recordingStopsByItselfAtFifteenSeconds() = runTest {
        val r = rig()
        r.controller.pressStart(); runCurrent()
        advanceTimeBy(16_000)
        assertEquals(CharmState.THINKING, r.controller.state.value)
        assertFalse(r.recorder.recording)
        assertTrue(r.channel.uploads.single().finished)
        val audio = Base64.getDecoder().decode(JSONObject(String(r.channel.uploads.single().body())).getJSONArray("items").getJSONObject(0).getString("data_base64"))
        assertEquals(VoiceNote.WAV_HEADER_BYTES + 15 * 32000, audio.size)
    }

    @Test
    fun noReplyWithinAMinuteFailsTheTurn() = runTest {
        val r = rig()
        talk(r, 1_000)
        advanceTimeBy(59_000); assertEquals(CharmState.THINKING, r.controller.state.value)
        advanceTimeBy(2_000)
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertEquals(ConversationController.ERROR_NO_REPLY, r.controller.error.value)
    }

    @Test
    fun aRepliesToSomeoneElseAreNotSpoken() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("other", "someone-else", "남의 대화입니다."))
        r.channel.emit(ChatEvent.MessageDone("other", "someone-else", null))
        runCurrent()
        assertTrue(r.speaker.spoken.isEmpty())
        assertEquals(CharmState.THINKING, r.controller.state.value)
    }

    @Test
    fun pressingWhileSpeakingBargesIn() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "긴 대답을 하고 있어요. 계속 말해요. 더 말해요. "))
        runCurrent()
        assertEquals(CharmState.SPEAKING, r.controller.state.value)
        val stopsBefore = r.speaker.stops

        r.controller.pressStart(); runCurrent()
        assertEquals(CharmState.LISTENING, r.controller.state.value)
        assertTrue(r.speaker.stops > stopsBefore)
        assertEquals("", r.controller.reply.value)
        assertEquals(2, r.channel.uploads.size)
    }

    @Test
    fun cancelDropsTheTurnAndTheUpload() = runTest {
        val r = rig()
        r.controller.pressStart(); runCurrent()
        advanceTimeBy(500)
        r.controller.cancel(); runCurrent()
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertFalse(r.recorder.recording)
        assertTrue(r.channel.uploads.single().cancelled)
    }

    @Test
    fun cancelWhileSpeakingStopsTheSpeaker() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "말하는 중이에요. 이어서 "))
        runCurrent()
        val before = r.speaker.stops
        r.controller.cancel(); runCurrent()
        assertTrue(r.speaker.stops > before)
        assertEquals(CharmState.IDLE, r.controller.state.value)
    }

    @Test
    fun anOpenFailureIsReported() = runTest {
        val r = rig()
        r.channel.openFails = true
        r.controller.pressStart(); runCurrent()
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertEquals(ConversationController.ERROR_CONNECT, r.controller.error.value)
    }

    @Test
    fun anUploadFailureIsReported() = runTest {
        val r = rig()
        r.channel.next = { FakeUpload(failWrites = true) }
        r.controller.pressStart(); runCurrent()
        advanceTimeBy(500)
        assertEquals(CharmState.IDLE, r.controller.state.value)
        assertEquals(ConversationController.ERROR_UPLOAD, r.controller.error.value)
    }

    @Test
    fun markdownIsNotReadAloud() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.TextAppend("m1", "u1", "**중요해요.** [링크](https://x.y) 보세요. "))
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(listOf("중요해요.", "링크 보세요."), r.speaker.spoken)
    }

    @Test
    fun aMessageThatArrivesWholeIsStillSpoken() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.MessageDone("m1", "u1", "한 번에 왔어요. 두 문장이에요."))
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(listOf("한 번에 왔어요.", "두 문장이에요."), r.speaker.spoken)
    }

    @Test
    fun theNextTurnStartsCleanAfterTheFirst() = runTest {
        val r = rig()
        talk(r, 1_000)
        r.channel.emit(ChatEvent.MessageDone("m1", "u1", "첫 대답이에요."))
        runCurrent(); advanceTimeBy(6_000)
        assertEquals(CharmState.IDLE, r.controller.state.value)

        r.channel.next = { FakeUpload(ack = ChatAck("u2", null)) }
        talk(r, 1_000)
        r.channel.emit(ChatEvent.MessageDone("m2", "u2", "두 번째 대답이에요."))
        runCurrent(); advanceTimeBy(6_000)
        assertEquals(listOf("첫 대답이에요.", "두 번째 대답이에요."), r.speaker.spoken)
        assertEquals(CharmState.IDLE, r.controller.state.value)
    }
}
