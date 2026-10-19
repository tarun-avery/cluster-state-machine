package dev.tarunavery.tradinggateway

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import dev.tarunavery.counterprotocol.CounterCommand
import dev.tarunavery.counterprotocol.CounterMessage
import dev.tarunavery.counterprotocol.CounterStatus
import org.agrona.ExpandableArrayBuffer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
class CounterClusterClient(
  @Value("\${aeron.cluster.ingress-endpoint:127.0.0.1:9002}") private val ingressEndpoint: String,
  @Value("\${aeron.client.data-dir:./gateway-data}") dataDir: String,
) : AutoCloseable {
  private val logger = LoggerFactory.getLogger(javaClass)
  private val running = AtomicBoolean(false)
  private val commands = ArrayBlockingQueue<Byte>(COMMAND_QUEUE_CAPACITY)
  private val updateListeners = CopyOnWriteArraySet<(CounterMessage) -> Unit>()
  private val clientAeronDirectory = File(dataDir, "aeron-client").absolutePath
  private var executor: ExecutorService? = null

  fun addUpdateListener(listener: (CounterMessage) -> Unit) {
    updateListeners.add(listener)
  }

  fun removeUpdateListener(listener: (CounterMessage) -> Unit) {
    updateListeners.remove(listener)
  }

  fun submit(command: CounterCommand): Boolean = commands.offer(command.code)

  @jakarta.annotation.PostConstruct
  fun start() {
    if (!running.compareAndSet(false, true)) return
    executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "aeron-cluster-client").apply { isDaemon = true }
    }.also { it.submit(::runClientLoop) }
  }

  override fun close() {
    running.set(false)
    executor?.shutdownNow()
    executor?.awaitTermination(5, TimeUnit.SECONDS)
    executor = null
  }

  private fun runClientLoop() {
    while (running.get()) {
      var mediaDriver: MediaDriver? = null
      var clusterClient: AeronCluster? = null
      try {
        mediaDriver = MediaDriver.launch(
          MediaDriver.Context()
            .aeronDirectoryName(clientAeronDirectory)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
        )

        val egressListener = EgressListener { _, _, buffer, offset, length, _ ->
          if (length >= RESPONSE_LENGTH) {
            val status = CounterStatus.fromCode(buffer.getByte(offset))
            val count = buffer.getLong(offset + 1, ByteOrder.LITTLE_ENDIAN)
            val result = CounterMessage(status, count)
            updateListeners.forEach { listener -> listener(result) }
          } else {
            logger.warn("Ignoring short counter response: {} bytes", length)
          }
        }

        clusterClient = AeronCluster.connect(
          AeronCluster.Context()
            .aeronDirectoryName(clientAeronDirectory)
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ingressEndpoint)
            .egressListener(egressListener)
        )
        logger.info("Connected to Aeron Cluster at {}", ingressEndpoint)

        val commandBuffer = ExpandableArrayBuffer(COMMAND_LENGTH)
        val idleStrategy = org.agrona.concurrent.BackoffIdleStrategy()
        while (running.get() && !clusterClient.isClosed) {
          val command = commands.poll()
          if (command != null) {
            commandBuffer.putByte(0, command)
            var result = clusterClient.offer(commandBuffer, 0, COMMAND_LENGTH)
            while (result < 0 && running.get()) {
              clusterClient.pollEgress()
              idleStrategy.idle()
              result = clusterClient.offer(commandBuffer, 0, COMMAND_LENGTH)
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
        clusterClient?.close()
        mediaDriver?.close()
      }
    }
  }

  companion object {
    private const val COMMAND_LENGTH = 1
    private const val RESPONSE_LENGTH = 1 + Long.SIZE_BYTES
    private const val COMMAND_QUEUE_CAPACITY = 1024
  }
}
