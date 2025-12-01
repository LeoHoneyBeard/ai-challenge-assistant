package com.aichallenge.assistant.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class UserSettings(
    val lastProject: String? = null,
    val lastChatModel: String = "llama3.1",
    val lastEmbeddingModel: String = "llama3.1",
)

object SettingsStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val settingsDir: Path =
        Paths.get(System.getProperty("user.home"), ".ai-challenge-assistant")
    private val settingsFile: Path = settingsDir.resolve("state.json")

    fun load(): UserSettings {
        return runCatching {
            if (Files.exists(settingsFile)) {
                json.decodeFromString(Files.readString(settingsFile))
            } else {
                UserSettings()
            }
        }.getOrElse { UserSettings() }
    }

    fun update(
        projectPath: Path? = null,
        chatModel: String? = null,
        embeddingModel: String? = null,
    ) {
        val current = load()
        val next = current.copy(
            lastProject = projectPath?.toAbsolutePath()?.toString() ?: current.lastProject,
            lastChatModel = chatModel ?: current.lastChatModel,
            lastEmbeddingModel = embeddingModel ?: current.lastEmbeddingModel,
        )
        save(next)
    }

    private fun save(settings: UserSettings) {
        runCatching {
            settingsDir.createDirectories()
            Files.writeString(settingsFile, json.encodeToString(settings))
        }
    }
}
