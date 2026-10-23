package com.example.clusterstatemachine.gateway

import com.example.clusterstatemachine.protocol.CounterCommand
import com.example.clusterstatemachine.protocol.CounterMessage
import com.example.clusterstatemachine.protocol.CounterRequest
import com.example.clusterstatemachine.protocol.CounterWireCodec
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import org.agrona.ExpandableArrayBuffer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CounterClusterClient(
    @Value("\${aeron.cluster.ingress-endpoint:127.0.0.1:9002}") private val ingressEndpoint: String,
    @Value("\${aeron.client.data-dir:\${app.data-root:./data}/gateway}") dataDir: String,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val commands = ArrayBlockingQueue<CounterRequest>(COMMAND_QUEUE_CAPACITY)
    private val updateListeners = CopyOnWriteArraySet<(CounterMessage) -> Unit>()
    private val clientAeronDirectory = File(dataDir, "aeron-client").absolutePath
    private var executor: ExecutorService? = null

    fun addUpdateListener(listener: (CounterMessage) -> Unit) {
        updateListeners.add(listener)
    }

    fun removeUpdateListener(listener: (CounterMessage) -> Unit) {
        updateListeners.remove(listener)
    }

    @Synchronized
    fun submit(request: CounterRequest): Boolean = connected.get() && commands.offer(request)

    @Synchronized
    private fun disconnect() {
        connected.set(false)
        commands.clear()
    }

    @jakarta.annotation.PostConstruct
    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor =
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "aeron-cluster-client").apply { isDaemon = true }
                }.also { it.submit(::runClientLoop) }
    }

    override fun close() {
        running.set(false)
        disconnect()
        executor?.shutdownNow()
        executor?.awaitTermination(5, TimeUnit.SECONDS)
        executor = null
    }

    private fun runClientLoop() {
        while (running.get()) {
            var mediaDriver: MediaDriver? = null
            var clusterClient: AeronCluster? = null
            try {
                mediaDriver =
                    MediaDriver.launch(
                        MediaDriver
                            .Context()
                            .aeronDirectoryName(clientAeronDirectory)
                            .threadingMode(ThreadingMode.SHARED)
                            .dirDeleteOnStart(true)
                            .dirDeleteOnShutdown(true),
                    )

                val egressListener =
                    EgressListener { _, _, buffer, offset, length, _ ->
                        val result =
                            try {
                                CounterWireCodec.decodeResult(buffer, offset, length)
                            } catch (exception: IllegalArgumentException) {
                                logger.warn("Ignoring invalid counter response: {}", exception.message)
                                null
                            } catch (exception: IndexOutOfBoundsException) {
                                logger.warn("Ignoring truncated counter response: {}", exception.message)
                                null
                            }
                        if (result != null) {
                            updateListeners.forEach { listener ->
                                runCatching { listener(result) }
                                    .onFailure { logger.warn("Counter update listener failed", it) }
                            }
                        }
                    }

                clusterClient =
                    AeronCluster.connect(
                        AeronCluster
                            .Context()
                            .aeronDirectoryName(clientAeronDirectory)
                            .ingressChannel("aeron:udp")
                            .ingressEndpoints("0=$ingressEndpoint")
                            .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                            .egressListener(egressListener),
                    )
                logger.info("Connected to Aeron Cluster at {}", ingressEndpoint)

                commands.offer(CounterRequest(CounterCommand.GET))
                connected.set(true)
                var lastKeepAlive = System.nanoTime()
                val commandBuffer = ExpandableArrayBuffer(CounterWireCodec.ARITHMETIC_LENGTH)
                val idleStrategy = org.agrona.concurrent.BackoffIdleStrategy()
                while (running.get() && !clusterClient.isClosed) {
                    val now = System.nanoTime()
                    if (now - lastKeepAlive >= KEEP_ALIVE_INTERVAL_NS && clusterClient.sendKeepAlive()) {
                        lastKeepAlive = now
                    }
                    val command = commands.poll()
                    if (command != null) {
                        val commandLength = CounterWireCodec.encodeRequest(commandBuffer, 0, command)
                        var result = clusterClient.offer(commandBuffer, 0, commandLength)
                        val offerDeadline = System.nanoTime() + OFFER_TIMEOUT_NS
                        while (result < 0 && running.get() && !clusterClient.isClosed) {
                            check(System.nanoTime() < offerDeadline) { "Timed out offering counter command" }
                            clusterClient.pollEgress()
                            val retryNow = System.nanoTime()
                            if (retryNow - lastKeepAlive >= KEEP_ALIVE_INTERVAL_NS && clusterClient.sendKeepAlive()) {
                                lastKeepAlive = retryNow
                            }
                            idleStrategy.idle()
                            if (!clusterClient.isClosed) result = clusterClient.offer(commandBuffer, 0, commandLength)
                        }
                    }

                    if (clusterClient.pollEgress() == 0 && command == null) {
                        idleStrategy.idle()
                    } else {
                        idleStrategy.reset()
                    }
                }
            } catch (exception: Exception) {
                if (running.get()) logger.error("Aeron Cluster client disconnected; reconnecting", exception)
            } finally {
                disconnect()
                try {
                    clusterClient?.close()
                } finally {
                    mediaDriver?.close()
                }
            }
            if (running.get()) {
                try {
                    TimeUnit.SECONDS.sleep(1)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    companion object {
        private val KEEP_ALIVE_INTERVAL_NS = TimeUnit.SECONDS.toNanos(1)
        private val OFFER_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5)
        private const val COMMAND_QUEUE_CAPACITY = 1024
    }
}
