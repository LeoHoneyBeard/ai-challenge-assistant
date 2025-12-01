package com.aichallenge.assistant.service

import com.aichallenge.assistant.integrations.McpGitClient
import com.aichallenge.assistant.integrations.OllamaClient
import com.aichallenge.assistant.integrations.OllamaMessage
import com.aichallenge.assistant.model.IngestResult
import com.aichallenge.assistant.model.RagStatus
import com.aichallenge.assistant.rag.DocumentLoader
import com.aichallenge.assistant.rag.KnowledgeBase
import com.aichallenge.assistant.rag.KnowledgeChunk
import com.aichallenge.assistant.rag.TextChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

class AssistantController(
    private val knowledgeBase: KnowledgeBase = KnowledgeBase(),
    private val documentLoader: DocumentLoader = DocumentLoader(),
    private val ollamaClient: OllamaClient = OllamaClient(),
    private val projectStructureAnalyzer: ProjectStructureAnalyzer = ProjectStructureAnalyzer(),
    private val mcpGitClient: McpGitClient = McpGitClient(),
) {

    suspend fun ingest(path: Path, baseUrl: String, embeddingModel: String): IngestResult = withContext(Dispatchers.IO) {
        val loadResult = documentLoader.loadSources(path)
        val warnings = loadResult.skipped.toMutableList()
        val embeddedChunks = mutableListOf<KnowledgeChunk>()

        loadResult.chunks.forEach { chunk ->
            val embeddingResult = embedChunk(baseUrl, embeddingModel, chunk)
            embeddingResult.onSuccess { vector ->
                embeddedChunks += KnowledgeChunk(
                    source = chunk.source,
                    content = chunk.content,
                    embedding = vector,
                )
            }.onFailure { error ->
                warnings += "Embedding failed for ${'$'}{chunk.source}: ${'$'}{error.message}"
            }
        }

        knowledgeBase.replaceAll(embeddedChunks)

        val ragStatus = RagStatus(
            sources = knowledgeBase.sources(),
            chunkCount = embeddedChunks.size,
        )

        IngestResult(
            ragStatus = ragStatus,
            warnings = warnings,
        )
    }

    suspend fun listModels(baseUrl: String): Result<List<String>> = ollamaClient.listModels(baseUrl)

    suspend fun ask(
        question: String,
        chatModel: String,
        embeddingModel: String,
        baseUrl: String,
        gitBranch: String?,
        projectRoot: Path?,
        extraSystemPrompt: String? = null,
    ): Result<String> {
        val isHelp = question.startsWith("/help", ignoreCase = true)
        val contextBlocks = if (isHelp) {
            val retrievalQuery = question.removePrefix("/help").trim().ifBlank { question }
            val queryEmbeddingResult = ollamaClient.embed(baseUrl, embeddingModel, retrievalQuery)
            val queryEmbedding = queryEmbeddingResult.getOrElse { return Result.failure(it) }

        val retrievedChunks = knowledgeBase.search(queryEmbedding, topK = 8)
        val keywords = extractKeywords(retrievalQuery)
        val rerankedChunks = if (retrievedChunks.isNotEmpty() && keywords.isNotEmpty()) {
            retrievedChunks.sortedByDescending { chunk -> keywordScore(chunk, keywords) }
        } else {
            retrievedChunks
        }
            val bestChunks = rerankedChunks.take(4)
            val blocks = bestChunks.takeIf { it.isNotEmpty() }
                ?.joinToString("\n---\n") { chunk -> "[${chunk.source}]\n${chunk.content}" }
                ?: "RAG context is empty. Answer using your general knowledge."
            println("[RAG] Query='$question'\n$blocks\n")
            blocks
        } else {
            "RAG context not provided (use /help to request project-specific information)."
        }

        val systemPrompt = buildString {
            appendLine("You are an engineering assistant. Help the user understand the project.")
            if (!gitBranch.isNullOrBlank()) {
                appendLine("Current git branch: $gitBranch")
            }
            if (!extraSystemPrompt.isNullOrBlank()) {
                appendLine(extraSystemPrompt)
            }
            appendLine("Context snippets:")
            appendLine(contextBlocks)
            appendLine()
            appendLine("Available MCP tools (request by responding with `MCP_REQUEST:tool_name` only):")
            appendLine("- git-branch: returns the result of `git rev-parse --abbrev-ref HEAD` for the selected project.")
            appendLine("If you request a tool, wait for `MCP_RESPONSE:tool_name -> data` before answering the user.")
        }

        val userMessage = if (isHelp) {
            val payload = question.removePrefix("/help").trim()
            val messageContent = buildString {
                append("PROJECT_CONTEXT_QUESTION: The user is asking specifically about the currently selected project. ")
                append("Rely on the RAG context and MCP responses to reason about project structure, files, and conventions. ")
                if (payload.isNotBlank()) {
                    append("User request: $payload")
                } else {
                    append("Provide a concise overview and structure summary of the project.")
                }
            }
            OllamaMessage(role = "user", content = messageContent)
        } else {
            OllamaMessage(role = "user", content = question)
        }

        val conversation = mutableListOf(
            OllamaMessage(role = "system", content = systemPrompt),
            userMessage,
        )

        repeat(3) {
            val response = ollamaClient.chat(baseUrl = baseUrl, model = chatModel, messages = conversation)
            response.onFailure { return response }
            val content = response.getOrThrow()
            val requestedTool = parseToolRequest(content)
            if (requestedTool == null) {
                return Result.success(content)
            } else {
                conversation += OllamaMessage(role = "assistant", content = content)
                val toolResult = runTool(requestedTool, projectRoot).getOrElse { it.message ?: "tool error" }
                conversation += OllamaMessage(
                    role = "system",
                    content = "MCP_RESPONSE:$requestedTool -> $toolResult. Continue answering the user with this information.",
                )
            }
        }
        return Result.failure(IllegalStateException("Exceeded MCP tool request limit."))
    }

    suspend fun fetchBranch(root: Path?): Result<String> {
        val projectRoot = root ?: return Result.failure(IllegalStateException("Project path is not selected"))
        return mcpGitClient.fetchCurrentBranch(projectRoot)
    }

    fun describeProject(root: Path, question: String?): String =
        projectStructureAnalyzer.describe(root, knowledgeBase.sources(), question)

    fun knowledgeSources(): List<String> = knowledgeBase.sources()

    private fun extractKeywords(text: String): Set<String> =
        wordRegex.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length > 2 }
            .toSet()

    private fun keywordScore(chunk: KnowledgeChunk, keywords: Set<String>): Int {
        if (keywords.isEmpty()) return 0
        val contentLower = chunk.content.lowercase()
        val sourceLower = chunk.source.lowercase()
        var score = 0
        keywords.forEach { key ->
            var index = contentLower.indexOf(key)
            while (index >= 0) {
                score++
                index = contentLower.indexOf(key, index + key.length)
            }
            if (sourceLower.contains(key)) {
                score += 2
            }
        }
        return score
    }

    private fun parseToolRequest(response: String): String? {
        val marker = "MCP_REQUEST:"
        return response.trim().takeIf { it.startsWith(marker) }?.substring(marker.length)?.trim()
    }

    private suspend fun runTool(toolName: String, projectRoot: Path?): Result<String> = when (toolName.lowercase()) {
        "git-branch" -> {
            if (projectRoot == null) {
                Result.failure(IllegalStateException("Project path is not selected"))
            } else {
                fetchBranch(projectRoot)
            }
        }
        else -> Result.failure(IllegalArgumentException("Unknown MCP tool: ${'$'}toolName"))
    }

    private suspend fun embedChunk(baseUrl: String, model: String, chunk: TextChunk): Result<List<Double>> {
        val enriched = "Source: ${chunk.source}\n${chunk.content}"
        return ollamaClient.embed(baseUrl, model, enriched)
    }
}

private val wordRegex = Regex("[\\p{L}\\p{Nd}]+")
