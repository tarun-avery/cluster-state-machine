package com.example.clusterstatemachine.cluster

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
import org.mockito.Mockito.*
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
      responseStatus = buffer.getByte(0)
      responseCount = buffer.getLong(1, ByteOrder.LITTLE_ENDIAN)
      1L
    }
    service.onStart(cluster, null)
  }

  private fun command(vararg bytes: Byte) {
    val buffer = ExpandableArrayBuffer(16)
    bytes.forEachIndexed { index, byte -> buffer.putByte(index + 3, byte) }
    service.onSessionMessage(session, 0, buffer, 3, bytes.size, mock(Header::class.java))
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
    command(1); command(1); command(2); command(0)
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
