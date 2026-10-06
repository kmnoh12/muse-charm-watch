package muse.charm.conversation

/**
 * Cuts streamed reply text into sentences as soon as each one is complete, so speech can start
 * while the rest is still arriving. A terminator ends a sentence only when it is followed by
 * something that shows the sentence is over (space, newline, closing quote), so "3.5" and "e.g."
 * at the end of a chunk are not cut. [push] never returns the unfinished tail; [flush] does.
 *
 * Text with no terminator for [maxLength] characters is cut at the last comma or space, so a long
 * run-on does not delay speech.
 */
class SentenceSplitter(private val maxLength: Int = 120) {
    private val buf = StringBuilder()

    fun push(text: String): List<String> {
        buf.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val cut = findBoundary() ?: break
            addIfSpeakable(out, buf.substring(0, cut))
            buf.delete(0, cut)
        }
        return out
    }

    /** The remaining text, if any; call when the message is done. */
    fun flush(): String? {
        val rest = buf.toString().trim()
        buf.setLength(0)
        return rest.takeIf { it.isNotEmpty() }
    }

    fun reset() = buf.setLength(0)

    private fun addIfSpeakable(out: MutableList<String>, raw: String) {
        val s = raw.trim()
        if (s.isNotEmpty()) out += s
    }

    /** Index just past the end of the first complete sentence, or null. */
    private fun findBoundary(): Int? {
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '\n') return i + 1
            if (c in ".!?…。！？") {
                var end = i + 1
                while (end < buf.length && buf[end] in ".!?…。！？\"'”’)」』*_`~") end++
                if (end >= buf.length) return null // may continue ("3." then "5"); wait for more
                val next = buf[end]
                val decimal = c == '.' && i > 0 && buf[i - 1].isDigit() && next.isDigit()
                if (!decimal && (next.isWhitespace() || c in "。！？")) return end
                i = end
                continue
            }
            i++
        }
        if (buf.length >= maxLength) {
            val window = buf.substring(0, maxLength)
            val at = maxOf(window.lastIndexOf(','), window.lastIndexOf('，'), window.lastIndexOf(' '))
            return if (at > maxLength / 3) at + 1 else maxLength
        }
        return null
    }
}

/** Makes reply text fit for a speech engine: no markdown markers, links or list bullets. */
object SpeechText {
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val marks = Regex("""[*_`#>~|]+""")
    private val bullet = Regex("""^\s*([-•]|\d+[.)])\s+""")
    private val spaces = Regex("""\s+""")

    fun clean(sentence: String): String =
        sentence.replace(link, "$1").replace(url, "").lines().joinToString(" ") { it.replace(bullet, "") }
            .replace(marks, "").replace(spaces, " ").trim()
}
