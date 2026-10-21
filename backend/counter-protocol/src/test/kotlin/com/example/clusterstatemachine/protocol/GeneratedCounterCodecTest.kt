package com.example.clusterstatemachine.protocol

import com.example.clusterstatemachine.messages.AddDecoder
import com.example.clusterstatemachine.messages.AddEncoder
import com.example.clusterstatemachine.messages.CounterResultDecoder
import com.example.clusterstatemachine.messages.CounterResultEncoder
import com.example.clusterstatemachine.messages.DivideDecoder
import com.example.clusterstatemachine.messages.DivideEncoder
import com.example.clusterstatemachine.messages.MultiplyDecoder
import com.example.clusterstatemachine.messages.MultiplyEncoder
import com.example.clusterstatemachine.messages.SubtractDecoder
import com.example.clusterstatemachine.messages.SubtractEncoder
import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratedCounterCodecTest {
    @Test
    fun `all operations match independent binary layouts at nonzero offsets`() {
        for (quantity in listOf(Long.MIN_VALUE, -10L, 0L, 3L, Long.MAX_VALUE)) {
            for (template in 101..104) {
                val buffer = UnsafeBuffer(ByteArray(32))
                when (template) {
                    101 -> AddEncoder().wrapAndApplyHeader(buffer, 3).quantity(quantity)
                    102 -> SubtractEncoder().wrapAndApplyHeader(buffer, 3).quantity(quantity)
                    103 -> MultiplyEncoder().wrapAndApplyHeader(buffer, 3).quantity(quantity)
                    104 -> DivideEncoder().wrapAndApplyHeader(buffer, 3).quantity(quantity)
                }
                val expected =
                    ByteBuffer
                        .allocate(17)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(9)
                        .putShort(template.toShort())
                        .putShort(2)
                        .putShort(0)
                        .put(if (quantity == Long.MIN_VALUE) 1.toByte() else 0.toByte())
                        .putLong(if (quantity == Long.MIN_VALUE) 0 else quantity)
                        .array()
                assertContentEquals(expected, buffer.byteArray().copyOfRange(3, 20))
                val actual =
                    when (template) {
                        101 -> AddDecoder().wrapAndApplyHeader(buffer, 3, 17).quantity()
                        102 -> SubtractDecoder().wrapAndApplyHeader(buffer, 3, 17).quantity()
                        103 -> MultiplyDecoder().wrapAndApplyHeader(buffer, 3, 17).quantity()
                        else -> DivideDecoder().wrapAndApplyHeader(buffer, 3, 17).quantity()
                    }
                assertEquals(quantity, actual)
            }
        }
    }

    @Test
    fun `results preserve full range and reject truncated transport messages`() {
        val buffer = UnsafeBuffer(ByteArray(40))
        CounterResultEncoder().wrapAndApplyHeader(buffer, 5).status(2).count(Long.MIN_VALUE)
        val expected =
            ByteBuffer
                .allocate(25)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(17)
                .putShort(105)
                .putShort(2)
                .putShort(0)
                .putLong(2)
                .put(1)
                .putLong(0)
                .array()
        assertContentEquals(expected, buffer.byteArray().copyOfRange(5, 30))
        assertEquals(Long.MIN_VALUE, CounterResultDecoder().wrapAndApplyHeader(buffer, 5, 25).count())
        for (length in 0 until 25) {
            assertFailsWith<IllegalArgumentException> { CounterResultDecoder().wrapAndApplyHeader(buffer, 5, length) }
        }
    }
}
