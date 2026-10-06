package muse.gadget.chat

import org.json.JSONObject

/**
 * Reply events from the long-lived `POST /chat/subscribe` stream and the tracker that picks out the
 * replies to this device's turn. Behavior follows Meta's ESP32 reference
 * (components/muse/muse_chat_session.cpp, muse_chat_link.c).
 */

sealed class ChatEvent {
    /** The agent started or stopped working (`agent.status` / `task.status`). */
    data class Busy(val on: Boolean) : ChatEvent()

    data class MessageStart(val id: String, val parentId: String?) : ChatEvent()

    data class TextAppend(val id: String, val parentId: String?, val text: String) : ChatEvent()

    /** [text] is the whole message when the server sends it (`display_text`, else `content`). */
    data class MessageDone(val id: String, val parentId: String?, val text: String?) : ChatEvent()
}

/** One chat/stream acknowledgement: the user message's ids, which later replies point back to. */
data class ChatAck(val messageId: String?, val replyToMessageId: String?)

/** Reads the ack body of `POST /chat/stream`; ids may sit under `result`. */
fun parseChatAck(body: String): ChatAck? {
    val root = try { JSONObject(body) } catch (e: org.json.JSONException) { return null }
    val obj = root.optJSONObject("result") ?: root
    return ChatAck(
        obj.optString("message_id").ifEmpty { null },
        obj.optString("reply_to_message_id").ifEmpty { null },
    )
}

/** Splits an NDJSON stream into JSON lines; a line longer than [maxLine] is skipped. */
class NdjsonDecoder(private val maxLine: Int = 12 * 1024) {
    private var buf = java.io.ByteArrayOutputStream()
    private var skipping = false

    fun feed(data: ByteArray): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (b in data) {
            if (b == '\n'.code.toByte()) {
                if (!skipping && buf.size() > 0) parse(buf.toByteArray())?.let { out += it }
                buf = java.io.ByteArrayOutputStream()
                skipping = false
            } else if (!skipping) {
                if (buf.size() >= maxLine) {
                    skipping = true
                    buf = java.io.ByteArrayOutputStream()
                } else {
                    buf.write(b.toInt())
                }
            }
        }
        return out
    }

    private fun parse(line: ByteArray): JSONObject? =
        try { JSONObject(String(line, Charsets.UTF_8)) } catch (e: org.json.JSONException) { null }
}

/** Turns subscription lines into [ChatEvent]s, dropping repeats by `seq` and anything that is not an event. */
class ChatEventParser {
    private var lastSeq = 0L

    fun parse(line: JSONObject): ChatEvent? {
        if (line.optString("type") != "event") return null // the subscription ack
        if (line.has("seq")) {
            val seq = line.optLong("seq", 0L)
            if (seq in 1..lastSeq) return null
            if (seq > lastSeq) lastSeq = seq
        }
        val name = line.optString("event")
        val payload = line.optJSONObject("payload") ?: JSONObject()

        if (name == "agent.status" || name == "task.status") {
            val code = payload.opt("activity_code") as? String
            val status = payload.opt("status") as? String
            return when {
                code != null -> ChatEvent.Busy(code.isNotEmpty() && code != "online" && code != "idle")
                status != null -> ChatEvent.Busy(status.isNotEmpty() && status != "completed" && status != "failed")
                else -> null
            }
        }
        val start = name == "delta.message_start"
        val append = name == "delta.text_append"
        val done = name == "delta.message_done"
        val full = name == "message.assistant"
        if (!start && !append && !done && !full) return null

        val id = listOf(payload.optString("message_id"), line.optString("message_id"), payload.optString("id")).firstOrNull { it.isNotEmpty() } ?: return null
        val parent = (payload.optString("reply_to_message_id").ifEmpty { payload.optString("parent_message_id") }).ifEmpty { null }
        return when {
            start -> ChatEvent.MessageStart(id, parent)
            append -> payload.optString("text").takeIf { it.isNotEmpty() }?.let { ChatEvent.TextAppend(id, parent, it) }
            else -> {
                // A `message.assistant` that says its display text is not ready yet is not the end of the message.
                if (full && payload.opt("display_text_ready") == false) return null
                val text = (payload.opt("display_text") as? String) ?: (payload.opt("content") as? String)
                ChatEvent.MessageDone(id, parent, text)
            }
        }
    }
}

