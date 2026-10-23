package com.example.clusterstatemachine.history

import com.example.clusterstatemachine.protocol.LocalSettings

fun main() {
    val settings = LocalSettings()
    HistoryDatabase(
        settings.get("HISTORY_JDBC_URL", "jdbc:postgresql://127.0.0.1:${settings.get("POSTGRES_PORT", "5432")}/counter_history"),
        settings.get("POSTGRES_USER", "counter"),
        settings.required("POSTGRES_PASSWORD"),
    ).use { println("History schema ready") }
}
