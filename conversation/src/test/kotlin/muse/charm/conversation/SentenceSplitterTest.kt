package muse.charm.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentenceSplitterTest {
    private fun run(vararg chunks: String): List<String> {
        val s = SentenceSplitter()
        val out = mutableListOf<String>()
        chunks.forEach { out += s.push(it) }
        s.flush()?.let { out += it }
        return out
    }

    @Test
    fun cutsAtTerminatorsFollowedBySpace() {
        assertEquals(listOf("안녕하세요.", "만나서 반가워요!", "잘 지냈어요?"), run("안녕하세요. 만나서 반가워요! 잘 지냈어요?"))
    }

    @Test
    fun aSentenceIsReleasedAsSoonAsTheNextOneBegins() {
        val s = SentenceSplitter()
        assertEquals(emptyList(), s.push("첫 번째 문장입니다"))
        assertEquals(emptyList(), s.push("."))
        assertEquals(listOf("첫 번째 문장입니다."), s.push(" 두"))
        assertEquals("두", s.flush())
    }

    @Test
    fun theLastSentenceWaitsForFlush() {
        val s = SentenceSplitter()
        assertEquals(emptyList(), s.push("끝나는 문장입니다."))
        assertEquals("끝나는 문장입니다.", s.flush())
        assertNull(s.flush())
    }

    @Test
    fun decimalsAreNotCut() {
        assertEquals(listOf("원주율은 3.14 정도예요."), run("원주율은 3.", "14 정도예요."))
    }

    @Test
    fun chunkBoundariesDoNotMatter() {
        val text = "하나. 둘! 셋? 넷입니다."
        val whole = run(text)
        assertEquals(listOf("하나.", "둘!", "셋?", "넷입니다."), whole)
        for (n in 1..6) assertEquals(whole, run(*text.chunked(n).toTypedArray()), "chunk size $n")
    }

    @Test
    fun newlinesEndSentences() {
        assertEquals(listOf("첫 줄", "둘째 줄"), run("첫 줄\n둘째 줄"))
    }

    @Test
    fun fullWidthTerminatorsNeedNoSpace() {
        assertEquals(listOf("こんにちは。", "元気ですか？"), run("こんにちは。元気ですか？"))
    }

    @Test
    fun closingQuotesStayWithTheirSentence() {
        assertEquals(listOf("그가 \"안녕.\"", "다음 말"), run("그가 \"안녕.\" 다음 말"))
    }

    @Test
    fun aLongRunOnIsCutAtASpaceOrComma() {
        val words = (1..40).joinToString(" ") { "단어$it" }
        val s = SentenceSplitter(maxLength = 60)
        val out = s.push(words)
        assert(out.isNotEmpty()) { "expected an early cut" }
        assert(out.all { it.length <= 60 }) { out.toString() }
        assertEquals(words, (out + listOfNotNull(s.flush())).joinToString(" "))
    }

    @Test
    fun speechTextDropsMarkdownLinksAndBullets() {
        assertEquals("중요한 내용이에요", SpeechText.clean("**중요한** 내용이에요"))
        assertEquals("여기를 보세요", SpeechText.clean("[여기를](https://example.com/x) 보세요"))
        assertEquals("주소는 입니다", SpeechText.clean("주소는 https://example.com/abc 입니다"))
        assertEquals("사과 배", SpeechText.clean("- 사과\n- 배"))
        assertEquals("코드 test 끝", SpeechText.clean("코드 `test` 끝"))
        assertEquals("", SpeechText.clean("***"))
    }
}
