package com.aichallenge.assistant.mcp

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

data class McpGithubConfig(
    val token: String,
    val owner: String,
    val repo: String,
    val apiUrl: String,
)

data class McpGithubSettings(
    val token: String,
    val owner: String?,
    val repo: String?,
    val apiUrl: String?,
)

data class GithubWebhookSettings(
    val port: Int,
    val secret: String?,
)

object LocalPropertiesConfig {
    private val properties: Properties? by lazy { loadProperties() }

    fun githubSettings(): McpGithubSettings? {
        val props = properties
        val token = props?.valueOrNull("mcp.github.token") ?: envValue("MCP_GITHUB_TOKEN") ?: return null
        val owner = props?.valueOrNull("mcp.github.owner") ?: envValue("MCP_GITHUB_OWNER")
        val repo = props?.valueOrNull("mcp.github.repo") ?: envValue("MCP_GITHUB_REPO")
        val apiUrl = props?.valueOrNull("mcp.github.apiUrl") ?: envValue("MCP_GITHUB_API_URL")
        return McpGithubSettings(
            token = token,
            owner = owner,
            repo = repo,
            apiUrl = apiUrl,
        )
    }

    fun webhookSettings(): GithubWebhookSettings? {
        val portValue = properties?.valueOrNull("github.webhook.port") ?: envValue("GITHUB_WEBHOOK_PORT")
        val port = portValue?.toIntOrNull() ?: return null
        val secret = properties?.valueOrNull("github.webhook.secret") ?: envValue("GITHUB_WEBHOOK_SECRET")
        return GithubWebhookSettings(port = port, secret = secret)
    }

    private fun loadProperties(): Properties? {
        val path = Paths.get("local.properties")
        if (!Files.exists(path)) return null
        return runCatching {
            Files.newBufferedReader(path).use { reader ->
                Properties().apply { load(reader) }
            }
        }.getOrNull()
    }

    private fun Properties.valueOrNull(key: String): String? =
        getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    private fun envValue(key: String): String? =
        System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
}
