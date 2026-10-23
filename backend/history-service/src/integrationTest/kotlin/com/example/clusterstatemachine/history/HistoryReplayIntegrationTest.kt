package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.archive.PUBLIC_CHANNEL
import com.example.clusterstatemachine.archive.PUBLIC_STREAM_ID
import com.example.clusterstatemachine.archive.PublicArchiveRuntime
import com.example.clusterstatemachine.archive.archiveClient
import com.example.clusterstatemachine.protocol.AuditPair
import com.example.clusterstatemachine.protocol.AuditPairCodec
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterStatus
import com.example.clusterstatemachine.protocol.CounterWireCodec
import com.example.clusterstatemachine.protocol.LocalSettings
import io.aeron.Aeron
import io.aeron.archive.status.RecordingPos
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.ExpandableArrayBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.junit.jupiter.api.io.TempDir
import java.net.DatagramSocket
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoryReplayIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `separate driver replays over UDP with idempotency and database rebuild`() {
        val settings = LocalSettings()
        val url = "jdbc:postgresql://127.0.0.1:${settings.get("POSTGRES_PORT", "5432")}/counter_history"
        val password = settings.required("POSTGRES_PASSWORD")
        val port = DatagramSocket(0).use { it.localPort }
        PublicArchiveRuntime(directory.resolve("public").toFile(), port).use { runtime ->
            Aeron.connect(Aeron.Context().aeronDirectoryName(runtime.driver.aeronDirectoryName())).use { writer ->
                archiveClient(writer, "aeron:ipc?term-length=64k").use { archive ->
                    archive.addRecordedExclusivePublication(PUBLIC_CHANNEL, PUBLIC_STREAM_ID).use { publication ->
                        val response = ExpandableArrayBuffer()
                        val size = CounterWireCodec.encodeResult(response, 0, CounterMessage(CounterStatus.OK, 20), true)
                        val pair =
                            AuditPair(UUID.randomUUID(), 128, 123, 4, byteArrayOf(1), ByteArray(size).also { response.getBytes(0, it) }, 1)
                        val bytes = AuditPairCodec.encode(pair)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        var offered: Long
                        do {
                            offered = publication.offer(UnsafeBuffer(bytes), 0, bytes.size)
                            check(System.nanoTime() < deadline)
                            if (offered < 0) Thread.sleep(1)
                        } while (offered < 0)
                        while (true) {
                            val id = RecordingPos.findCounterIdBySession(writer.countersReader(), publication.sessionId())
                            if (id >= 0 && writer.countersReader().getCounterValue(id) >= offered) break
                            check(System.nanoTime() < deadline)
                            Thread.sleep(1)
                        }
                        MediaDriver
                            .launch(
                                MediaDriver
                                    .Context()
                                    .aeronDirectoryName(
                                        directory.resolve("reader").toString(),
                                    ).threadingMode(ThreadingMode.SHARED),
                            ).use { driver ->
                                Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())).use { reader ->
                                    archiveClient(reader, "aeron:udp?endpoint=127.0.0.1:$port").use { remote ->
                                        // Two independent empty projections replay the same retained recording.
                                        repeat(2) { pass ->
                                            val schema = "replay_" + UUID.randomUUID().toString().replace("-", "")
                                            DriverManager.getConnection(url, "counter", password).use { admin ->
                                                admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
                                                try {
                                                    HistoryDatabase("$url?currentSchema=$schema", "counter", password).use { database ->
                                                        val replay = HistoryReplay(database)
                                                        replay.catchUp(remote)
                                                        replay.catchUp(remote)
                                                        assertEquals(1, database.page(100, null).getJsonArray("items").size())
                                                        assertEquals(
                                                            "20",
                                                            database
                                                                .page(100, null)
                                                                .getJsonArray("items")
                                                                .getJsonObject(0)
                                                                .getString("count"),
                                                        )
                                                        assertEquals("caught_up", replay.state.get())
                                                        if (pass == 1) {
                                                            var invalidPosition: Long
                                                            do {
                                                                invalidPosition =
                                                                    publication.offer(UnsafeBuffer(byteArrayOf(1, 2, 3)), 0, 3)
                                                            } while (invalidPosition <
                                                                0
                                                            )
                                                            val recordingCounter =
                                                                RecordingPos.findCounterIdBySession(
                                                                    writer.countersReader(),
                                                                    publication.sessionId(),
                                                                )
                                                            while (writer.countersReader().getCounterValue(recordingCounter) <
                                                                invalidPosition
                                                            ) {
                                                                Thread.sleep(1)
                                                            }
                                                            val recordingId =
                                                                RecordingPos.getRecordingId(
                                                                    writer.countersReader(),
                                                                    recordingCounter,
                                                                )
                                                            val before = database.resumePosition(recordingId)
                                                            assertFailsWith<IllegalArgumentException> { replay.catchUp(remote) }
                                                            assertEquals(before, database.resumePosition(recordingId))
                                                            assertEquals(1, database.page(100, null).getJsonArray("items").size())
                                                        }
                                                    }
                                                } finally {
                                                    admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
}
