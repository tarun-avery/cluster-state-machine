package com.example.clusterstatemachine.archive

import com.example.clusterstatemachine.protocol.LocalSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val settings = LocalSettings()
    val active = AtomicBoolean(true)
    Runtime.getRuntime().addShutdownHook(Thread { active.set(false) })
    val publicRoot = File(settings.dataRoot, "public-archive")
    PublicArchiveRuntime(publicRoot).use {
        ArchiveBridge(File(settings.dataRoot, "cluster"), publicRoot).use { bridge ->
            println("Public Archive bridge ready on loopback port $PUBLIC_CONTROL_PORT, stream $PUBLIC_STREAM_ID")
            while (active.get()) {
                val count = bridge.exportCommitted()
                if (count > 0) println("Recorded $count audit pairs")
                Thread.sleep(100)
            }
        }
    }
}
