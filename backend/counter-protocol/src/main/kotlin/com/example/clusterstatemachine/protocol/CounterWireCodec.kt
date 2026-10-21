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
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import java.nio.ByteOrder

/** Generated wire adapters are callback-scoped; returned requests/results own their values. */
object CounterWireCodec {
    const val RESULT_LENGTH = CounterResultEncoder.HEADER_LENGTH + CounterResultEncoder.BLOCK_LENGTH
    const val ARITHMETIC_LENGTH = AddEncoder.HEADER_LENGTH + AddEncoder.BLOCK_LENGTH
    private const val LEGACY_RESULT_LENGTH = 1 + Long.SIZE_BYTES

    fun encodeRequest(
        buffer: MutableDirectBuffer,
        offset: Int,
        request: CounterRequest,
    ): Int {
        val quantity = request.quantity ?: 1L
        when (request.command) {
            CounterCommand.GET -> {
                buffer.putByte(offset, CounterCommand.GET.code)
                return 1
            }

            CounterCommand.INCREMENT, CounterCommand.ADD -> {
                AddEncoder().wrapAndApplyHeader(buffer, offset).quantity(quantity)
            }

            CounterCommand.DECREMENT, CounterCommand.SUBTRACT -> {
                SubtractEncoder().wrapAndApplyHeader(buffer, offset).quantity(quantity)
            }

            CounterCommand.MULTIPLY -> {
                MultiplyEncoder().wrapAndApplyHeader(buffer, offset).quantity(quantity)
            }

            CounterCommand.DIVIDE -> {
                DivideEncoder().wrapAndApplyHeader(buffer, offset).quantity(quantity)
            }
        }
        return ARITHMETIC_LENGTH
    }

    fun decodeRequest(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): CounterRequest {
        validateBounds(buffer, offset, length)
        if (isLegacyRequest(length)) {
            val command = CounterCommand.entries.firstOrNull { it.code == buffer.getByte(offset) }
            require(command != null) { "Unknown legacy command" }
            require(length == if (command.requiresQuantity) 9 else 1) { "Invalid legacy command length" }
            return CounterRequest(command, if (command.requiresQuantity) buffer.getLong(offset + 1, ByteOrder.LITTLE_ENDIAN) else null)
        }
        validateHeader(buffer, offset, length)
        val template = buffer.getShort(offset + 2, ByteOrder.LITTLE_ENDIAN).toInt() and 65535
        val (command, quantity) =
            when (template) {
                AddEncoder.TEMPLATE_ID -> {
                    CounterCommand.ADD to AddDecoder().wrapAndApplyHeader(buffer, offset, length).quantity()
                }

                SubtractEncoder.TEMPLATE_ID -> {
                    CounterCommand.SUBTRACT to
                        SubtractDecoder().wrapAndApplyHeader(buffer, offset, length).quantity()
                }

                MultiplyEncoder.TEMPLATE_ID -> {
                    CounterCommand.MULTIPLY to
                        MultiplyDecoder().wrapAndApplyHeader(buffer, offset, length).quantity()
                }

                DivideEncoder.TEMPLATE_ID -> {
                    CounterCommand.DIVIDE to DivideDecoder().wrapAndApplyHeader(buffer, offset, length).quantity()
                }

                else -> {
                    throw IllegalArgumentException("Unknown counter command template")
                }
            }
        require(length == ARITHMETIC_LENGTH) { "Unexpected version 0 command length" }
        return CounterRequest(command, quantity)
    }

    fun isLegacyRequest(length: Int): Boolean = length == 1 || length == 9

    fun encodeResult(
        buffer: MutableDirectBuffer,
        offset: Int,
        message: CounterMessage,
        legacy: Boolean = false,
    ): Int {
        if (legacy) {
            buffer.putByte(offset, message.status.code)
            buffer.putLong(offset + 1, message.count, ByteOrder.LITTLE_ENDIAN)
            return LEGACY_RESULT_LENGTH
        }
        CounterResultEncoder().wrapAndApplyHeader(buffer, offset).status(message.status.code.toLong()).count(message.count)
        return RESULT_LENGTH
    }

    fun decodeResult(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ): CounterMessage {
        validateBounds(buffer, offset, length)
        if (length == LEGACY_RESULT_LENGTH) {
            return CounterMessage(CounterStatus.fromCode(buffer.getByte(offset)), buffer.getLong(offset + 1, ByteOrder.LITTLE_ENDIAN))
        }
        validateHeader(buffer, offset, length)
        require(length == RESULT_LENGTH) { "Unexpected version 0 result length" }
        val decoder = CounterResultDecoder().wrapAndApplyHeader(buffer, offset, length)
        val code = decoder.status()
        val status = CounterStatus.entries.firstOrNull { it.code.toLong() == code } ?: CounterStatus.UNKNOWN
        return CounterMessage(status, decoder.count())
    }

    private fun validateBounds(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length > 0 && offset.toLong() + length <= buffer.capacity()) { "Invalid transport bounds" }
    }

    private fun validateHeader(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
    ) {
        require(length >= 8) { "Truncated counter header" }
        require((buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == AddEncoder.SCHEMA_ID) {
            "Unknown counter schema"
        }
        require((buffer.getShort(offset + 6, ByteOrder.LITTLE_ENDIAN).toInt() and 65535) == AddEncoder.SCHEMA_VERSION) {
            "Unsupported counter schema version"
        }
        val blockLength = buffer.getShort(offset, ByteOrder.LITTLE_ENDIAN).toInt() and 65535
        require(blockLength == length - 8) { "Counter message length does not match its header" }
    }
}
