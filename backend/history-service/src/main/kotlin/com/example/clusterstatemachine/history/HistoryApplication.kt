package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.archive.PUBLIC_CONTROL_PORT
import com.example.clusterstatemachine.archive.archiveClient
import com.example.clusterstatemachine.protocol.LocalSettings
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import io.aeron.driver.ThreadingMode
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val settings = LocalSettings()
    val active = AtomicBoolean(true)
    val stopped = java.util.concurrent.CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            active.set(false)
            stopped.await(40, java.util.concurrent.TimeUnit.SECONDS)
        },
    )
    try {
        HistoryDatabase(
            settings.get("HISTORY_JDBC_URL", "jdbc:postgresql://127.0.0.1:${settings.get("POSTGRES_PORT", "5432")}/counter_history"),
            settings.get("POSTGRES_USER", "counter"),
            settings.required("POSTGRES_PASSWORD"),
        ).use { database ->
            val replay = HistoryReplay(database)
            val vertx = Vertx.vertx()
            val server =
                vertx
                    .createHttpServer()
                    .requestHandler { request ->
                        if (request.path() == "/health") {
                            request.response().putHeader("content-type", "application/json").end(
                                JsonObject().put("state", replay.state.get()).put("detail", replay.detail.get()).encode(),
                            )
                        } else if (request.path() == "/history" && request.method().name() == "GET") {
                            vertx
                                .executeBlocking(
                                    java.util.concurrent.Callable {
                                        database
                                            .page(request.getParam("limit")?.toInt() ?: 20, request.getParam("cursor"))
                                            .put("state", replay.state.get())
                                            .put("detail", replay.detail.get())
                                            .encode()
                                    },
                                ).onSuccess { request.response().putHeader("content-type", "application/json").end(it) }
                                .onFailure { failure ->
                                    val status = if (failure is IllegalArgumentException) 400 else 503
                                    request
                                        .response()
                                        .setStatusCode(status)
                                        .putHeader("content-type", "application/json")
                                        .end(
                                            JsonObject()
                                                .put(
                                                    "error",
                                                    if (status ==
                                                        400
                                                    ) {
                                                        "Invalid history page request"
                                                    } else {
                                                        "History database unavailable"
                                                    },
                                                ).encode(),
                                        )
                                }
                        } else {
                            request.response().setStatusCode(404).end()
                        }
                    }.listen(settings.get("HISTORY_PORT", "8082").toInt(), "127.0.0.1")
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get()
            println("History HTTP ready at http://127.0.0.1:${server.actualPort()}; Flyway validated")
            try {
                MediaDriver
                    .launch(
                        MediaDriver
                            .Context()
                            .aeronDirectoryName(File(settings.dataRoot, "history/aeron-client").absolutePath)
                            .threadingMode(ThreadingMode.SHARED)
                            .dirDeleteOnStart(true)
                            .dirDeleteOnShutdown(true),
                    ).use { driver ->
                        Aeron.connect(Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName())).use { aeron ->
                            while (active.get()) {
                                try {
                                    archiveClient(aeron, "aeron:udp?endpoint=127.0.0.1:$PUBLIC_CONTROL_PORT").use { archive ->
                                        while (active.get()) {
                                            replay.catchUp(archive)
                                            Thread.sleep(500)
                                        }
                                    }
                                } catch (failure: Exception) {
                                    replay.state.set("unavailable")
                                    replay.detail.set("Replay paused: ${failure.javaClass.simpleName}; retrying retained data")
                                    System.err.println(replay.detail.get())
                                    failure.printStackTrace()
                                    Thread.sleep(2000)
                                }
                            }
                        }
                    }
            } finally {
                vertx
                    .close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get()
            }
        }
    } finally {
        stopped.countDown()
    }
}
