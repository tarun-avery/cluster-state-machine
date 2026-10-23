package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.protocol.AuditPair
import com.example.clusterstatemachine.protocol.CounterWireCodec
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.agrona.concurrent.UnsafeBuffer
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.util.UUID

class HistoryDatabase(
    url: String,
    user: String,
    password: String,
) : AutoCloseable {
    private val pool =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 4
                connectionTimeout = 3000
                initializationFailTimeout = 10000
            },
        )

    init {
        try {
            Flyway
                .configure()
                .dataSource(pool)
                .cleanDisabled(true)
                .load()
                .migrate()
        } catch (failure: Throwable) {
            pool.close()
            throw failure
        }
    }

    fun resumePosition(recordingId: Long): Long =
        pool.connection.use { connection ->
            connection.prepareStatement("SELECT COALESCE(MAX(public_position),0) FROM outgoing WHERE public_recording_id=?").use {
                it.setLong(1, recordingId)
                it.executeQuery().use { result ->
                    result.next()
                    result.getLong(1)
                }
            }
        }

    fun insert(
        pair: AuditPair,
        recordingId: Long,
        position: Long,
    ) {
        val request = runCatching { CounterWireCodec.decodeRequest(UnsafeBuffer(pair.request), 0, pair.request.size) }.getOrNull()
        val result = CounterWireCodec.decodeResult(UnsafeBuffer(pair.result), 0, pair.result.size)
        pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection
                    .prepareStatement(
                        """INSERT INTO incoming (source_id,command_position,cluster_timestamp,session_id,protocol_schema_id,operation,quantity,raw_request,capture_kind)
                       VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
                    ).use {
                        it.setObject(1, pair.sourceId)
                        it.setLong(2, pair.commandPosition)
                        it.setLong(3, pair.timestamp)
                        it.setLong(4, pair.sessionId)
                        it.setInt(5, if (pair.request.size == 17) 2 else 0)
                        it.setString(6, request?.command?.name)
                        it.setObject(7, request?.quantity)
                        it.setBytes(8, pair.request)
                        it.setString(
                            9,
                            when (pair.captureKind) {
                                1 -> "CAPTURED"
                                2 -> "RECONSTRUCTED"
                                else -> "UNCLASSIFIED"
                            },
                        )
                        it.executeUpdate()
                    }
                // Conflicting duplicates are corruption, never silently discarded.
                connection
                    .prepareStatement(
                        "SELECT raw_request,cluster_timestamp,session_id FROM incoming WHERE source_id=? AND command_position=?",
                    ).use {
                        it.setObject(1, pair.sourceId)
                        it.setLong(2, pair.commandPosition)
                        it.executeQuery().use { rows ->
                            check(
                                rows.next() && rows.getBytes(1).contentEquals(pair.request) && rows.getLong(2) == pair.timestamp &&
                                    rows.getLong(3) == pair.sessionId,
                            ) {
                                "Conflicting incoming audit identity"
                            }
                        }
                    }
                connection
                    .prepareStatement(
                        """INSERT INTO outgoing (source_id,command_position,status,count,protocol_schema_id,raw_result,public_recording_id,public_position)
                       VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (source_id,command_position) DO UPDATE
                       SET public_recording_id=EXCLUDED.public_recording_id,public_position=EXCLUDED.public_position
                       WHERE outgoing.raw_result=EXCLUDED.raw_result""",
                    ).use {
                        it.setObject(1, pair.sourceId)
                        it.setLong(2, pair.commandPosition)
                        it.setString(3, result.status.name)
                        it.setLong(4, result.count)
                        it.setInt(5, if (pair.result.size == 25) 2 else 0)
                        it.setBytes(6, pair.result)
                        it.setLong(7, recordingId)
                        it.setLong(8, position)
                        check(it.executeUpdate() == 1) { "Conflicting outgoing audit identity" }
                    }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun page(
        limit: Int,
        cursor: String?,
    ): JsonObject {
        require(limit in 1..100)
        return pool.connection.use { connection ->
            val before = cursor?.let { cursorOrder(connection, it) } ?: Long.MAX_VALUE
            connection
                .prepareStatement(
                    """SELECT i.*,o.status,o.count,o.public_recording_id,o.public_position
                   FROM incoming i JOIN outgoing o USING(source_id,command_position)
                   WHERE i.ingested_order < ? ORDER BY i.ingested_order DESC LIMIT ?""",
                ).use {
                    it.setLong(1, before)
                    it.setInt(2, limit + 1)
                    val items = JsonArray()
                    var next: String? = null
                    it.executeQuery().use { rows ->
                        while (rows.next()) {
                            if (items.size() == limit) break
                            val source = rows.getString("source_id")
                            val position = rows.getLong("command_position").toString()
                            items.add(
                                JsonObject()
                                    .put("sourceId", source)
                                    .put("commandPosition", position)
                                    .put("timestamp", rows.getLong("cluster_timestamp").toString())
                                    .put("captureKind", rows.getString("capture_kind"))
                                    .put("operation", rows.getString("operation") ?: "INVALID")
                                    .put("quantity", rows.getObject("quantity")?.toString())
                                    .put("status", rows.getString("status"))
                                    .put("count", rows.getLong("count").toString()),
                            )
                            next = "$source:$position"
                        }
                        if (rows.isAfterLast) next = null
                    }
                    JsonObject().put("items", items).put("nextCursor", next)
                }
        }
    }

    private fun cursorOrder(
        connection: Connection,
        cursor: String,
    ): Long {
        val parts = cursor.split(':')
        require(parts.size == 2) { "Invalid cursor" }
        val source = UUID.fromString(parts[0])
        val position = parts[1].toLong()
        return connection.prepareStatement("SELECT ingested_order FROM incoming WHERE source_id=? AND command_position=?").use {
            it.setObject(1, source)
            it.setLong(2, position)
            it.executeQuery().use { rows ->
                require(rows.next()) { "Unknown cursor" }
                rows.getLong(1)
            }
        }
    }

    override fun close() = pool.close()
}
