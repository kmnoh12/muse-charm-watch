package muse.gadget

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleFramingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun rep(c: Char, n: Int) = ByteArray(n) { c.code.toByte() }

    @Test
    fun chunkPayloadFollowsMtu() {
        for ((mtu, perChunk) in listOf(23 to 17, 185 to 157, 256 to 157, 3 to 17)) {
            val chunks = BleFraming.encodeChunks(rep('x', 1000), mtu)
            assertTrue(chunks.dropLast(1).all { it.size - 3 == perChunk }, "mtu $mtu")
            assertContentEquals(rep('x', 1000), chunks.map { it.copyOfRange(3, it.size) }.reduce { a, b -> a + b })
        }
    }

    @Test
    fun chunkHeaders() {
        val chunks = BleFraming.encodeChunks(rep('a', 40), 23)
        assertEquals(listOf(listOf(0xFE, 0, 3), listOf(0xFE, 1, 3), listOf(0xFE, 2, 3)),
            chunks.map { c -> c.take(3).map { it.toInt() and 0xFF } })
    }

    @Test
    fun emptyMessageIsOneChunk() {
        assertContentEquals(bytes(0xFE, 0, 1), BleFraming.encodeChunks(ByteArray(0)).single())
    }

    @Test
    fun tooManyChunksIsAnError() {
        assertFailsWith<IllegalArgumentException> { BleFraming.encodeChunks(rep('x', 17 * 255 + 1), 23) }
    }

    @Test
    fun roundTrip() {
        val message = ByteArray(2560) { (it % 256).toByte() }
        val a = ChunkAssembler()
        val results = BleFraming.encodeChunks(message, 185).map { a.feed(it) }
        assertContentEquals(message, results.last())
        assertTrue(results.dropLast(1).all { it == null })
    }

    @Test
    fun plainWritePassesThrough() {
        val plain = """{"action":"get_device_info"}""".toByteArray()
        assertContentEquals(plain, ChunkAssembler().feed(plain))
    }

    @Test
    fun outOfOrderChunkDiscardsTheMessage() {
        val chunks = BleFraming.encodeChunks(rep('y', 60), 23)
        val a = ChunkAssembler()
        assertNull(a.feed(chunks[0])); assertNull(a.feed(chunks[2])); assertNull(a.feed(chunks[1])); assertNull(a.feed(chunks[3]))
    }

    @Test
    fun indexZeroRestartsTheMessage() {
        val old = BleFraming.encodeChunks(rep('o', 60), 23)
        val new = BleFraming.encodeChunks(rep('n', 30), 23)
        val a = ChunkAssembler()
        a.feed(old[0]); a.feed(old[1])
        assertContentEquals(rep('n', 30), new.map { a.feed(it) }.last())
    }

    @Test
    fun changedTotalMidMessageDiscardsIt() {
        val a = ChunkAssembler()
        a.feed(bytes(0xFE, 0, 3) + "abc".toByteArray())
        assertNull(a.feed(bytes(0xFE, 1, 2) + "def".toByteArray()))
        assertNull(a.feed(bytes(0xFE, 2, 3) + "ghi".toByteArray()))
    }

    @Test
    fun zeroTotalIsIgnored() {
        assertNull(ChunkAssembler().feed(bytes(0xFE, 0, 0) + "abc".toByteArray()))
    }

    @Test
    fun oversizeMessageIsDiscarded() {
        val a = ChunkAssembler(maxBytes = 20)
        assertEquals(listOf(null, null), BleFraming.encodeChunks(rep('z', 30), 23).map { a.feed(it) })
    }

    @Test
    fun identityNamesShareTheSameSuffix() {
        val id = Identity.generate { ByteArray(6) { (it + 1).toByte() } }
        assertEquals("02:02:03:04:05:06", id.mac)
        assertEquals("homelink-040506", id.nodeId)
        assertEquals("homelink-" + id.suffix, id.nodeId)
        assertEquals("MuseGadget" + id.suffix.uppercase(), id.bleName)
        assertTrue(Identity.isValidMac(id.mac))
    }
}
