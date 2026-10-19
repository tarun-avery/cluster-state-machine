package dev.tarunavery.tradinggateway

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import dev.tarunavery.counterprotocol.CounterCommand
import dev.tarunavery.counterprotocol.CounterMessage
import dev.tarunavery.counterprotocol.CounterStatus
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
  private val sockets = CopyOnWriteArraySet<io.vertx.core.http.ServerWebSocket>()
  private val updateListener: (CounterMessage) -> Unit = { message ->
    val json = JsonObject()
      .put("type", "count")
      .put("count", message.count)
      .put("status", message.statusName())
      .encode()

    vertx.runOnContext { _ ->
      sockets.filter { !it.isClosed }.forEach { it.writeTextMessage(json) }
    }
  }

  @jakarta.annotation.PostConstruct
  fun start() {
    clusterClient.addUpdateListener(updateListener)
    vertx.createHttpServer()
      .webSocketHandler { socket ->
        if (socket.path() != "/ws") {
          socket.close()
          return@webSocketHandler
        }

        sockets.add(socket)
        socket.closeHandler { sockets.remove(socket) }
        socket.textMessageHandler { text -> handleMessage(socket, text) }
        if (!clusterClient.submit(CounterCommand.GET)) {
          socket.writeTextMessage(errorMessage("Gateway is busy; try again"))
        }
      }
      .listen(port)
      .onSuccess { logger.info("Vert.x WebSocket listening on port {} at /ws", port) }
      .onFailure { logger.error("Could not start Vert.x WebSocket server", it) }
  }

  override fun close() {
    clusterClient.removeUpdateListener(updateListener)
    sockets.forEach { it.close() }
    vertx.close()
  }

  private fun handleMessage(socket: io.vertx.core.http.ServerWebSocket, text: String) {
    val requested = runCatching { JsonObject(text).getString("type") }.getOrNull()
    val command = when (requested) {
      "get" -> CounterCommand.GET
      "increment" -> CounterCommand.INCREMENT
      "decrement" -> CounterCommand.DECREMENT
      else -> null
    }

    if (command == null) {
      socket.writeTextMessage(errorMessage("Expected type: get, increment, or decrement"))
    } else if (!clusterClient.submit(command)) {
      socket.writeTextMessage(errorMessage("Gateway is busy; try again"))
    }
  }

  private fun errorMessage(message: String) =
    JsonObject().put("type", "error").put("message", message).encode()
}

private fun CounterMessage.statusName(): String = when (status) {
  CounterStatus.OK -> "ok"
  CounterStatus.INVALID_COMMAND -> "invalid_command"
  CounterStatus.OVERFLOW -> "overflow"
  CounterStatus.UNKNOWN -> "unknown"
}
