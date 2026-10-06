package muse.gadget

import java.util.Base64
import muse.gadget.chat.*
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNoteTest {
    private fun pcm(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun reference(pcm: ByteArray): String =
        VoiceNote.HEAD + Base64.getEncoder().encodeToString(VoiceNote.wavHeader() + pcm) + VoiceNote.TAIL

    private fun streamed(pcm: ByteArray, piece: Int): String {
        val body = VoiceNoteBody()
        val out = StringBuilder()
        var i = 0
        while (i < pcm.size) {
            out.append(String(body.append(pcm.copyOfRange(i, minOf(i + piece, pcm.size)))))
            i += piece
        }
        out.append(String(body.finish()))
        return out.toString()
    }

    @Test
    fun wavHeaderMarksUnknownLength() {
        val h = VoiceNote.wavHeader(16000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVEfmt ", String(h, 8, 8))
        assertEquals("data", String(h, 36, 4))
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), (4..7).map { h[it].toInt() and 0xFF })
        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), (40..43).map { h[it].toInt() and 0xFF })
        assertEquals(16000, (24..27).sumOf { (h[it].toInt() and 0xFF) shl (8 * (it - 24)) })
        assertEquals(32000, (28..31).sumOf { (h[it].toInt() and 0xFF) shl (8 * (it - 28)) })
        assertEquals(listOf(1, 0, 1, 0, 2, 0, 16, 0), listOf(20, 21, 22, 23, 32, 33, 34, 35).map { h[it].toInt() })
    }

    @Test
    fun streamedBodyEqualsTheOneShotBodyForAnyChunking() {
        for (size in listOf(0, 1, 2, 3, 4, 100, 999, 4801)) {
            val data = pcm(size)
            val expected = reference(data)
            for (piece in listOf(1, 2, 3, 5, 48, 1536, 100000)) {
                assertEquals(expected, streamed(data, piece), "size=$size piece=$piece")
            }
            assertEquals(expected, String(voiceNoteBody(data)))
        }
    }

    @Test
    fun bodyIsValidJsonCarryingTheAudio() {
        val data = pcm(3200)
        val json = JSONObject(String(voiceNoteBody(data)))
        assertEquals("", json.getString("message"))
        assertEquals("text", json.getString("output_modality"))
        val item = json.getJSONArray("items").getJSONObject(0)
        assertEquals("file", item.getString("type"))
        assertEquals("audio/wav", item.getString("mime_type"))
        assertEquals("voice_note.wav", item.getString("filename"))
        assertContentEquals(VoiceNote.wavHeader() + data, Base64.getDecoder().decode(item.getString("data_base64")))
    }

    @Test
    fun firstChunkCarriesTheHeadAndLaterOnesDoNot() {
        val body = VoiceNoteBody()
        val first = String(body.append(pcm(3000)))
        val second = String(body.append(pcm(3000)))
        assertTrue(first.startsWith(VoiceNote.HEAD))
        assertFalse(second.contains("data_base64"))
        assertTrue(String(body.finish()).endsWith(VoiceNote.TAIL))
        assertEquals(6000L, body.pcmBytes)
        assertEquals(6000.0 / 32000, body.seconds)
    }
}

class ChatEventsTest {
    private val parser get() = ChatEventParser()
    private fun line(s: String) = JSONObject(s)

    @Test
    fun ndjsonDecoderHandlesPartialBlankAndBadLines() {
        val d = NdjsonDecoder()
        assertTrue(d.feed("""{"a":1""".toByteArray()).isEmpty())
        val out = d.feed("}\n\n{\"b\":2}\nnot json\n{\"c\":3}\n".toByteArray())
        assertEquals(listOf("a", "b", "c"), out.map { it.keys().next() })
    }

    @Test
    fun ndjsonDecoderSkipsOverlongLines() {
        val d = NdjsonDecoder(maxLine = 20)
        val out = d.feed(("""{"big":"${"x".repeat(100)}"}""" + "\n" + """{"ok":1}""" + "\n").toByteArray())
        assertEquals(1, out.size)
        assertEquals(1, out[0].getInt("ok"))
    }

