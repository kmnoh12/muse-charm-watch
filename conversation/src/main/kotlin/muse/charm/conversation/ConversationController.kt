package muse.charm.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import muse.gadget.chat.ReplyTracker
import muse.gadget.chat.ReplyUpdate
import muse.gadget.chat.VoiceNote
import muse.gadget.chat.VoiceNoteBody

/**
 * One push-to-talk conversation at a time: hold to record, release to send, then speak the reply
 * sentence by sentence as it streams in.
 *
 * ```
 *  IDLE --pressStart--> LISTENING --pressEnd--> THINKING <--> SPEAKING --settled--> IDLE
 * ```
 * Speech to text happens on the server (the note is a WAV upload), so there is no recognizer here.
 *
 * [scope] must run on a single thread (the main thread on Android); all methods are called from it.
 */
class ConversationController(
    private val scope: CoroutineScope,
    private val channel: MuseChannel,
    private val recorder: Recorder,
    private val speaker: Speaker,
    private val nowMs: () -> Long,
    private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS,
    private val turnCapMs: Long = TURN_CAP_MS,
) {
    companion object {
        const val REPLY_TIMEOUT_MS = 60_000L
        const val TURN_CAP_MS = 180_000L
        const val ACK_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 100L

        const val ERROR_CONNECT = "뮤즈에 연결할 수 없어요"
        const val ERROR_TOO_SHORT = "못 들었어요"
        const val ERROR_NO_REPLY = "응답이 없어요"
        const val ERROR_UPLOAD = "보내지 못했어요"
        const val ERROR_DISCONNECTED = "연결이 끊겼어요"
    }

    private val _state = MutableStateFlow(CharmState.IDLE)
    val state: StateFlow<CharmState> = _state.asStateFlow()

    /** A short message for the screen when a turn fails; cleared when the next turn starts. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The reply text so far, for captions. */
    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply.asStateFlow()

    private val tracker = ReplyTracker(nowMs)
    private val splitter = SentenceSplitter()
    private val deltaChars = HashMap<Int, Int>()

    private var eventsJob: Job? = null
    private var turnJob: Job? = null
    private var speechJob: Job? = null
    private var queue = Channel<String>(Channel.UNLIMITED)
    private var release = CompletableDeferred<Unit>()
    private var currentNote: VoiceNoteUpload? = null
    private var pending = 0 // sentences queued or being spoken
    private var turnActive = false
    private var turnId = 0 // bumped whenever a turn starts or is dropped, so stale callbacks can tell

    /** Starts listening for reply events. Call once, when the session is up. */
    fun start() {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            channel.events
                .onEach(::onEvent)
                .catch { _error.value = ERROR_DISCONNECTED }
                .collect {}
        }
    }

    /** The button went down: barge in on anything in progress and start recording. */
    fun pressStart() {
        if (_state.value == CharmState.LISTENING) return
        abortTurn()
        _error.value = null
        _state.value = CharmState.LISTENING
        turnActive = true
        val id = ++turnId
        turnJob = scope.launch { runTurn(id) }
    }

    /** The button went up: stop recording and send. */
    fun pressEnd() {
        if (_state.value == CharmState.LISTENING) release.complete(Unit)
    }

    /** Drops whatever is happening (a tap while thinking or speaking). */
    fun cancel() {
        abortTurn()
        _state.value = CharmState.IDLE
    }

    private fun abortTurn() {
        turnId++
        turnJob?.cancel(); turnJob = null
        speechJob?.cancel(); speechJob = null
        speaker.stop()
        currentNote?.let { note -> scope.launch(NonCancellable) { runCatching { note.cancel() } } }
        currentNote = null
        queue.close()
        splitter.reset(); deltaChars.clear(); pending = 0
        _reply.value = ""
        tracker.end()
        turnActive = false
    }

    private fun fail(message: String) {
        abortTurn()
        _error.value = message
        _state.value = CharmState.IDLE
    }

    private fun refreshState() {
        if (!turnActive || _state.value == CharmState.LISTENING) return
        _state.value = if (pending > 0) CharmState.SPEAKING else CharmState.THINKING
    }

    private suspend fun runTurn(id: Int) {
        val startedAt = nowMs()
        tracker.begin()
        queue = Channel(Channel.UNLIMITED)
        release = CompletableDeferred()
        val sentences = queue
        speechJob = scope.launch {
            for (sentence in sentences) {
                if (id == turnId) refreshState()
                try {
                    speaker.speak(sentence)
                } finally {
                    if (id == turnId) pending--
                }
                if (id == turnId) refreshState()
            }
        }

        val note = try {
            channel.openVoiceNote()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ERROR_CONNECT)
            return
        }
        currentNote = note

        val body = VoiceNoteBody()
        var uploadError: Exception? = null
        // The recorder is a child of this turn, so dropping the turn always releases the microphone.
        coroutineScope {
            val recording = launch {
                try {
                    recorder.record { pcm ->
                        if (body.seconds < VoiceNote.MAX_SECONDS) {
                            note.write(body.append(pcm), false)
                            if (body.seconds >= VoiceNote.MAX_SECONDS) release.complete(Unit)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uploadError = e
                    release.complete(Unit)
                }
            }
            release.await()
            recording.cancelAndJoin()
        }

        if (uploadError != null) { fail(ERROR_UPLOAD); return }
        if (body.seconds < VoiceNote.MIN_SECONDS) {
            fail(ERROR_TOO_SHORT)
            return
        }
        try {
            note.write(body.finish(), true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(ERROR_UPLOAD)
            return
        }
        _state.value = CharmState.THINKING
        val postedAt = nowMs()

        withTimeoutOrNull(ACK_TIMEOUT_MS) { runCatching { note.ack() }.getOrNull() }?.let(tracker::onAck)
        currentNote = null

        while (true) {
            delay(POLL_MS)
            val now = nowMs()
            if (tracker.messageCount == 0 && now - postedAt > replyTimeoutMs) { fail(ERROR_NO_REPLY); return }
            if (now - startedAt > turnCapMs) break
            if (tracker.isSettled() && pending == 0) break
        }
        speechJob?.cancel(); speechJob = null
        tracker.end()
        turnActive = false
        _state.value = CharmState.IDLE
    }

    // -- Reply events -------------------------------------------------------------------

    private fun onEvent(event: muse.gadget.chat.ChatEvent) {
        for (update in tracker.onEvent(event)) {
            when (update) {
                is ReplyUpdate.TextDelta -> {
                    deltaChars[update.index] = (deltaChars[update.index] ?: 0) + update.text.length
                    _reply.value = _reply.value + update.text
                    splitter.push(update.text).forEach(::enqueue)
                }
                is ReplyUpdate.MessageDone -> {
                    if ((deltaChars[update.index] ?: 0) == 0) {
                        _reply.value = _reply.value + update.fullText
                        splitter.push(update.fullText).forEach(::enqueue)
                    }
                    splitter.flush()?.let(::enqueue)
                    _reply.value = _reply.value + "\n"
                }
            }
        }
    }

    private fun enqueue(sentence: String) {
        val speakable = SpeechText.clean(sentence)
        if (speakable.isEmpty()) return
        pending++
        queue.trySend(speakable)
        refreshState()
    }
}
