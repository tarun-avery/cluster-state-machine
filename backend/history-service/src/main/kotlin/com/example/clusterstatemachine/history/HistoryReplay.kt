package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.archive.PUBLIC_STREAM_ID
import com.example.clusterstatemachine.archive.recordingIds
import com.example.clusterstatemachine.archive.replayRange
import com.example.clusterstatemachine.protocol.AuditPairCodec
import io.aeron.archive.client.AeronArchive
import java.util.concurrent.atomic.AtomicReference

class HistoryReplay(
    private val database: HistoryDatabase,
) {
    val state = AtomicReference("starting")
    val detail = AtomicReference("Waiting for public Archive")

    fun catchUp(archive: AeronArchive) {
        state.set("catching_up")
        for (id in recordingIds(archive, PUBLIC_STREAM_ID)) {
            val start = maxOf(database.resumePosition(id), archive.getStartPosition(id))
            val end = archive.getMaxRecordedPosition(id)
            replayRange(archive, id, start, end, 19404, remote = true) { buffer, offset, length, header ->
                val pair = AuditPairCodec.decode(buffer, offset, length)
                database.insert(pair, id, header.position())
            }
        }
        state.set("caught_up")
        detail.set("Caught up with public recording; cluster export may lag")
    }
}