    @Test
    fun parserIgnoresTheSubscriptionAckAndUnknownEvents() {
        assertNull(parser.parse(line("""{"type":"subscribed"}""")))
        assertNull(parser.parse(line("""{"type":"event","event":"something.else","payload":{}}""")))
    }

    @Test
    fun parserReadsTheMessageLifecycle() {
        val p = parser
        assertEquals(ChatEvent.MessageStart("m1", "u1"), p.parse(line("""{"type":"event","seq":1,"event":"delta.message_start","payload":{"message_id":"m1","reply_to_message_id":"u1"}}""")))
        assertEquals(ChatEvent.TextAppend("m1", null, "Hel"), p.parse(line("""{"type":"event","seq":2,"event":"delta.text_append","payload":{"message_id":"m1","text":"Hel"}}""")))
        assertEquals(ChatEvent.MessageDone("m1", null, "Hello"), p.parse(line("""{"type":"event","seq":3,"event":"delta.message_done","payload":{"message_id":"m1","display_text":"Hello"}}""")))
    }

    @Test
    fun parserFallsBackAcrossIdAndTextFields() {
        val p = parser
        assertEquals(ChatEvent.MessageStart("x", "pp"), p.parse(line("""{"type":"event","event":"delta.message_start","message_id":"x","payload":{"parent_message_id":"pp"}}""")))
        assertEquals(ChatEvent.MessageDone("y", null, "body"), p.parse(line("""{"type":"event","event":"message.assistant","payload":{"id":"y","content":"body"}}""")))
        assertNull(p.parse(line("""{"type":"event","event":"delta.text_append","payload":{"text":"no id"}}""")))
        assertNull(p.parse(line("""{"type":"event","event":"delta.text_append","payload":{"message_id":"m","text":""}}""")))
    }

    @Test
    fun assistantMessageNotYetReadyIsNotTheEnd() {
        val e = parser.parse(line("""{"type":"event","event":"message.assistant","payload":{"message_id":"m","display_text_ready":false,"content":"partial"}}"""))
        assertNull(e)
    }

    @Test
    fun parserDropsRepeatedSequenceNumbers() {
        val p = parser
        val l = """{"type":"event","seq":5,"event":"delta.message_start","payload":{"message_id":"m"}}"""
        assertNotNull(p.parse(line(l)))
        assertNull(p.parse(line(l)))
        assertNull(p.parse(line(l.replace("\"seq\":5", "\"seq\":4"))))
        assertNotNull(p.parse(line(l.replace("\"seq\":5", "\"seq\":6"))))
    }

    @Test
    fun busyStatusFollowsActivityCodeThenStatus() {
        val p = parser
        fun busy(payload: String) = (p.parse(line("""{"type":"event","event":"agent.status","payload":$payload}""")) as? ChatEvent.Busy)?.on
        assertEquals(true, busy("""{"activity_code":"thinking"}"""))
        assertEquals(false, busy("""{"activity_code":"idle"}"""))
        assertEquals(false, busy("""{"activity_code":"online"}"""))
        assertEquals(true, busy("""{"status":"running"}"""))
        assertEquals(false, busy("""{"status":"completed"}"""))
        assertNull(busy("""{}"""))
    }

    @Test
    fun chatAckIdsMayBeNestedUnderResult() {
        assertEquals(ChatAck("u1", "u0"), parseChatAck("""{"result":{"message_id":"u1","reply_to_message_id":"u0"}}"""))
        assertEquals(ChatAck("u1", null), parseChatAck("""{"message_id":"u1"}"""))
        assertEquals(ChatAck(null, null), parseChatAck("""{"accepted":true}"""))
        assertNull(parseChatAck("not json"))
    }

    // -- ReplyTracker --------------------------------------------------------------------

    private class Clock(var now: Long = 0) : () -> Long { override fun invoke() = now }

    private fun tracker(clock: Clock) = ReplyTracker(clock).also { it.begin() }

