package com.example.clusterstatemachine.cluster

import com.example.clusterstatemachine.gateway.CounterClusterClient
import com.example.clusterstatemachine.gateway.VertxWebSocketServer
import com.example.clusterstatemachine.protocol.AuditPair
import com.example.clusterstatemachine.protocol.AuditPairCodec
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

    @Test
    fun `actual request and reply are committed as service messages and replay without clients`() {
        val basePort = availableClusterPorts()
        val data = directory.resolve("audit-cluster").toFile()
        val first = java.util.concurrent.CopyOnWriteArrayList<AuditPair>()

        fun observed(pairs: MutableList<AuditPair>): CounterClusteredService =
            object : CounterClusteredService() {
                override fun onSessionMessage(
                    session: io.aeron.cluster.service.ClientSession?,
                    timestamp: Long,
                    buffer: org.agrona.DirectBuffer,
                    offset: Int,
                    length: Int,
                    header: io.aeron.logbuffer.Header,
                ) {
                    if (session == null && AuditPairCodec.isAudit(buffer, offset, length)) {
                        pairs.add(AuditPairCodec.decode(buffer, offset, length))
                    }
                    super.onSessionMessage(session, timestamp, buffer, offset, length, header)
                }
            }
        SingleNodeClusterRuntime(observed(first), basePort, data).use {
            LegacyClient("127.0.0.1:${basePort + 2}", directory.resolve("audit-client")).use { client ->
                assertEquals(CounterMessage(CounterStatus.OK, 20), client.send(3, 20))
                assertEquals(CounterMessage(CounterStatus.DIVISION_BY_ZERO, 20), client.send(6, 0))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (first.size < 2) {
                check(System.nanoTime() < deadline)
                Thread.sleep(1)
            }
        }
        val replayed = java.util.concurrent.CopyOnWriteArrayList<AuditPair>()
        SingleNodeClusterRuntime(observed(replayed), basePort, data).use {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (replayed.size < 2) {
                check(System.nanoTime() < deadline)
                Thread.sleep(1)
            }
            assertEquals(first.map { it.commandPosition }, replayed.map { it.commandPosition })
            first.zip(replayed).forEach { (a, b) ->
                kotlin.test.assertContentEquals(a.request, b.request)
                kotlin.test.assertContentEquals(a.result, b.result)
            }
        }
    }

    @Test
    fun `public Archive records exact pairs and checkpoint loss safely reexports`() {
        val port = availableClusterPorts()
        val root = directory.resolve("public-test").toFile()
        val publicRoot = directory.resolve("public-recording").toFile()
        SingleNodeClusterRuntime(CounterClusteredService(), port, root).use {
            com.example.clusterstatemachine.archive.PublicArchiveRuntime(publicRoot, DatagramSocket(0).use { it.localPort }).use {
                LegacyClient("127.0.0.1:${port + 2}", directory.resolve("public-client")).use { client ->
                    assertEquals(20, client.send(3, 20).count)
                    assertEquals(CounterStatus.DIVISION_BY_ZERO, client.send(6, 0).status)
                }
                var exported = 0
                com.example.clusterstatemachine.archive.ArchiveBridge(root, publicRoot).use { bridge ->
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (exported < 2) {
                        exported += bridge.exportCommitted()
                        check(System.nanoTime() < deadline)
                        Thread.sleep(1)
                    }
                }
                // Simulate interruption after recording but before its checkpoint survived.
                publicRoot.resolve("checkpoints").listFiles()!!.forEach { it.delete() }
                com.example.clusterstatemachine.archive.ArchiveBridge(root, publicRoot).use { bridge ->
                    assertEquals(2, bridge.exportCommitted())
                }
                io.aeron.Aeron
                    .connect(
                        io.aeron.Aeron
                            .Context()
                            .aeronDirectoryName(publicRoot.resolve("aeron").absolutePath),
                    ).use { aeron ->
                        com.example.clusterstatemachine.archive.archiveClient(aeron, "aeron:ipc?term-length=64k").use { archive ->
                            val pairs = mutableListOf<AuditPair>()
                            for (id in com.example.clusterstatemachine.archive.recordingIds(
                                archive,
                                com.example.clusterstatemachine.archive.PUBLIC_STREAM_ID,
                            )) {
                                com.example.clusterstatemachine.archive.replayRange(
                                    archive,
                                    id,
                                    archive.getStartPosition(id),
                                    archive.getMaxRecordedPosition(id),
                                    19403,
                                ) { buffer, offset, length, _ ->
                                    pairs.add(AuditPairCodec.decode(buffer, offset, length))
                                }
                            }
                            assertEquals(4, pairs.size)
                            assertEquals(pairs[0].commandPosition, pairs[2].commandPosition)
                            kotlin.test.assertContentEquals(pairs[0].request, pairs[2].request)
                            kotlin.test.assertContentEquals(pairs[0].result, pairs[2].result)
                        }
                    }
            }
        }
    }

    @Test
    fun `committed commands recover after process interruption before and after audit offer`() {
        for (phase in listOf("before", "after")) {
            val port = availableClusterPorts()
            val root = directory.resolve("interrupted-$phase").toFile()
            val output = directory.resolve("fixture-$phase.log").toFile()
            val process =
                ProcessBuilder(
                    "${System.getProperty("java.home")}/bin/java",
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp",
                    System.getProperty("fixture.classpath"),
                    "com.example.clusterstatemachine.cluster.AuditInterruptionFixtureKt",
                    root.absolutePath,
                    port.toString(),
                    phase,
                ).redirectErrorStream(true).redirectOutput(output).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!output.readText().contains("FIXTURE_READY")) {
                    check(process.isAlive && System.nanoTime() < deadline) { output.readText() }
                    Thread.sleep(10)
                }
                runCatching {
                    LegacyClient("127.0.0.1:${port + 2}", directory.resolve("interrupt-client-$phase")).use { it.send(3, 20) }
                }
                check(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(77, process.exitValue())
                // Wait until the terminated driver's heartbeat expires, without deleting live driver files.
                Thread.sleep(11000)
                val recovered = java.util.concurrent.CopyOnWriteArrayList<AuditPair>()
                val service =
                    object : CounterClusteredService() {
                        override fun onSessionMessage(
                            session: io.aeron.cluster.service.ClientSession?,
                            timestamp: Long,
                            buffer: org.agrona.DirectBuffer,
                            offset: Int,
                            length: Int,
                            header: io.aeron.logbuffer.Header,
                        ) {
                            if (session == null &&
                                AuditPairCodec.isAudit(buffer, offset, length)
                            ) {
                                recovered.add(AuditPairCodec.decode(buffer, offset, length))
                            }
                            super.onSessionMessage(session, timestamp, buffer, offset, length, header)
                        }
                    }
                SingleNodeClusterRuntime(service, port, root).use {
                    LegacyClient("127.0.0.1:${port + 2}", directory.resolve("recover-client-$phase")).use { client ->
                        assertEquals(20, client.send(0).count)
                    }
                    val auditDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (recovered.none { it.request.size == 9 && it.request[0] == 3.toByte() }) {
                        check(System.nanoTime() < auditDeadline) { "Committed result was not recovered after $phase interruption" }
                        Thread.sleep(1)
                    }
                    val pair = recovered.single { it.request.size == 9 && it.request[0] == 3.toByte() }
                    assertEquals(
                        20,
                        CounterWireCodec.decodeResult(org.agrona.concurrent.UnsafeBuffer(pair.result), 0, pair.result.size).count,
                    )
                    assertEquals(1, pair.captureKind)
                }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    }

    @Test
    fun `real snapshot and restart preserve state and subsequent audit dispatch`() {
        val port = availableClusterPorts()
        val root = directory.resolve("snapshot-cluster").toFile()
        SingleNodeClusterRuntime(CounterClusteredService(), port, root).use {
            LegacyClient("127.0.0.1:${port + 2}", directory.resolve("snapshot-client")).use { client ->
                assertEquals(20, client.send(3, 20).count)
                io.aeron.Aeron
                    .connect(
                        io.aeron.Aeron
                            .Context()
                            .aeronDirectoryName(root.resolve("aeron").absolutePath),
                    ).use { aeron ->
                        val toggle =
                            io.aeron.cluster.ClusterControl
                                .findControlToggle(aeron.countersReader(), 0)
                        check(
                            io.aeron.cluster.ClusterControl.ToggleState.SNAPSHOT
                                .toggle(toggle),
                        )
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        while (true) {
                            val snapshot =
                                io.aeron.cluster
                                    .RecordingLog(root.resolve("cluster"), false)
                                    .use { it.getLatestSnapshot(0) }
                            if (snapshot != null) break
                            check(System.nanoTime() < deadline)
                            Thread.sleep(5)
                        }
                    }
            }
        }
        SingleNodeClusterRuntime(CounterClusteredService(), port, root).use {
            LegacyClient("127.0.0.1:${port + 2}", directory.resolve("snapshot-after")).use { client ->
                assertEquals(20, client.send(0).count)
                assertEquals(60, client.send(5, 3).count)
            }
        }
    }

    @Test
    fun `all generated operations project matching pairs through public Archive into PostgreSQL`() {
        val settings =
            com.example.clusterstatemachine.protocol
                .LocalSettings()
        val url = "jdbc:postgresql://127.0.0.1:${settings.get("POSTGRES_PORT", "5432")}/counter_history"
        val password = settings.required("POSTGRES_PASSWORD")
        val schema =
            "flow_" +
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
        java.sql.DriverManager.getConnection(url, "counter", password).use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                val port = availableClusterPorts()
                val root = directory.resolve("flow-cluster").toFile()
                val publicRoot = directory.resolve("flow-public").toFile()
                SingleNodeClusterRuntime(CounterClusteredService(), port, root).use {
                    com.example.clusterstatemachine.archive.PublicArchiveRuntime(publicRoot, DatagramSocket(0).use { it.localPort }).use {
                        val messages = LinkedBlockingQueue<CounterMessage>()
                        CounterClusterClient("127.0.0.1:${port + 2}", directory.resolve("flow-client").toString()).use { client ->
                            awaitConnected(client)
                            client.addUpdateListener { messages.add(it) }
                            val operations =
                                listOf(
                                    Triple(com.example.clusterstatemachine.protocol.CounterCommand.ADD, 20L, 20L),
                                    Triple(com.example.clusterstatemachine.protocol.CounterCommand.MULTIPLY, 3L, 60L),
                                    Triple(com.example.clusterstatemachine.protocol.CounterCommand.SUBTRACT, 10L, 50L),
                                    Triple(com.example.clusterstatemachine.protocol.CounterCommand.DIVIDE, 4L, 12L),
                                    Triple(com.example.clusterstatemachine.protocol.CounterCommand.DIVIDE, 0L, 12L),
                                )
                            for ((command, quantity, count) in operations) {
                                check(
                                    client.submit(
                                        com.example.clusterstatemachine.protocol
                                            .CounterRequest(command, quantity),
                                    ),
                                )
                                assertEquals(count, assertNotNull(messages.poll(5, TimeUnit.SECONDS)).count)
                            }
                        }
                        LegacyClient("127.0.0.1:${port + 2}", directory.resolve("malformed-client")).use { client ->
                            assertEquals(CounterMessage(CounterStatus.INVALID_COMMAND, 12), client.send(99))
                        }
                        com.example.clusterstatemachine.archive.ArchiveBridge(root, publicRoot).use { bridge ->
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            var exported = 0
                            while (exported <
                                7
                            ) {
                                exported += bridge.exportCommitted()
                                check(System.nanoTime() < deadline)
                                Thread.sleep(1)
                            }
                        }
                        io.aeron.Aeron
                            .connect(
                                io.aeron.Aeron
                                    .Context()
                                    .aeronDirectoryName(publicRoot.resolve("aeron").absolutePath),
                            ).use { aeron ->
                                com.example.clusterstatemachine.archive.archiveClient(aeron, "aeron:ipc?term-length=64k").use { archive ->
                                    com.example.clusterstatemachine.history
                                        .HistoryDatabase(
                                            "$url?currentSchema=$schema",
                                            "counter",
                                            password,
                                        ).use { database ->
                                            val replay =
                                                com.example.clusterstatemachine.history
                                                    .HistoryReplay(database)
                                            replay.catchUp(archive)
                                            replay.catchUp(archive)
                                            val rows =
                                                database
                                                    .page(100, null)
                                                    .getJsonArray("items")
                                                    .map { it as JsonObject }
                                                    .filter {
                                                        it.getString("operation") !in setOf("GET", "INVALID")
                                                    }.reversed()
                                            assertEquals(listOf("20", "60", "50", "12", "12"), rows.map { it.getString("count") })
                                            assertEquals(
                                                listOf("OK", "OK", "OK", "OK", "DIVISION_BY_ZERO"),
                                                rows.map { it.getString("status") },
                                            )
                                            assertEquals(listOf("20", "3", "10", "4", "0"), rows.map { it.getString("quantity") })
                                            val allRows = database.page(100, null).getJsonArray("items").map { it as JsonObject }
                                            assertEquals(7, allRows.size)
                                            val invalid = allRows.single { it.getString("operation") == "INVALID" }
                                            assertEquals("INVALID_COMMAND", invalid.getString("status"))
                                            assertEquals("12", invalid.getString("count"))
                                        }
                                }
                            }
                    }
                }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
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
