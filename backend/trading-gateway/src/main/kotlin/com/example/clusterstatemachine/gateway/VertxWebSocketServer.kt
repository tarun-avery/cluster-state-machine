package com.example.clusterstatemachine.gateway

import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArraySet

@Component
class VertxWebSocketServer(
    private val clusterClient: CounterClusterClient,
    @Value("\${gateway.websocket.port:8081}") private val port: Int,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val vertx = Vertx.vertx()
    private val updateContext = vertx.getOrCreateContext()
    private val sockets = CopyOnWriteArraySet<io.vertx.core.http.ServerWebSocket>()
    private val updateListener: (CounterMessage) -> Unit = { message ->
        val json =
            JsonObject()
                .put("type", "count")
                .put("count", message.count.toString())
                .put("status", message.statusName())
                .encode()

        updateContext.runOnContext { _ ->
            sockets.filter { !it.isClosed }.forEach { it.writeTextMessage(json) }
        }
    }

    @jakarta.annotation.PostConstruct
    fun start() {
        clusterClient.addUpdateListener(updateListener)
        vertx
            .createHttpServer()
            .webSocketHandler { socket ->
                if (socket.path() != "/ws") {
                    socket.close()
                    return@webSocketHandler
                }

                sockets.add(socket)
                socket.closeHandler { sockets.remove(socket) }
                socket.textMessageHandler { text -> handleMessage(socket, text) }
                if (!clusterClient.submit(CounterRequest(CounterCommand.GET))) {
                    socket.writeTextMessage(errorMessage("Cluster unavailable or gateway busy; try again"))
                }
            }.listen(port)
            .onFailure {
                clusterClient.removeUpdateListener(updateListener)
                vertx.close()
            }.toCompletionStage()
            .toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS)
        logger.info("Vert.x WebSocket listening on port {} at /ws", port)
    }

    override fun close() {
        clusterClient.removeUpdateListener(updateListener)
        vertx
            .close()
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun handleMessage(
        socket: io.vertx.core.http.ServerWebSocket,
        text: String,
    ) {
        val json = runCatching { JsonObject(text) }.getOrNull()
        val requested = runCatching { json?.getString("type") }.getOrNull()
        val command =
            when (requested) {
                "get" -> CounterCommand.GET
                "increment" -> CounterCommand.INCREMENT
                "decrement" -> CounterCommand.DECREMENT
                "add" -> CounterCommand.ADD
                "subtract" -> CounterCommand.SUBTRACT
                "multiply" -> CounterCommand.MULTIPLY
                "divide" -> CounterCommand.DIVIDE
                else -> null
            }

        if (command == null) {
            socket.writeTextMessage(errorMessage("Expected type: get, increment, decrement, add, subtract, multiply, or divide"))
            return
        }
        val quantityText = json?.getValue("quantity") as? String
        val quantity = quantityText?.takeIf { it.matches(Regex("[+-]?[0-9]+")) }?.toLongOrNull()
        if ((command.requiresQuantity && quantity == null) || (!command.requiresQuantity && json?.containsKey("quantity") == true)) {
            socket.writeTextMessage(
                errorMessage("Arithmetic quantity must be a signed 64-bit whole-number string; omit it for get/increment/decrement"),
            )
            return
        }
        if (!clusterClient.submit(CounterRequest(command, quantity))) {
            socket.writeTextMessage(errorMessage("Cluster unavailable or gateway busy; try again"))
        }
    }

    private fun errorMessage(message: String) = JsonObject().put("type", "error").put("message", message).encode()
}

private fun CounterMessage.statusName(): String =
    when (status) {
        CounterStatus.OK -> "ok"
        CounterStatus.INVALID_COMMAND -> "invalid_command"
        CounterStatus.OVERFLOW -> "overflow"
        CounterStatus.DIVISION_BY_ZERO -> "division_by_zero"
        CounterStatus.UNKNOWN -> "unknown"
    }
