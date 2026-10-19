package com.example.clusterstatemachine.cluster

import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterStatus
import org.agrona.DirectBuffer
import org.agrona.ExpandableArrayBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.springframework.stereotype.Component
import java.nio.ByteOrder

@Component
class CounterClusteredService : ClusteredService {
  private var count = 0L
  private var idleStrategy: IdleStrategy? = null

  override fun onStart(cluster: Cluster, snapshotImage: Image?) {
    idleStrategy = cluster.idleStrategy()
    if (snapshotImage != null) {
      var snapshotLoaded = false
      val handler = FragmentHandler { buffer, offset, length, _ ->
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
    var status = CounterStatus.OK
    if (length != COMMAND_LENGTH) {
      status = CounterStatus.INVALID_COMMAND
    } else {
      when (buffer.getByte(offset)) {
        CounterCommand.GET.code -> Unit
        CounterCommand.INCREMENT.code -> if (count == Long.MAX_VALUE) status = CounterStatus.OVERFLOW else count++
        CounterCommand.DECREMENT.code -> if (count == Long.MIN_VALUE) status = CounterStatus.OVERFLOW else count--
        else -> status = CounterStatus.INVALID_COMMAND
      }
    }

    if (session != null) {
      val response = ExpandableArrayBuffer(RESPONSE_LENGTH)
      response.putByte(0, status.code)
      response.putLong(1, count, ByteOrder.LITTLE_ENDIAN)
      offerResponse(session, response)
    }
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

  override fun onSessionOpen(session: ClientSession, timestamp: Long) = Unit

  override fun onSessionClose(session: ClientSession, timestamp: Long, closeReason: CloseReason) = Unit

  override fun onTimerEvent(correlationId: Long, timestamp: Long) = Unit

  override fun onRoleChange(newRole: Cluster.Role) = Unit

  override fun onTerminate(cluster: Cluster) = Unit

  private fun offerResponse(session: ClientSession, response: MutableDirectBuffer) {
    val idle = idleStrategy ?: return
    idle.reset()
    while (session.offer(response, 0, RESPONSE_LENGTH) < 0) {
      idle.idle()
    }
  }

  private companion object {
    const val COMMAND_LENGTH = 1
    const val RESPONSE_LENGTH = 1 + Long.SIZE_BYTES
  }
}
