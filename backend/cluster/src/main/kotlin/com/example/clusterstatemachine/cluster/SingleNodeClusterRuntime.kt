package com.example.clusterstatemachine.cluster

import io.aeron.archive.Archive
import io.aeron.archive.client.AeronArchive
import io.aeron.cluster.ClusteredMediaDriver
import io.aeron.cluster.ConsensusModule
import io.aeron.cluster.RecordingLog
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.MediaDriver
import org.agrona.concurrent.NoOpLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

@Configuration
class ClusterRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    fun clusterRuntime(
        service: CounterClusteredService,
        @Value("\${aeron.cluster.base-port:9000}") basePort: Int,
        @Value("\${aeron.cluster.data-dir:\${app.data-root:./data}/cluster}") dataDir: String,
    ) = SingleNodeClusterRuntime(service, basePort, File(dataDir))
}

class SingleNodeClusterRuntime(
    service: CounterClusteredService,
    basePort: Int,
    dataDir: File,
) : AutoCloseable {
    init {
        dataDir.mkdirs()
        val identity = File(dataDir, "source-id")
        if (!identity.exists()) {
            Files.writeString(identity.toPath(), UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW)
        }
        service.sourceId = UUID.fromString(identity.readText().trim())
    }

    private val aeronDirectory = File(dataDir, "aeron").absolutePath
    private val archiveDirectory = File(dataDir, "archive")
    private val clusterDirectory = File(dataDir, "cluster")
    private val host = "127.0.0.1"
    private val ingressPort = basePort + CLIENT_FACING_PORT_OFFSET
    private val memberConfiguration =
        "0,$host:$ingressPort,$host:${basePort + MEMBER_FACING_PORT_OFFSET}," +
            "$host:${basePort + LOG_PORT_OFFSET},$host:${basePort + TRANSFER_PORT_OFFSET}," +
            "$host:${basePort + ARCHIVE_CONTROL_PORT_OFFSET}|"

    private val mediaDriverContext =
        MediaDriver
            .Context()
            .aeronDirectoryName(aeronDirectory)

    private val replicationArchiveContext =
        AeronArchive
            .Context()
            .controlResponseChannel("aeron:udp?endpoint=$host:0")

    private val archiveContext =
        Archive
            .Context()
            .aeronDirectoryName(aeronDirectory)
            .archiveDir(archiveDirectory)
            .controlChannel("aeron:udp?endpoint=$host:${basePort + ARCHIVE_CONTROL_PORT_OFFSET}")
            .archiveClientContext(replicationArchiveContext)
            .localControlChannel("aeron:ipc?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=$host:0")
            .recordingEventsEnabled(false)

    private val aeronArchiveContext =
        AeronArchive
            .Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirectory)

    private val consensusContext =
        ConsensusModule
            .Context()
            .controlChannel("aeron:ipc?term-length=8m")
            .clusterMemberId(0)
            .clusterMembers(memberConfiguration)
            .clusterDir(clusterDirectory)
            .archiveContext(aeronArchiveContext.clone())
            .serviceCount(1)
            .replicationChannel("aeron:udp?endpoint=$host:0")
            .ingressChannel("aeron:udp?endpoint=$host:$ingressPort")

    private val serviceContext =
        ClusteredServiceContainer
            .Context()
            .aeronDirectoryName(aeronDirectory)
            .archiveContext(aeronArchiveContext.clone())
            .clusterDir(clusterDirectory)
            .clusteredService(service)
            .controlChannel("aeron:ipc?term-length=8m")
            .serviceId(0)

    private val clusteredMediaDriver =
        ClusteredMediaDriver.launch(
            mediaDriverContext,
            archiveContext,
            consensusContext,
        )
    private val serviceContainer =
        try {
            val cutoff = File(dataDir, "audit-start-position")
            if (!cutoff.exists()) {
                AeronArchive.connect(aeronArchiveContext.clone()).use { archive ->
                    val end =
                        RecordingLog(clusterDirectory, false).use { log ->
                            log
                                .entries()
                                .filter { it.isValid && it.type == RecordingLog.ENTRY_TYPE_TERM }
                                .map { archive.getMaxRecordedPosition(it.recordingId) }
                                .maxOrNull() ?: 0L
                        }
                    cutoff.writeText(end.toString())
                }
            }
            service.captureStartPosition = cutoff.readText().trim().toLong()
            ClusteredServiceContainer.launch(serviceContext)
        } catch (exception: Throwable) {
            clusteredMediaDriver.close()
            throw exception
        }

    override fun close() {
        try {
            serviceContainer.close()
        } finally {
            clusteredMediaDriver.close()
        }
    }

    private companion object {
        const val ARCHIVE_CONTROL_PORT_OFFSET = 1
        const val CLIENT_FACING_PORT_OFFSET = 2
        const val MEMBER_FACING_PORT_OFFSET = 3
        const val LOG_PORT_OFFSET = 4
        const val TRANSFER_PORT_OFFSET = 5
    }
}
