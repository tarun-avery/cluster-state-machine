package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.protocol.AuditPair
import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterStatus
import com.example.clusterstatemachine.protocol.CounterWireCodec
import com.example.clusterstatemachine.protocol.LocalSettings
import org.agrona.ExpandableArrayBuffer
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class HistoryDatabaseIntegrationTest {
    @Test
    fun `migrations atomic pairs boundaries duplicates conflicts pagination and restart`() {
        val settings = LocalSettings()
        val url = "jdbc:postgresql://127.0.0.1:${settings.get("POSTGRES_PORT", "5432")}/counter_history"
        val password = settings.required("POSTGRES_PASSWORD")
        val schema = "test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, "counter", password).use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            val isolated = "$url?currentSchema=$schema"
            try {
                val source = UUID.randomUUID()

                fun pair(
                    position: Long,
                    quantity: Long,
                ): AuditPair {
                    val request = ExpandableArrayBuffer()
                    val result = ExpandableArrayBuffer()
                    val rl = CounterWireCodec.encodeRequest(request, 0, CounterRequest(CounterCommand.ADD, quantity))
                    val sl = CounterWireCodec.encodeResult(result, 0, CounterMessage(CounterStatus.OK, quantity))
                    return AuditPair(
                        source,
                        position,
                        123,
                        1,
                        ByteArray(rl).also { request.getBytes(0, it) },
                        ByteArray(sl).also { result.getBytes(0, it) },
                    )
                }
                val first = pair(1, Long.MIN_VALUE)
                HistoryDatabase(isolated, "counter", password).use { db ->
                    db.insert(first, 4, 128)
                    db.insert(first, 4, 256)
                    assertEquals(256, db.resumePosition(4))
                    db.insert(first, 5, 96)
                    assertEquals(256, db.resumePosition(4))
                    assertEquals(96, db.resumePosition(5))
                    assertEquals(1, db.page(100, null).getJsonArray("items").size())
                    assertEquals(
                        Long.MIN_VALUE.toString(),
                        db
                            .page(1, null)
                            .getJsonArray("items")
                            .getJsonObject(0)
                            .getString("quantity"),
                    )
                    assertFailsWith<IllegalStateException> { db.insert(pair(1, 7), 4, 300) }
                    admin.createStatement().use {
                        it.execute(
                            "ALTER TABLE $schema.outgoing ADD CONSTRAINT test_failure CHECK(command_position <> 2)",
                        )
                    }
                    assertFails { db.insert(pair(2, 9), 4, 400) }
                    admin.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM $schema.incoming WHERE command_position=2").use {
                            it.next()
                            assertEquals(0, it.getInt(1))
                        }
                        statement.execute("ALTER TABLE $schema.outgoing DROP CONSTRAINT test_failure")
                    }
                    db.insert(pair(2, Long.MAX_VALUE), 4, 512)
                    val page = db.page(1, null)
                    assertEquals(Long.MAX_VALUE.toString(), page.getJsonArray("items").getJsonObject(0).getString("count"))
                    assertEquals(1, db.page(1, page.getString("nextCursor")).getJsonArray("items").size())
                }
                HistoryDatabase(isolated, "counter", password).use { db ->
                    assertEquals(2, db.page(100, null).getJsonArray("items").size())
                    assertEquals(512, db.resumePosition(4))
                }
                val altered = Files.createTempDirectory("changed-migration")
                try {
                    Files.writeString(altered.resolve("V1__create_incoming_and_outgoing.sql"), "SELECT 1;")
                    assertFails {
                        Flyway
                            .configure()
                            .dataSource(isolated, "counter", password)
                            .locations("filesystem:$altered")
                            .load()
                            .validate()
                    }
                } finally {
                    altered.toFile().deleteRecursively()
                }
            } finally {
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
