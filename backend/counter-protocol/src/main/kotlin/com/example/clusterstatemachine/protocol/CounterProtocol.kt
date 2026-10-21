package com.example.clusterstatemachine.protocol

enum class CounterCommand(
    val code: Byte,
    val requiresQuantity: Boolean = false,
) {
    GET(0),
    INCREMENT(1),
    DECREMENT(2),
    ADD(3, true),
    SUBTRACT(4, true),
    MULTIPLY(5, true),
    DIVIDE(6, true),
}

enum class CounterStatus(
    val code: Byte,
) {
    OK(0),
    INVALID_COMMAND(1),
    OVERFLOW(2),
    DIVISION_BY_ZERO(3),
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

data class CounterRequest(
    val command: CounterCommand,
    val quantity: Long? = null,
) {
    init {
        require(command.requiresQuantity == (quantity != null)) { "Arithmetic commands require a quantity" }
    }
}
