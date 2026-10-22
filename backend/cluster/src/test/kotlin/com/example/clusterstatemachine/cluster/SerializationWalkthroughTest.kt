package com.example.clusterstatemachine.cluster

import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterStatus
import com.example.clusterstatemachine.protocol.CounterWireCodec
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.ExpandableArrayBuffer
import org.agrona.concurrent.NoOpIdleStrategy
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A debugger-friendly tour: real generated codecs and service, mocked transport only. */
class SerializationWalkthroughTest {
    @Test
    fun `walk through commands bytes state and results`() {
        val service = CounterClusteredService()
        val cluster = mock(Cluster::class.java)
        val session = mock(ClientSession::class.java)
        `when`(cluster.idleStrategy()).thenReturn(NoOpIdleStrategy.INSTANCE)
        var received: CounterMessage? = null
        `when`(session.offer(any(DirectBuffer::class.java), anyInt(), anyInt())).thenAnswer { invocation ->
            // TOUR 5: inspect the generated response before it becomes an owned result.
            val replyBuffer = invocation.getArgument<DirectBuffer>(0)
            val replyOffset = invocation.getArgument<Int>(1)
            val replyLength = invocation.getArgument<Int>(2)
            assertEquals(CounterWireCodec.RESULT_LENGTH, replyLength)
            received = CounterWireCodec.decodeResult(replyBuffer, replyOffset, replyLength)
            1L
        }
        service.onStart(cluster, null)
        val steps =
            listOf(
                CounterRequest(CounterCommand.ADD, 20) to 20L,
                CounterRequest(CounterCommand.MULTIPLY, 3) to 60L,
                CounterRequest(CounterCommand.SUBTRACT, 10) to 50L,
                CounterRequest(CounterCommand.DIVIDE, 4) to 12L,
                CounterRequest(CounterCommand.DIVIDE, 0) to 12L,
            )
        for ((request, expectedCount) in steps) {
            // TOUR 1: compare the operation/quantity with its resulting byte representation.
            val commandBuffer = ExpandableArrayBuffer(64)
            val commandOffset = 5
            val commandLength = CounterWireCodec.encodeRequest(commandBuffer, commandOffset, request)
            assertEquals(17, commandLength)
            received = null
            // TOUR 3: step into the real service callback and its private apply() method.
            service.onSessionMessage(session, 0, commandBuffer, commandOffset, commandLength, mock(Header::class.java))
            // TOUR 6: the result is owned data; browser broadcasting happens outside this harness.
            val result = assertNotNull(received)
            val expectedStatus = if (request.quantity == 0L) CounterStatus.DIVISION_BY_ZERO else CounterStatus.OK
            assertEquals(CounterMessage(expectedStatus, expectedCount), result)
            println("${request.command} ${request.quantity}: $commandLength request bytes -> ${result.count} (${result.status})")
        }
    }
}
