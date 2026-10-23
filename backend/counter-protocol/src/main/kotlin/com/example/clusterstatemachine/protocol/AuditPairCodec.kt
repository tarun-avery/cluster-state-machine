package com.example.clusterstatemachine.protocol

import org.agrona.DirectBuffer
import org.agrona.ExpandableArrayBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Versioned wrapper around the unchanged Taxi/legacy request and result bytes. */
data class AuditPair(
    val sourceId: UUID,
    val commandPosition: Long,
    val timestamp: Long,
    val sessionId: Long,
    val request: ByteArray,
    val result: ByteArray,
)

object AuditPairCodec {
    private const val MAGIC = 0x41554454
    private const val VERSION = 1
    private const val HEADER_LENGTH = 56
    const val MAX_PAYLOAD_LENGTH = 65536
    private val order = ByteOrder.LITTLE_ENDIAN

    fun isAudit(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): Boolean = length >= 4 && buffer.getInt(offset, order) == MAGIC

    fun encode(pair: AuditPair): ByteArray {
        require(pair.request.size <= MAX_PAYLOAD_LENGTH && pair.result.size <= MAX_PAYLOAD_LENGTH)
        val length = HEADER_LENGTH + pair.request.size + pair.result.size
        val buffer = ExpandableArrayBuffer(length)
        buffer.putInt(0, MAGIC, order)
        buffer.putInt(4, VERSION, order)
        buffer.putLong(8, pair.sourceId.mostSignificantBits, order)
        buffer.putLong(16, pair.sourceId.leastSignificantBits, order)
        buffer.putLong(24, pair.commandPosition, order)
        buffer.putLong(32, pair.timestamp, order)
        buffer.putLong(40, pair.sessionId, order)
        buffer.putInt(48, pair.request.size, order)
        buffer.putInt(52, pair.result.size, order)
        buffer.putBytes(HEADER_LENGTH, pair.request)
        buffer.putBytes(HEADER_LENGTH + pair.request.size, pair.result)
        return ByteArray(length).also { buffer.getBytes(0, it) }
    }

    fun decode(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): AuditPair {
        require(offset >= 0 && length >= HEADER_LENGTH && offset <= buffer.capacity() - length) { "Truncated audit pair" }
        require(isAudit(buffer, offset, length)) { "Invalid audit magic" }
        require(buffer.getInt(offset + 4, order) == VERSION) { "Unsupported audit version" }
        val requestLength = buffer.getInt(offset + 48, order)
        val resultLength = buffer.getInt(offset + 52, order)
        require(requestLength in 0..MAX_PAYLOAD_LENGTH && resultLength in 0..MAX_PAYLOAD_LENGTH) { "Invalid audit lengths" }
        require(length == HEADER_LENGTH + requestLength + resultLength) { "Invalid audit size" }
        return AuditPair(
            UUID(buffer.getLong(offset + 8, order), buffer.getLong(offset + 16, order)),
            buffer.getLong(offset + 24, order),
            buffer.getLong(offset + 32, order),
            buffer.getLong(offset + 40, order),
            ByteArray(requestLength).also { buffer.getBytes(offset + HEADER_LENGTH, it) },
            ByteArray(resultLength).also { buffer.getBytes(offset + HEADER_LENGTH + requestLength, it) },
        )
    }
}