    @Test
    fun trackerFollowsOneRepliesDeltasAndSettlesAfterQuiet() {
        val c = Clock(); val t = tracker(c)
        t.onAck(ChatAck("u1", null))
        assertTrue(t.onEvent(ChatEvent.MessageStart("m1", "u1")).isEmpty())
        val d1 = t.onEvent(ChatEvent.TextAppend("m1", "u1", "Hello "))
        t.onEvent(ChatEvent.TextAppend("m1", "u1", "there."))
        assertEquals(listOf(ReplyUpdate.TextDelta(0, "m1", "Hello ")), d1)
        val done = t.onEvent(ChatEvent.MessageDone("m1", "u1", null))
        assertEquals(listOf<ReplyUpdate>(ReplyUpdate.MessageDone(0, "m1", "Hello there.")), done)
        assertTrue(t.allDone)
        c.now = 2_999; assertFalse(t.isSettled())
        c.now = 3_000; assertTrue(t.isSettled())
    }

    @Test
    fun aReplyToSomeoneElseIsRejectedOnceTheAckIsKnown() {
        val c = Clock(); val t = tracker(c)
        t.onAck(ChatAck("u1", null))
        assertTrue(t.onEvent(ChatEvent.TextAppend("other", "u99", "not ours")).isEmpty())
        assertTrue(t.onEvent(ChatEvent.TextAppend("other", null, "still not ours")).isEmpty()) // remembered as rejected
        assertEquals(0, t.messageCount)
        assertEquals(1, t.onEvent(ChatEvent.TextAppend("mine", "u1", "yes")).size)
    }

    @Test
    fun beforeTheAckAMessageIsAcceptedAndBound() {
        val c = Clock(); val t = tracker(c)
        assertEquals(1, t.onEvent(ChatEvent.TextAppend("m1", "whatever", "early")).size)
        assertEquals(1, t.messageCount)
    }

    @Test
    fun eventsOutsideATurnAreIgnored() {
        val t = ReplyTracker(Clock())
        assertTrue(t.onEvent(ChatEvent.TextAppend("m", null, "x")).isEmpty())
        t.begin(); t.end()
        assertTrue(t.onEvent(ChatEvent.TextAppend("m", null, "x")).isEmpty())
        assertFalse(t.isSettled())
    }

    @Test
    fun aBusyAgentKeepsTheTurnOpenForTwentySeconds() {
        val c = Clock(); val t = tracker(c)
        t.onEvent(ChatEvent.TextAppend("m1", null, "a"))
        t.onEvent(ChatEvent.MessageDone("m1", null, null))
        c.now = 1_000
        t.onEvent(ChatEvent.Busy(true))
        c.now = 20_999; assertFalse(t.isSettled())
        c.now = 21_000; assertTrue(t.isSettled())
    }

    @Test
    fun twoMessagesBothMustBeDone() {
        val c = Clock(); val t = tracker(c)
        t.onEvent(ChatEvent.TextAppend("m1", null, "one"))
        t.onEvent(ChatEvent.TextAppend("m2", null, "two"))
        t.onEvent(ChatEvent.MessageDone("m1", null, null))
        c.now = 10_000; assertFalse(t.isSettled())
        t.onEvent(ChatEvent.MessageDone("m2", null, null))
        c.now = 13_000; assertTrue(t.isSettled())
    }

    @Test
    fun doneTextFillsInWhenNoDeltasArrived() {
        val t = tracker(Clock())
        val u = t.onEvent(ChatEvent.MessageDone("m1", null, "all at once"))
        assertEquals(listOf<ReplyUpdate>(ReplyUpdate.MessageDone(0, "m1", "all at once")), u)
        assertEquals("all at once", t.text(0))
    }

    @Test
    fun aDuplicateDoneIsIgnored() {
        val t = tracker(Clock())
        t.onEvent(ChatEvent.MessageDone("m1", null, "x"))
        assertTrue(t.onEvent(ChatEvent.MessageDone("m1", null, "x")).isEmpty())
    }

    @Test
    fun atMostEightMessagesAreTracked() {
        val t = tracker(Clock())
        for (i in 1..10) t.onEvent(ChatEvent.TextAppend("m$i", null, "x"))
        assertEquals(ReplyTracker.MAX_MESSAGES, t.messageCount)
    }
}
