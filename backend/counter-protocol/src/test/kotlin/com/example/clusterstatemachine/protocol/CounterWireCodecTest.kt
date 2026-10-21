package com.example.clusterstatemachine.protocol

import org.agrona.concurrent.UnsafeBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CounterWireCodecTest {
    @Test
    fun `adapter dispatches all generated templates and normalizes legacy buttons`() {
        val buffer = UnsafeBuffer(ByteArray(64))
        for (command in CounterCommand.entries) {
            val request = CounterRequest(command, if (command.requiresQuantity) Long.MIN_VALUE else null)
            val length = CounterWireCodec.encodeRequest(buffer, 5, request)
            val expected =
                when (command) {
                    CounterCommand.INCREMENT -> CounterRequest(CounterCommand.ADD, 1)
                    CounterCommand.DECREMENT -> CounterRequest(CounterCommand.SUBTRACT, 1)
                    else -> request
                }
            assertEquals(expected, CounterWireCodec.decodeRequest(buffer, 5, length))
        }
        for (command in CounterCommand.entries) {
            buffer.putByte(5, command.code)
            if (command.requiresQuantity) buffer.putLong(6, Long.MIN_VALUE, ByteOrder.LITTLE_ENDIAN)
            val length = if (command.requiresQuantity) 9 else 1
            assertEquals(
                CounterRequest(command, if (command.requiresQuantity) Long.MIN_VALUE else null),
                CounterWireCodec.decodeRequest(buffer, 5, length),
            )
        }
    }

    @Test
    fun `generated and legacy results preserve statuses and full range`() {
        val buffer = UnsafeBuffer(ByteArray(64))
        for (legacy in listOf(false, true)) {
            for (status in CounterStatus.entries) {
                val message = CounterMessage(status, Long.MIN_VALUE)
                val length = CounterWireCodec.encodeResult(buffer, 5, message, legacy)
                assertEquals(message, CounterWireCodec.decodeResult(buffer, 5, length))
            }
        }
        CounterWireCodec.encodeResult(buffer, 5, CounterMessage(CounterStatus.OK, 42))
        buffer.putLong(13, 256, ByteOrder.LITTLE_ENDIAN)
        assertEquals(CounterStatus.UNKNOWN, CounterWireCodec.decodeResult(buffer, 5, 25).status)
    }

    @Test
    fun `invalid header bounds template version and full range tag are rejected`() {
        val buffer = UnsafeBuffer(ByteArray(64))

        fun reset() = CounterWireCodec.encodeRequest(buffer, 5, CounterRequest(CounterCommand.MULTIPLY, 3))
        for ((offset, value) in listOf(5 to 8, 7 to 999, 9 to 3, 11 to 1)) {
            reset()
            buffer.putShort(offset, value.toShort(), ByteOrder.LITTLE_ENDIAN)
            assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeRequest(buffer, 5, 17) }
        }
        reset()
        buffer.putByte(13, 2)
        assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeRequest(buffer, 5, 17) }
        reset()
        for (length in 0 until 17) assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeRequest(buffer, 5, length) }
        assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeRequest(buffer, -1, 17) }
        assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeRequest(buffer, 50, 17) }
        assertFailsWith<IllegalArgumentException> { CounterWireCodec.decodeResult(buffer, 5, 17) }
    }
}
