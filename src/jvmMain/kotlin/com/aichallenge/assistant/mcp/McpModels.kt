package com.aichallenge.assistant.mcp

data class McpToolState(
    val id: String,
    val serverId: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
)

data class McpServerState(
    val id: String,
    val name: String,
    val description: String,
    val online: Boolean,
    val tools: List<McpToolState>,
)

data class McpToolSummary(
    val id: String,
    val serverName: String,
    val description: String,
)
