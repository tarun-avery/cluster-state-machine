package com.example.clusterstatemachine.archive

import com.example.clusterstatemachine.protocol.AuditPairCodec
import io.aeron.Aeron
import io.aeron.archive.client.AeronArchive
import io.aeron.archive.status.RecordingPos
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.codecs.MessageHeaderDecoder
import io.aeron.cluster.codecs.SessionMessageHeaderDecoder
import io.aeron.cluster.service.ClusterCounters
import io.aeron.cluster.service.ClusteredServiceContainer
import org.agrona.concurrent.UnsafeBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

class ArchiveBridge(
    private val clusterRoot: File,
    private val publicRoot: File,
) : AutoCloseable {
    private val source = Aeron.connect(Aeron.Context().aeronDirectoryName(File(clusterRoot, "aeron").absolutePath))
    private val sourceArchive =
        try {
            archiveClient(source, "aeron:ipc?term-length=64k")
        } catch (
            failure: Throwable,
        ) {
            source.close()
            throw failure
        }
    private val target = Aeron.connect(Aeron.Context().aeronDirectoryName(File(publicRoot, "aeron").absolutePath))
    private val targetArchive = archiveClient(target, "aeron:ipc?term-length=64k")
    private val publication = targetArchive.addRecordedExclusivePublication(PUBLIC_CHANNEL, PUBLIC_STREAM_ID)
    private val sourceId = File(clusterRoot, "source-id").readText().trim()
    private val checkpoint = File(publicRoot, "checkpoints/$sourceId-v1.position")
    private var position = if (checkpoint.exists()) checkpoint.readText().trim().toLong() else 0L

    /** A bounded pass can be restarted; only recorded publication progress permits checkpointing. */
    fun exportCommitted(): Int {
        val counters = source.countersReader()
        val counterId = ClusterCounters.find(counters, ClusteredServiceContainer.Configuration.COMMIT_POSITION_TYPE_ID, 0)
        check(counterId >= 0) { "Cluster commit counter unavailable" }
        val committed = counters.getCounterValue(counterId)
        var exported = 0
        RecordingLog(File(clusterRoot, "cluster"), false).use { log ->
            val terms =
                log
                    .entries()
                    .filter { it.isValid && it.type == RecordingLog.ENTRY_TYPE_TERM }
                    .distinctBy { it.recordingId }
                    .sortedBy { it.termBaseLogPosition }
            for (term in terms) {
                val start = maxOf(position, sourceArchive.getStartPosition(term.recordingId))
                val end = minOf(committed, sourceArchive.getMaxRecordedPosition(term.recordingId))
                if (end <= start) continue
                replayRange(sourceArchive, term.recordingId, start, end, 19402) { buffer, offset, length, _ ->
                    val header = MessageHeaderDecoder().wrap(buffer, offset)
                    if (header.templateId() == SessionMessageHeaderDecoder.TEMPLATE_ID) {
                        val session =
                            SessionMessageHeaderDecoder().wrap(
                                buffer,
                                offset + MessageHeaderDecoder.ENCODED_LENGTH,
                                header.blockLength(),
                                header.version(),
                            )
                        val payloadOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH + header.blockLength()
                        val payloadLength = length - (payloadOffset - offset)
                        if (session.clusterSessionId() < 0 && AuditPairCodec.isAudit(buffer, payloadOffset, payloadLength)) {
                            val pair = AuditPairCodec.decode(buffer, payloadOffset, payloadLength)
                            check(pair.sourceId.toString() == sourceId) { "Audit source differs from cluster identity" }
                            val bytes = AuditPairCodec.encode(pair)
                            record(bytes)
                            exported++
                        }
                    }
                }
                // The entire finite replay completed and every offered pair reached Archive storage.
                persistCheckpoint(end)
                position = end
            }
        }
        return exported
    }

    private fun record(bytes: ByteArray) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val buffer = UnsafeBuffer(bytes)
        var offered: Long
        while (true) {
            offered = publication.offer(buffer, 0, bytes.size)
            if (offered >= 0) break
            check(System.nanoTime() < deadline) { "Public Archive backpressure or unavailable publication" }
            Thread.sleep(1)
        }
        val counters = target.countersReader()
        while (true) {
            val id = RecordingPos.findCounterIdBySession(counters, publication.sessionId())
            if (id >= 0 && counters.getCounterValue(id) >= offered) return
            check(System.nanoTime() < deadline) { "Public Archive did not record offered audit pair" }
            Thread.sleep(1)
        }
    }

    private fun persistCheckpoint(value: Long) {
        checkpoint.parentFile.mkdirs()
        val temporary = File(checkpoint.parentFile, checkpoint.name + ".tmp").toPath()
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            it.write(ByteBuffer.wrap("$value\n".toByteArray()))
            it.force(true)
        }
        Files.move(temporary, checkpoint.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(checkpoint.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    override fun close() {
        publication.close()
        targetArchive.close()
        target.close()
        sourceArchive.close()
        source.close()
    }
}
