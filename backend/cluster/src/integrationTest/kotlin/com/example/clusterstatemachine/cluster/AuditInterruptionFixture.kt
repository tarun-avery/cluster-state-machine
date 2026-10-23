package com.example.clusterstatemachine.cluster

import io.aeron.Image
import io.aeron.cluster.service.Cluster
import org.agrona.DirectBuffer
import java.io.File

/** Separate JVM: terminate precisely around the real service-message publication. */
fun main(arguments: Array<String>) {
    val phase = arguments[2]
    val service =
        object : CounterClusteredService() {
            override fun onStart(
                cluster: Cluster,
                snapshotImage: Image?,
            ) {
                val intercepted =
                    object : Cluster by cluster {
                        override fun offer(
                            buffer: DirectBuffer,
                            offset: Int,
                            length: Int,
                        ): Long {
                            if (phase == "before") Runtime.getRuntime().halt(77)
                            val result = cluster.offer(buffer, offset, length)
                            if (phase == "after" && result >= 0) Runtime.getRuntime().halt(77)
                            return result
                        }
                    }
                super.onStart(intercepted, snapshotImage)
            }
        }
    SingleNodeClusterRuntime(service, arguments[1].toInt(), File(arguments[0])).use {
        println("FIXTURE_READY")
        while (true) Thread.sleep(1000)
    }
}
