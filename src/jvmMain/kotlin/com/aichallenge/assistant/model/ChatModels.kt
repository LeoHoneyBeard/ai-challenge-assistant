package com.aichallenge.assistant.model

import java.time.Instant

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val timestamp: Instant = Instant.now(),
)

data class RagStatus(
    val sources: List<String>,
    val chunkCount: Int,
)

data class IngestResult(
    val ragStatus: RagStatus,
    val warnings: List<String>,
)