sealed class ReplyUpdate {
    abstract val index: Int

    data class TextDelta(override val index: Int, val id: String, val text: String) : ReplyUpdate()

    /** [fullText] is everything said in this message; use it if the deltas did not add up (a line skipped). */
    data class MessageDone(override val index: Int, val id: String, val fullText: String) : ReplyUpdate()
}

/**
 * Follows one turn's reply. After [begin], events are accepted only for messages that answer this
 * turn: once the ack names the user's message, a reply to anything else belongs to someone else.
 * "A turn has no explicit end event; it settles once every message is done and nothing has arrived
 * for a few seconds" ([isSettled]).
 */
class ReplyTracker(private val nowMs: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_MESSAGES = 8
        const val SETTLE_MS = 3_000L
        const val BUSY_HOLD_MS = 20_000L
        private const val MAX_REJECTED = 64
    }

    private class Msg(val id: String) {
        val text = StringBuilder()
        var done = false
    }

    private var active = false
    private var acked = false
    private val userIds = mutableSetOf<String>()
    private val messages = mutableListOf<Msg>()
    private val rejected = linkedSetOf<String>()
    private var rejectedOverflow = false
    private var lastEventAt = 0L
    private var lastContentAt = 0L
    private var agentBusy = false

    fun begin() {
        active = true
        acked = false
        userIds.clear()
        messages.clear()
        rejected.clear()
        rejectedOverflow = false
        agentBusy = false
        lastEventAt = nowMs()
        lastContentAt = lastEventAt
    }

    fun end() { active = false }

    fun onAck(ack: ChatAck) {
        ack.messageId?.let { userIds += it }
        ack.replyToMessageId?.let { userIds += it }
        acked = true
    }

    val messageCount: Int get() = messages.size
    val allDone: Boolean get() = messages.isNotEmpty() && messages.all { it.done }
    fun text(index: Int): String = messages[index].text.toString()

    fun onEvent(event: ChatEvent): List<ReplyUpdate> {
        if (!active) return emptyList()
        if (event is ChatEvent.Busy) {
            agentBusy = event.on
            lastEventAt = nowMs()
            return emptyList()
        }
        val (id, parent) = when (event) {
            is ChatEvent.MessageStart -> event.id to event.parentId
            is ChatEvent.TextAppend -> event.id to event.parentId
            is ChatEvent.MessageDone -> event.id to event.parentId
            is ChatEvent.Busy -> return emptyList()
        }
        val index = bind(id, parent)
        if (index < 0) return emptyList()
        lastEventAt = nowMs()
        lastContentAt = lastEventAt
        val msg = messages[index]
        return when (event) {
            is ChatEvent.TextAppend -> {
                msg.text.append(event.text)
                listOf(ReplyUpdate.TextDelta(index, id, event.text))
            }
            is ChatEvent.MessageDone -> {
                if (msg.done) return emptyList()
                msg.done = true
                if (msg.text.isEmpty() && !event.text.isNullOrEmpty()) msg.text.append(event.text)
                listOf(ReplyUpdate.MessageDone(index, id, event.text?.takeIf { it.isNotEmpty() } ?: msg.text.toString()))
            }
            else -> emptyList()
        }
    }

    private fun reject(id: String) {
        if (rejected.size >= MAX_REJECTED) rejectedOverflow = true else rejected += id
    }

    /** The message index for [id] if it belongs to this turn (binding it on first sight), else -1. */
    private fun bind(id: String, parent: String?): Int {
        if (id in rejected) return -1
        val known = messages.indexOfFirst { it.id == id }
        if (known >= 0) return known
        if (!parent.isNullOrEmpty() && acked && parent !in userIds && messages.none { it.id == parent }) {
            reject(id)
            return -1
        }
        if (parent.isNullOrEmpty() && rejectedOverflow) return -1
        if (messages.size == MAX_MESSAGES) return -1
        messages += Msg(id)
        return messages.size - 1
    }

    fun isSettled(): Boolean {
        if (!active || !allDone) return false
        val now = nowMs()
        if (agentBusy && now - lastEventAt < BUSY_HOLD_MS) return false
        return now - lastEventAt >= SETTLE_MS
    }
}
