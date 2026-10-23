package com.example.clusterstatemachine.cluster

import com.example.clusterstatemachine.protocol.AuditPair
import com.example.clusterstatemachine.protocol.AuditPairCodec
import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterStatus
import com.example.clusterstatemachine.protocol.CounterWireCodec
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.ExpandableArrayBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.agrona.concurrent.UnsafeBuffer
import org.springframework.stereotype.Component
import java.nio.ByteOrder
import java.util.UUID

@Component
open class CounterClusteredService : ClusteredService {
    var sourceId: UUID = UUID(0, 0)
        internal set
    var captureStartPosition: Long = 0L
        internal set
    private var cluster: Cluster? = null
    private var count = 0L
    private var idleStrategy: IdleStrategy? = null

    override fun onStart(
        cluster: Cluster,
        snapshotImage: Image?,
    ) {
        this.cluster = cluster
        idleStrategy = cluster.idleStrategy()
        if (snapshotImage != null) {
            var snapshotLoaded = false
            val handler =
                FragmentHandler { buffer, offset, length, _ ->
                    require(length >= Long.SIZE_BYTES) { "Counter snapshot is truncated" }
                    count = buffer.getLong(offset, ByteOrder.LITTLE_ENDIAN)
                    snapshotLoaded = true
                }

            while (!snapshotImage.isEndOfStream) {
                val fragments = snapshotImage.poll(handler, 1)
                if (fragments == 0) idleStrategy?.idle(0) else idleStrategy?.reset()
            }
            check(snapshotLoaded) { "Counter snapshot contains no state" }
        }
    }

    override fun onSessionMessage(
        session: ClientSession?,
        timestamp: Long,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
    ) {
        // Only service-generated messages have no client session and this envelope.
        // They are already ordered audit data, never a second arithmetic command.
        if (session == null && AuditPairCodec.isAudit(buffer, offset, length)) {
            AuditPairCodec.decode(buffer, offset, length)
            return
        }
        val request =
            try {
                CounterWireCodec.decodeRequest(buffer, offset, length)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        val status = if (request == null) CounterStatus.INVALID_COMMAND else apply(request.command, request.quantity ?: 1L)

        val response = ExpandableArrayBuffer(CounterWireCodec.RESULT_LENGTH)
        val responseLength =
            CounterWireCodec.encodeResult(
                response,
                0,
                CounterMessage(status, count),
                CounterWireCodec.isLegacyRequest(length),
            )
        val pair =
            AuditPair(
                sourceId,
                cluster!!.logPosition(),
                timestamp,
                session?.id() ?: -1L,
                ByteArray(length).also { buffer.getBytes(offset, it) },
                ByteArray(responseLength).also { response.getBytes(0, it) },
                if (cluster!!.logPosition() > captureStartPosition) 1 else 2,
            )
        val audit = AuditPairCodec.encode(pair)
        val auditBuffer = UnsafeBuffer(audit)
        val idle = idleStrategy!!
        // Cluster service-message tracking recovers this offer during log replay.
        // Never involve the gateway, external Archive or SQL in this callback.
        while (true) {
            val offered = cluster!!.offer(auditBuffer, 0, audit.size)
            if (offered >= 0) break
            check(
                offered != Publication.CLOSED && offered != Publication.MAX_POSITION_EXCEEDED,
            ) { "Cluster audit publication cannot progress" }
            idle.idle()
        }
        if (session != null) offerResponse(session, response, responseLength)
    }

    override fun onTakeSnapshot(snapshotPublication: ExclusivePublication) {
        val snapshot = ExpandableArrayBuffer(Long.SIZE_BYTES)
        snapshot.putLong(0, count, ByteOrder.LITTLE_ENDIAN)
        val idle = idleStrategy ?: return
        idle.reset()
        while (snapshotPublication.offer(snapshot, 0, Long.SIZE_BYTES) < 0) {
            idle.idle()
        }
    }

    override fun onSessionOpen(
        session: ClientSession,
        timestamp: Long,
    ) = Unit

    override fun onSessionClose(
        session: ClientSession,
        timestamp: Long,
        closeReason: CloseReason,
    ) = Unit

    override fun onTimerEvent(
        correlationId: Long,
        timestamp: Long,
    ) = Unit

    override fun onRoleChange(newRole: Cluster.Role) = Unit

    override fun onTerminate(cluster: Cluster) = Unit

    private fun apply(
        command: CounterCommand,
        quantity: Long,
    ): CounterStatus {
        if (command == CounterCommand.DIVIDE && quantity == 0L) return CounterStatus.DIVISION_BY_ZERO
        return try {
            val next =
                when (command) {
                    CounterCommand.GET -> {
                        count
                    }

                    CounterCommand.INCREMENT, CounterCommand.ADD -> {
                        Math.addExact(count, quantity)
                    }

                    CounterCommand.DECREMENT, CounterCommand.SUBTRACT -> {
                        Math.subtractExact(count, quantity)
                    }

                    CounterCommand.MULTIPLY -> {
                        Math.multiplyExact(count, quantity)
                    }

                    CounterCommand.DIVIDE -> {
                        if (count == Long.MIN_VALUE && quantity == -1L) throw ArithmeticException("Counter overflow")
                        count / quantity
                    }
                }
            count = next
            CounterStatus.OK
        } catch (_: ArithmeticException) {
            CounterStatus.OVERFLOW
        }
    }

    private fun offerResponse(
        session: ClientSession,
        response: MutableDirectBuffer,
        length: Int,
    ) {
        val idle = idleStrategy ?: return
        idle.reset()
        while (true) {
            val offered = session.offer(response, 0, length)
            if (offered >= 0 || session.isClosing || offered == Publication.CLOSED || offered == Publication.NOT_CONNECTED) return
            check(offered != Publication.MAX_POSITION_EXCEEDED) { "Client publication exhausted" }
            idle.idle()
        }
    }
}
