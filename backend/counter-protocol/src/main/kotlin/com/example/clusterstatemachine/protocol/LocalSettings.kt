package com.example.clusterstatemachine.protocol

import java.io.File

/** Environment overrides ignored local .env defaults; contains no embedded secrets. */
class LocalSettings(
    private val environment: Map<String, String> = System.getenv(),
) {
    private val local =
        File(".env")
            .takeIf { it.isFile }
            ?.readLines()
            ?.filter { it.isNotBlank() && !it.startsWith("#") }
            ?.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            .orEmpty()

    fun get(
        name: String,
        fallback: String,
    ): String = environment[name] ?: local[name] ?: fallback

    fun required(name: String): String = get(name, "").also { require(it.isNotBlank()) { "Set $name in environment or .env" } }

    val dataRoot: File get() = File(get("APP_DATA_ROOT", "./data")).absoluteFile
}
