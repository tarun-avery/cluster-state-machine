package com.example.clusterstatemachine.archive

import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.archive.Archive
import io.aeron.archive.client.AeronArchive
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.FragmentHandler
import java.io.File
import java.util.concurrent.TimeUnit

const val PUBLIC_STREAM_ID = 19401
const val PUBLIC_CONTROL_PORT = 9021
const val PUBLIC_CHANNEL = "aeron:ipc?term-length=8m"

fun archiveClient(
    aeron: Aeron,
    control: String,
): AeronArchive =
    AeronArchive.connect(
        AeronArchive
            .Context()
            .aeron(aeron)
            .ownsAeronClient(false)
            .controlRequestChannel(control)
            .controlResponseChannel("aeron:udp?endpoint=127.0.0.1:0"),
    )

class PublicArchiveRuntime(
    root: File,
    port: Int = PUBLIC_CONTROL_PORT,
) : AutoCloseable {
    val driver: MediaDriver =
        MediaDriver.launch(
            MediaDriver
                .Context()
                .aeronDirectoryName(File(root, "aeron").absolutePath)
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true),
        )
    private val archive =
        try {
            Archive.launch(
                Archive
                    .Context()
                    .aeronDirectoryName(driver.aeronDirectoryName())
                    .archiveDir(File(root, "archive"))
                    .controlChannel("aeron:udp?endpoint=127.0.0.1:$port")
                    .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
                    .localControlChannel("aeron:ipc?term-length=64k")
                    .recordingEventsEnabled(false)
                    .fileSyncLevel(1)
                    .catalogFileSyncLevel(1),
            )
        } catch (failure: Throwable) {
            driver.close()
            throw failure
        }

    override fun close() {
        try {
            archive.close()
        } finally {
            driver.close()
        }
    }
}

fun recordingIds(
    archive: AeronArchive,
    streamId: Int? = null,
): List<Long> {
    val ids = mutableListOf<Long>()
    var next = 0L
    while (true) {
        var last = next
        val count =
            archive.listRecordings(next, 100) { _, _, id, _, _, _, _, _, _, _, _, _, stream, _, _, _ ->
                last = id + 1
                if (streamId == null || stream == streamId) ids.add(id)
            }
        if (count == 0) break
        next = last
    }
    return ids
}

/** Finite replay only. Caller selects a committed/recorded end boundary. */
fun replayRange(
    archive: AeronArchive,
    id: Long,
    start: Long,
    end: Long,
    streamId: Int,
    handler: FragmentHandler,
) {
    if (end <= start) return
    archive.replay(id, start, end - start, "aeron:ipc", streamId).use { subscription ->
        val assembler = FragmentAssembler(handler)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            val fragments = subscription.poll(assembler, 32)
            val image = if (subscription.imageCount() > 0) subscription.imageAtIndex(0) else null
            if (image != null && (image.position() >= end || image.isEndOfStream)) break
            check(System.nanoTime() < deadline) { "Archive replay timed out at recording $id ($start..$end)" }
            if (fragments == 0) Thread.sleep(1)
        }
    }
}
