package com.example.clusterstatemachine.cluster

import com.example.clusterstatemachine.gateway.CounterClusterClient
import com.example.clusterstatemachine.gateway.VertxWebSocketServer
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterStatus
import com.example.clusterstatemachine.protocol.CounterWireCodec
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.vertx.core.json.JsonObject
import org.agrona.ExpandableArrayBuffer
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TaxiClusterIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `legacy logs migrate to generated commands and both replay after restart`() {
        val basePort = availableClusterPorts()
        val endpoint = "127.0.0.1:${basePort + 2}"
        val data = directory.resolve("cluster").toFile()

        // Record actual legacy payloads before restarting with a fresh service instance.
        SingleNodeClusterRuntime(CounterClusteredService(), basePort, data).use {
            LegacyClient(endpoint, directory.resolve("legacy-before")).use { legacy ->
                assertEquals(CounterMessage(CounterStatus.OK, 20), legacy.send(3, 20))
                assertEquals(CounterMessage(CounterStatus.OK, 60), legacy.send(5, 3))
                assertEquals(CounterMessage(CounterStatus.OK, 59), legacy.send(2))
            }
        }

        SingleNodeClusterRuntime(CounterClusteredService(), basePort, data).use {
            val port = ServerSocket(0).use { it.localPort }
            CounterClusterClient(endpoint, directory.resolve("gateway").toString()).use { client ->
                awaitConnected(client)
                VertxWebSocketServer(client, port).use { server ->
                    server.start()
                    HttpClient.newHttpClient().use { http ->
                        Browser(http, port).use { first ->
                            assertCount(first.next(), "59")
                            Browser(http, port).use { second ->
                                assertCount(second.next(), "59")
                                assertCount(first.next(), "59")

                                fun apply(
                                    type: String,
                                    quantity: String,
                                    expected: String,
                                    status: String = "ok",
                                ) {
                                    first.send(JsonObject().put("type", type).put("quantity", quantity))
                                    assertCount(first.next(), expected, status)
                                    assertCount(second.next(), expected, status)
                                }
                                apply("add", "1", "60")
                                apply("multiply", "3", "180")
                                apply("subtract", "10", "170")
                                apply("divide", "4", "42")
                                apply("divide", "0", "42", "division_by_zero")
                                apply("multiply", "-3", "-126")
                                apply("divide", "5", "-25")
                                apply("multiply", "0", "0")
                                apply("add", Long.MIN_VALUE.toString(), Long.MIN_VALUE.toString())
                                apply("divide", "-1", Long.MIN_VALUE.toString(), "overflow")
                                apply("subtract", Long.MIN_VALUE.toString(), "0")
                                apply("add", Long.MAX_VALUE.toString(), Long.MAX_VALUE.toString())
                                apply("add", "1", Long.MAX_VALUE.toString(), "overflow")
                                first.send(JsonObject().put("type", "add").put("quantity", 3))
                                assertEquals("error", first.next().getString("type"))
                            }
                        }
                    }
                }
            }
        }

        // Replay a real recorded log containing both formats, including full-range quantities.
        SingleNodeClusterRuntime(CounterClusteredService(), basePort, data).use {
            LegacyClient(endpoint, directory.resolve("legacy-after")).use { legacy ->
                assertEquals(CounterMessage(CounterStatus.OK, Long.MAX_VALUE), legacy.send(0))
                assertEquals(CounterMessage(CounterStatus.OVERFLOW, Long.MAX_VALUE), legacy.send(1))
                assertEquals(CounterMessage(CounterStatus.OK, 0), legacy.send(3, -Long.MAX_VALUE))
            }
        }
    }

    private fun assertCount(
        message: JsonObject,
        count: String,
        status: String = "ok",
    ) {
        assertEquals("count", message.getString("type"))
        assertEquals(count, message.getString("count"))
        assertEquals(status, message.getString("status"))
    }

    private fun awaitConnected(client: CounterClusterClient) {
        val ready = CountDownLatch(1)
        val listener: (CounterMessage) -> Unit = { ready.countDown() }
        client.addUpdateListener(listener)
        try {
            client.start()
            check(ready.await(15, TimeUnit.SECONDS)) { "Gateway did not fetch initial cluster state" }
        } finally {
            client.removeUpdateListener(listener)
        }
    }

    private class Browser(
        http: HttpClient,
        port: Int,
    ) : AutoCloseable {
        private val messages = LinkedBlockingQueue<String>()
        private val text = StringBuilder()
        private val socket =
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

        fun send(message: JsonObject) {
            socket.sendText(message.encode(), true).get(5, TimeUnit.SECONDS)
        }

        fun next(): JsonObject = JsonObject(assertNotNull(messages.poll(5, TimeUnit.SECONDS), "Missing WebSocket result"))

        override fun close() {
            socket.abort()
        }
    }

    private class LegacyClient(
        endpoint: String,
        directory: Path,
    ) : AutoCloseable {
        private val responses = LinkedBlockingQueue<CounterMessage>()
        private val driver =
            MediaDriver.launch(
                MediaDriver
                    .Context()
                    .aeronDirectoryName(directory.toString())
                    .threadingMode(ThreadingMode.SHARED)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true),
            )
        private val client =
            try {
                AeronCluster.connect(
                    AeronCluster
                        .Context()
                        .aeronDirectoryName(driver.aeronDirectoryName())
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints("0=$endpoint")
                        .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                        .egressListener(
                            EgressListener { _, _, buffer, offset, length, _ ->
                                assertEquals(9, length, "Legacy requests must receive legacy replies")
                                responses.add(CounterWireCodec.decodeResult(buffer, offset, length))
                            },
                        ),
                )
            } catch (failure: Throwable) {
                driver.close()
                throw failure
            }

        fun send(
            opcode: Byte,
            quantity: Long? = null,
        ): CounterMessage {
            val buffer = ExpandableArrayBuffer(9)
            buffer.putByte(0, opcode)
            quantity?.let { buffer.putLong(1, it, ByteOrder.LITTLE_ENDIAN) }
            val length = if (quantity == null) 1 else 9
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.offer(buffer, 0, length) < 0) {
                check(System.nanoTime() < deadline) { "Legacy command offer timed out" }
                client.sendKeepAlive()
                client.pollEgress()
                Thread.sleep(1)
            }
            while (true) {
                client.pollEgress()
                responses.poll()?.let { return it }
                check(System.nanoTime() < deadline) { "Legacy command reply timed out" }
                client.sendKeepAlive()
                Thread.sleep(1)
            }
        }

        override fun close() {
            try {
                client.close()
            } finally {
                driver.close()
            }
        }
    }

    private fun availableClusterPorts(): Int {
        repeat(100) {
            val base = DatagramSocket(0).use { it.localPort }
            if (base > 65530) return@repeat
            val sockets = mutableListOf<DatagramSocket>()
            try {
                for (port in base + 1..base + 5) {
                    val socket = DatagramSocket(null)
                    sockets.add(socket)
                    socket.bind(InetSocketAddress("127.0.0.1", port))
                }
                return base
            } catch (_: java.net.BindException) {
                // Try another range; concurrent processes can own local UDP ports.
            } finally {
                sockets.forEach { it.close() }
            }
        }
        error("Could not reserve five adjacent UDP ports")
    }
}
