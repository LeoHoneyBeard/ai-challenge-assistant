package com.aichallenge.assistant.ci

import com.aichallenge.assistant.service.AssistantController
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths

fun main(): Unit = runBlocking {
    val baseUrl = envOrDefault("OLLAMA_BASE_URL", "http://localhost:11434")
    val chatModel = envOrDefault("OLLAMA_CHAT_MODEL", "llama3.1")
    val embeddingModel = envOrDefault("OLLAMA_EMBED_MODEL", chatModel)
    val projectRoot = Paths.get(envOrDefault("PROJECT_ROOT", ".")).toAbsolutePath().normalize()
    val prNumber = env("PR_NUMBER")?.toIntOrNull()
        ?: error("PR_NUMBER environment variable is required for automated review.")

    require(Files.exists(projectRoot)) { "Project root $projectRoot does not exist." }

    val controller = AssistantController()
    println("::group::RAG ingestion")
    val ingestResult = controller.ingest(projectRoot, baseUrl, embeddingModel)
    println("Indexed ${ingestResult.ragStatus.chunkCount} chunks from ${ingestResult.ragStatus.sources.size} sources.")
    if (ingestResult.warnings.isNotEmpty()) {
        println("Warnings:")
        ingestResult.warnings.forEach { println("- $it") }
    }
    println("::endgroup::")

    println("::group::Pull request review")
    val review = controller.reviewPullRequest(
        prNumber = prNumber,
        baseUrl = baseUrl,
        chatModel = chatModel,
        embeddingModel = embeddingModel,
        projectRoot = projectRoot,
    ).getOrElse { error("PR review failed: ${it.message}") }
    println(review)
    println("::endgroup::")
}

private fun env(key: String): String? = System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }

private fun envOrDefault(key: String, default: String): String = env(key) ?: default
