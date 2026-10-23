package com.example.clusterstatemachine.protocol

import org.agrona.concurrent.UnsafeBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditPairCodecTest {
    @Test
    fun `pair preserves exact bytes and signed metadata with bounds checked`() {
        val pair = AuditPair(UUID(123, 456), Long.MAX_VALUE, Long.MIN_VALUE, -1, byteArrayOf(1, 2), byteArrayOf(3, 4))
        val bytes = AuditPairCodec.encode(pair)
        val decoded = AuditPairCodec.decode(UnsafeBuffer(bytes), 0, bytes.size)
        assertEquals(pair.sourceId, decoded.sourceId)
        assertEquals(pair.commandPosition, decoded.commandPosition)
        assertEquals(pair.timestamp, decoded.timestamp)
        assertContentEquals(pair.request, decoded.request)
        assertContentEquals(pair.result, decoded.result)
        for (length in bytes.indices) {
            assertFailsWith<IllegalArgumentException> { AuditPairCodec.decode(UnsafeBuffer(bytes), 0, length) }
        }
        bytes[4] = 99
        assertFailsWith<IllegalArgumentException> { AuditPairCodec.decode(UnsafeBuffer(bytes), 0, bytes.size) }
    }

    @Test
    fun `previous audit version remains readable without asserting original capture`() {
        val bytes = AuditPairCodec.encode(AuditPair(UUID(0, 1), 7, 8, 9, byteArrayOf(1), byteArrayOf(2), 1))
        val legacy = bytes.copyOfRange(0, 56) + bytes.copyOfRange(60, bytes.size)
        legacy[4] = 1
        val decoded = AuditPairCodec.decode(UnsafeBuffer(legacy), 0, legacy.size)
        assertEquals(0, decoded.captureKind)
        assertContentEquals(byteArrayOf(1), decoded.request)
        assertContentEquals(byteArrayOf(2), decoded.result)
    }
}
