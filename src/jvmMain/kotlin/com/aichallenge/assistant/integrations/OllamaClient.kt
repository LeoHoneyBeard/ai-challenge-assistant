package com.aichallenge.assistant.integrations

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OllamaClient(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = 120_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = false
                },
            )
        }
    },
) {

    suspend fun chat(
        baseUrl: String,
        model: String,
        messages: List<OllamaMessage>,
    ): Result<String> = runCatching {
        val payload = OllamaChatRequest(model = model, messages = messages, stream = false)
        val response = httpClient.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val chatResponse = parseResponse(response)

        when {
            !chatResponse.error.isNullOrBlank() -> error(chatResponse.error)
            !chatResponse.message?.content.isNullOrBlank() -> chatResponse.message!!.content
            !chatResponse.response.isNullOrBlank() -> chatResponse.response!!
            else -> "Model returned an empty payload."
        }
    }

    suspend fun embed(baseUrl: String, model: String, prompt: String): Result<List<Double>> = runCatching {
        val request = OllamaEmbeddingRequest(model = model, prompt = prompt)
        val response = httpClient.post("$baseUrl/api/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<OllamaEmbeddingResponse>()
        response.embedding ?: error("Embedding response was empty")
    }

    suspend fun listModels(baseUrl: String): Result<List<String>> = runCatching {
        val response = httpClient.get("$baseUrl/api/tags").body<OllamaTagsResponse>()
        response.models.mapNotNull { it.name }.sorted()
    }

    private suspend fun parseResponse(response: HttpResponse): OllamaChatResponse {
        val contentTypeHeader = response.headers[HttpHeaders.ContentType].orEmpty()
        return if (contentTypeHeader.contains("application/x-ndjson")) {
            val lines = response.bodyAsText()
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) {
                OllamaChatResponse(error = "Empty NDJSON response")
            } else {
                var lastRole: String? = null
                var lastResponse: String? = null
                var lastError: String? = null
                val builder = StringBuilder()
                lines.forEach { line ->
                    val chunk = runCatching { json.decodeFromString<OllamaChatResponse>(line) }.getOrNull()
                    if (chunk != null) {
                        chunk.message?.let { msg ->
                            if (!msg.role.isNullOrBlank()) {
                                lastRole = msg.role
                            }
                            builder.append(msg.content)
                        }
                        if (!chunk.response.isNullOrBlank()) {
                            lastResponse = chunk.response
                        }
                        if (!chunk.error.isNullOrBlank()) {
                            lastError = chunk.error
                        }
                    }
                }
                val aggregatedMessage = if (builder.isNotEmpty()) {
                    OllamaMessage(role = lastRole ?: "assistant", content = builder.toString())
                } else {
                    null
                }
                OllamaChatResponse(
                    message = aggregatedMessage,
                    response = lastResponse,
                    error = lastError,
                    done = true,
                )
            }
        } else {
            response.body()
        }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }
    }
}

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
)

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
)

@Serializable
data class OllamaChatResponse(
    val message: OllamaMessage? = null,
    val response: String? = null,
    val done: Boolean? = null,
    val error: String? = null,
)

@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val prompt: String,
)

@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Double>? = null,
)

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelTag> = emptyList(),
)

@Serializable
data class OllamaModelTag(
    val name: String? = null,
)
