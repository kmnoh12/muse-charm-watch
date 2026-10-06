package muse.charm.watch

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import muse.charm.conversation.Speaker

/**
 * Speaks with the watch's own text-to-speech engine. If no engine or Korean voice is available, it
 * still paces itself at a reading speed, so the talking animation and turn timing stay right.
 */
class AndroidSpeaker(context: Context, private val locale: Locale = Locale.KOREAN) : Speaker {
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private val waiting = HashMap<String, (Boolean) -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ready.complete(false)
                return@TextToSpeech
            }
            val lang = engine.setLanguage(locale)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId, true)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId, false)

                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
            })
            ready.complete(lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED)
        }
    }

    private fun finish(id: String?, ok: Boolean) {
        val cb = synchronized(waiting) { waiting.remove(id) } ?: return
        cb(ok)
    }

    override suspend fun speak(sentence: String) {
        val engine = tts
        if (engine == null || !ready.await()) {
            delay(readingTimeMs(sentence))
            return
        }
        val id = UUID.randomUUID().toString()
        val ok = suspendCancellableCoroutine { cont ->
            synchronized(waiting) { waiting[id] = { cont.resume(it) } }
            cont.invokeOnCancellation {
                synchronized(waiting) { waiting.remove(id) }
                engine.stop()
            }
            if (engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                synchronized(waiting) { waiting.remove(id) }
                cont.resume(false)
            }
        }
        if (!ok) delay(readingTimeMs(sentence).coerceAtMost(300)) // keep the pace if the engine refused
    }

    override fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private fun readingTimeMs(sentence: String): Long = (sentence.length * 110L).coerceIn(600, 6000)
}
