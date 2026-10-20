package com.example.clusterstatemachine.protocol

enum class CounterCommand(
    val code: Byte,
) {
    GET(0),
    INCREMENT(1),
    DECREMENT(2),
}

enum class CounterStatus(
    val code: Byte,
) {
    OK(0),
    INVALID_COMMAND(1),
    OVERFLOW(2),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Byte): CounterStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class CounterMessage(
    val status: CounterStatus,
    val count: Long,
)
