package com.example.clusterstatemachine.cluster

import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterWireCodec
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.ExpandableArrayBuffer
import org.agrona.concurrent.NoOpIdleStrategy
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CounterClusteredServiceTest {
    private val service = CounterClusteredService()
    private val cluster = mock(Cluster::class.java)
    private val session = mock(ClientSession::class.java)
    private var responseStatus: Byte = -1
    private var responseCount = 0L

    init {
        `when`(cluster.idleStrategy()).thenReturn(NoOpIdleStrategy.INSTANCE)
        `when`(session.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenAnswer {
            val buffer = it.getArgument<DirectBuffer>(0)
            val message = CounterWireCodec.decodeResult(buffer, it.getArgument(1), it.getArgument(2))
            responseStatus = message.status.code
            responseCount = message.count
            1L
        }
        service.onStart(cluster, null)
    }

    private fun command(vararg bytes: Byte) {
        val buffer = ExpandableArrayBuffer(16)
        bytes.forEachIndexed { index, byte -> buffer.putByte(index + 3, byte) }
        service.onSessionMessage(session, 0, buffer, 3, bytes.size, mock(Header::class.java))
    }

    private fun arithmetic(
        command: CounterCommand,
        quantity: Long,
        replay: Boolean = false,
    ) {
        val buffer = ExpandableArrayBuffer(16)
        buffer.putByte(3, command.code)
        buffer.putLong(4, quantity, ByteOrder.LITTLE_ENDIAN)
        service.onSessionMessage(if (replay) null else session, 0, buffer, 3, 9, mock(Header::class.java))
    }

    @Test
    fun `arithmetic commands apply quantities in order`() {
        arithmetic(CounterCommand.ADD, 20)
        assertEquals(20L, responseCount)
        arithmetic(CounterCommand.SUBTRACT, 10)
        assertEquals(10L, responseCount)
        arithmetic(CounterCommand.MULTIPLY, 3)
        assertEquals(30L, responseCount)
        arithmetic(CounterCommand.DIVIDE, 4)
        assertEquals(7L, responseCount)
        arithmetic(CounterCommand.MULTIPLY, -1)
        arithmetic(CounterCommand.DIVIDE, 2)
        assertEquals(-3L, responseCount)
        arithmetic(CounterCommand.SUBTRACT, -5)
        assertEquals(2L, responseCount)
        arithmetic(CounterCommand.ADD, -8)
        assertEquals(-6L, responseCount)
        arithmetic(CounterCommand.MULTIPLY, 0)
        assertEquals(0L, responseCount)
        assertEquals(0.toByte(), responseStatus)
    }

    @Test
    fun `arithmetic errors preserve state and allow later commands`() {
        for ((initial, operation, quantity) in listOf(
            Triple(Long.MAX_VALUE, CounterCommand.ADD, 1L),
            Triple(Long.MIN_VALUE, CounterCommand.ADD, -1L),
            Triple(Long.MIN_VALUE, CounterCommand.SUBTRACT, 1L),
            Triple(Long.MAX_VALUE, CounterCommand.SUBTRACT, -1L),
            Triple(Long.MAX_VALUE, CounterCommand.MULTIPLY, 2L),
            Triple(Long.MIN_VALUE, CounterCommand.MULTIPLY, -1L),
            Triple(Long.MIN_VALUE, CounterCommand.DIVIDE, -1L),
        )) {
            restore(initial)
            arithmetic(operation, quantity)
            assertEquals(initial, responseCount)
            assertEquals(2.toByte(), responseStatus)
        }
        restore(42)
        arithmetic(CounterCommand.DIVIDE, 0)
        assertEquals(42L, responseCount)
        assertEquals(3.toByte(), responseStatus)
        arithmetic(CounterCommand.ADD, 0)
        assertEquals(42L, responseCount)
        assertEquals(0.toByte(), responseStatus)
    }

    @Test
    fun `arithmetic payloads require exactly eight quantity bytes`() {
        command(1)
        for (operation in CounterCommand.entries.filter { it.requiresQuantity }) {
            command(operation.code)
            assertEquals(1.toByte(), responseStatus)
            command(operation.code, 1, 2, 3, 4, 5, 6, 7)
            assertEquals(1.toByte(), responseStatus)
            command(operation.code, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            assertEquals(1.toByte(), responseStatus)
            assertEquals(1L, responseCount)
        }
    }

    @Test
    fun `replayed arithmetic and restored state use the same transitions`() {
        restore(12)
        arithmetic(CounterCommand.MULTIPLY, 3, replay = true)
        arithmetic(CounterCommand.SUBTRACT, 10, replay = true)
        command(0)
        assertEquals(26L, responseCount)
    }

    @Test
    fun `generated and legacy commands share ordered state and full range`() {
        fun generated(
            command: CounterCommand,
            quantity: Long,
            replay: Boolean = false,
        ) {
            val buffer = ExpandableArrayBuffer(32)
            val length = CounterWireCodec.encodeRequest(buffer, 3, CounterRequest(command, quantity))
            service.onSessionMessage(if (replay) null else session, 0, buffer, 3, length, mock(Header::class.java))
        }
        generated(CounterCommand.ADD, 20)
        command(2)
        generated(CounterCommand.MULTIPLY, 3, replay = true)
        arithmetic(CounterCommand.SUBTRACT, 10, replay = true)
        generated(CounterCommand.DIVIDE, 4)
        assertEquals(11L, responseCount)
        generated(CounterCommand.MULTIPLY, 0)
        generated(CounterCommand.ADD, Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, responseCount)
        generated(CounterCommand.DIVIDE, -1)
        assertEquals(Long.MIN_VALUE, responseCount)
        assertEquals(2.toByte(), responseStatus)
        generated(CounterCommand.DIVIDE, 0)
        assertEquals(3.toByte(), responseStatus)
        generated(CounterCommand.SUBTRACT, Long.MIN_VALUE)
        assertEquals(0L, responseCount)
    }

    @Test
    fun `malformed generated commands cannot mutate or terminate the service`() {
        command(1)
        val buffer = ExpandableArrayBuffer(32)
        CounterWireCodec.encodeRequest(buffer, 3, CounterRequest(CounterCommand.ADD, 5))
        for (length in 0 until CounterWireCodec.ARITHMETIC_LENGTH) {
            service.onSessionMessage(session, 0, buffer, 3, length, mock(Header::class.java))
            assertEquals(1L, responseCount)
            assertEquals(1.toByte(), responseStatus)
        }
        buffer.putByte(11, 2)
        service.onSessionMessage(session, 0, buffer, 3, 17, mock(Header::class.java))
        assertEquals(1L, responseCount)
        assertEquals(1.toByte(), responseStatus)
        command(1)
        assertEquals(2L, responseCount)
    }

    private fun restore(count: Long) {
        val image = mock(Image::class.java)
        `when`(image.isEndOfStream).thenReturn(false, true)
        `when`(image.poll(any(FragmentHandler::class.java), anyInt())).thenAnswer {
            val buffer = ExpandableArrayBuffer(8)
            buffer.putLong(0, count, ByteOrder.LITTLE_ENDIAN)
            it.getArgument<FragmentHandler>(0).onFragment(buffer, 0, 8, mock(Header::class.java))
            1
        }
        service.onStart(cluster, image)
    }

    @Test
    fun `increment decrement and get preserve ordered state`() {
        command(1)
        command(1)
        command(2)
        command(0)
        assertEquals(1L, responseCount)
        assertEquals(0.toByte(), responseStatus)
    }

    @Test
    fun `invalid commands leave the count unchanged`() {
        command(1)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(99), byteArrayOf(1, 1))) {
            command(*bytes)
            assertEquals(1L, responseCount)
            assertEquals(1.toByte(), responseStatus)
        }
    }

    @Test
    fun `overflow at both long boundaries leaves count unchanged`() {
        restore(Long.MAX_VALUE)
        command(1)
        assertEquals(Long.MAX_VALUE, responseCount)
        assertEquals(2.toByte(), responseStatus)
        restore(Long.MIN_VALUE)
        command(2)
        assertEquals(Long.MIN_VALUE, responseCount)
        assertEquals(2.toByte(), responseStatus)
    }

    @Test
    fun `snapshot stores restored state and retries backpressure`() {
        restore(41)
        command(1)
        val publication = mock(ExclusivePublication::class.java)
        var attempts = 0
        `when`(publication.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenAnswer {
            assertEquals(42L, it.getArgument<DirectBuffer>(0).getLong(0, ByteOrder.LITTLE_ENDIAN))
            assertEquals(8, it.getArgument<Int>(2))
            if (++attempts == 1) -2L else 1L
        }
        service.onTakeSnapshot(publication)
        assertEquals(2, attempts)
    }

    @Test
    fun `empty snapshot is rejected`() {
        val image = mock(Image::class.java)
        `when`(image.isEndOfStream).thenReturn(true)
        assertFailsWith<IllegalStateException> { service.onStart(cluster, image) }
    }

    @Test
    fun `replayed command without a client session still changes state`() {
        val buffer = ExpandableArrayBuffer(1)
        buffer.putByte(0, 1)
        service.onSessionMessage(null, 0, buffer, 0, 1, mock(Header::class.java))
        command(0)
        assertEquals(1L, responseCount)
    }
}
