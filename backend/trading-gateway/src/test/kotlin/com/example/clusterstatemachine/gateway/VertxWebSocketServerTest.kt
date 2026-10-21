package com.example.clusterstatemachine.gateway

import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterStatus
import io.vertx.core.json.JsonObject
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VertxWebSocketServerTest {
    @Test
    fun `disconnected gateway refuses commands instead of queuing stale clicks`() {
        CounterClusterClient("127.0.0.1:9002", "unused-test-directory").use {
            assertFalse(it.submit(CounterRequest(CounterCommand.INCREMENT)))
        }
    }

    @Test
    fun `websocket bridges commands errors and exact long counts`() {
        val client = mock(CounterClusterClient::class.java)
        val listener = AtomicReference<(CounterMessage) -> Unit>()
        doAnswer {
            listener.set(it.getArgument(0))
            null
        }.`when`(client)
            .addUpdateListener(org.mockito.ArgumentMatchers.any() ?: {})
        val submitted = LinkedBlockingQueue<CounterRequest>()
        `when`(client.submit(org.mockito.ArgumentMatchers.any() ?: CounterRequest(CounterCommand.GET))).thenAnswer {
            val command = it.getArgument<CounterRequest>(0)
            submitted.add(command)
            command.command != CounterCommand.DECREMENT
        }
        val port = ServerSocket(0).use { it.localPort }
        val messages = LinkedBlockingQueue<String>()
        val text = StringBuilder()
        VertxWebSocketServer(client, port).use { server ->
            server.start()
            HttpClient.newHttpClient().use { http ->
                val socket =
                    http
                        .newWebSocketBuilder()
                        .buildAsync(
                            URI("ws://127.0.0.1:$port/ws"),
                            object : WebSocket.Listener {
                                override fun onText(
                                    webSocket: WebSocket,
                                    data: CharSequence,
                                    last: Boolean,
                                ): java.util.concurrent.CompletionStage<*>? {
                                    text.append(data)
                                    if (last) {
                                        messages.add(text.toString())
                                        text.setLength(0)
                                    }
                                    webSocket.request(1)
                                    return null
                                }
                            },
                        ).get(5, TimeUnit.SECONDS)
                try {
                    // A GET is submitted as soon as each browser connects.
                    assertEquals(CounterRequest(CounterCommand.GET), submitted.poll(5, TimeUnit.SECONDS))
                    listener.get().invoke(CounterMessage(CounterStatus.OK, Long.MAX_VALUE))
                    val count = JsonObject(assertNotNull(messages.poll(5, TimeUnit.SECONDS)))
                    assertEquals(Long.MAX_VALUE.toString(), count.getString("count"))
                    socket.sendText("{\"type\":\"increment\"}", true).join()
                    assertEquals(CounterRequest(CounterCommand.INCREMENT), submitted.poll(5, TimeUnit.SECONDS))
                    for (operation in listOf(CounterCommand.ADD, CounterCommand.SUBTRACT, CounterCommand.MULTIPLY, CounterCommand.DIVIDE)) {
                        val request = JsonObject().put("type", operation.name.lowercase()).put("quantity", Long.MIN_VALUE.toString())
                        socket.sendText(request.encode(), true).join()
                        assertEquals(CounterRequest(operation, Long.MIN_VALUE), submitted.poll(5, TimeUnit.SECONDS))
                    }
                    for (invalid in listOf(
                        "not-json",
                        "{\"type\":123}",
                        "{\"type\":\"unknown\"}",
                        "{\"type\":\"add\"}",
                        "{\"type\":\"multiply\",\"quantity\":3}",
                        "{\"type\":\"add\",\"quantity\":\"9223372036854775808\"}",
                        "{\"type\":\"subtract\",\"quantity\":\"1.5\"}",
                        "{\"type\":\"divide\",\"quantity\":null}",
                        "{\"type\":\"get\",\"quantity\":\"2\"}",
                    )) {
                        socket.sendText(invalid, true).join()
                        assertEquals("error", JsonObject(assertNotNull(messages.poll(5, TimeUnit.SECONDS))).getString("type"))
                        assertTrue(submitted.isEmpty())
                    }
                    listener.get().invoke(CounterMessage(CounterStatus.DIVISION_BY_ZERO, 5))
                    assertEquals("division_by_zero", JsonObject(assertNotNull(messages.poll(5, TimeUnit.SECONDS))).getString("status"))
                    socket.sendText("{\"type\":\"decrement\"}", true).join()
                    assertTrue(JsonObject(assertNotNull(messages.poll(5, TimeUnit.SECONDS))).getString("message").contains("unavailable"))
                } finally {
                    socket.abort()
                }
            }
        }
    }

    @Test
    fun `occupied websocket port fails startup`() {
        ServerSocket(0).use { occupied ->
            VertxWebSocketServer(mock(CounterClusterClient::class.java), occupied.localPort).use { server ->
                assertFailsWith<java.util.concurrent.ExecutionException> { server.start() }
            }
        }
    }
}
