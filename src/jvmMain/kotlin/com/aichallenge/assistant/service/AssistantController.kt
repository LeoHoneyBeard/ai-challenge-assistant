package com.aichallenge.assistant.service

import com.aichallenge.assistant.integrations.GithubRepoRef
import com.aichallenge.assistant.integrations.McpGitClient
import com.aichallenge.assistant.integrations.OllamaClient
import com.aichallenge.assistant.integrations.OllamaMessage
import com.aichallenge.assistant.mcp.McpServerState
import com.aichallenge.assistant.mcp.McpService
import com.aichallenge.assistant.model.IngestResult
import com.aichallenge.assistant.model.ChatMessage
import com.aichallenge.assistant.model.MessageRole
import com.aichallenge.assistant.model.ProjectTask
import com.aichallenge.assistant.model.PullRequestSummary
import com.aichallenge.assistant.model.RagStatus
import com.aichallenge.assistant.model.TaskDraft
import com.aichallenge.assistant.model.UserIssue
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
    private val userIssueRepository: UserIssueRepository = UserIssueRepository(),
    private val taskTrackerRepository: TaskTrackerRepository = TaskTrackerRepository(),
    private val mcpService: McpService = McpService(
        gitClient = mcpGitClient,
        userIssueRepository = userIssueRepository,
        taskTrackerRepository = taskTrackerRepository,
    ),
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
        requireTaskCreation: Boolean = false,
        history: List<ChatMessage> = emptyList(),
        extraSystemPrompt: String? = null,
    ): Result<String> {
        val isHelp = question.startsWith("/help", ignoreCase = true)
        log("ASK", "Incoming question (help=$isHelp): ${snippet(question)}")
        val contextBlocks = if (isHelp) {
            val retrievalQuery = question.removePrefix("/help").trim().ifBlank { question }
            log("OLLAMA", "Embedding request model=$embeddingModel prompt='${snippet(retrievalQuery)}'")
            val queryEmbeddingResult = ollamaClient.embed(baseUrl, embeddingModel, retrievalQuery)
            queryEmbeddingResult.onFailure { error ->
                log("OLLAMA", "Embedding failed: ${error.message}")
            }
            val queryEmbedding = queryEmbeddingResult.getOrElse { return Result.failure(it) }
            log("OLLAMA", "Embedding success: dim=${queryEmbedding.size}")

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

        val availableTools = mcpService.enabledTools(projectRoot)
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
            if (availableTools.isEmpty()) {
                appendLine("No MCP tools are currently approved by the user.")
            } else {
                appendLine("Available MCP tools (request them by responding with `MCP_REQUEST:tool_name` and, if arguments are necessary, add a newline followed by a JSON payload).")
                appendLine("Tool usage principles:")
                appendLine("1) Always gather required data via tools when it exists locally instead of asking the user to paste it (e.g., fetch user issues with `workspace-user-issues`).")
                appendLine("2) You may request multiple tools sequentially to complete a workflow (for example, read user issues and then create project tasks).")
                appendLine("3) When calling task-creation tools (`workspace-create-task` or `workspace-create-tasks-batch`), provide a full JSON payload such as {\"title\":\"...\",\"description\":\"...\",\"priority\":\"HIGH\",\"requirements\":[\"...\"]}. Use the batch tool when the user asks for multiple tasks in one message.")
                appendLine("4) If the user explicitly asks to add or update tasks, you MUST call one of the task-creation tools; do not respond with JSON only. Show the JSON, then immediately issue the MCP request so the task is persisted.")
                appendLine("5) Never claim a task exists unless the tool succeeded. If the tool fails, explain the failure and ask the user to correct the payload.")
                appendLine("After each tool result, continue reasoning; only send the final answer once you have satisfied the request without referencing MCP or intermediate steps.")
                availableTools.forEach { tool ->
                    appendLine("- ${tool.id} (${tool.serverName}): ${tool.description}")
                }
                appendLine("Only request a tool when it helps fulfill the instruction; once the tool returns data, incorporate it naturally into your answer.")
            }
        }

        val historyMessages = historyToOllama(history)
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

        val conversation = mutableListOf(OllamaMessage(role = "system", content = systemPrompt))
        conversation.addAll(historyMessages)
        conversation += userMessage

        var attempt = 0
        var pendingTaskCreation = requireTaskCreation
        while (attempt < MAX_TOOL_REQUEST_ATTEMPTS) {
            attempt++
            log("OLLAMA", "Chat request attempt=$attempt model=$chatModel messages=${conversation.size}")
            val response = ollamaClient.chat(baseUrl = baseUrl, model = chatModel, messages = conversation)
            response.onFailure {
                log("OLLAMA", "Chat failed on attempt=$attempt: ${it.message}")
                return response
            }
            val content = response.getOrThrow()
            log("OLLAMA", "Chat response attempt=$attempt: ${snippet(content)}")
            val requestedTool = parseToolRequest(content)
            if (requestedTool == null) {
                if (pendingTaskCreation) {
                    conversation += OllamaMessage(
                        role = "system",
                        content = "The user explicitly asked to create tasks. Call `workspace-create-task` with the JSON payload you prepared, wait for the tool result, and only then respond to the user.",
                    )
                    continue
                }
                return Result.success(content)
            } else {
                log("MCP", "Model requested tool '${requestedTool.id}' on attempt=$attempt")
                conversation += OllamaMessage(role = "assistant", content = content)
                val toolOutcome = runTool(requestedTool.id, requestedTool.payload, projectRoot)
                val toolResult = toolOutcome.getOrElse { it.message ?: "tool error" }
                log("MCP", "Tool '${requestedTool.id}' result: ${snippet(toolResult)}")
                conversation += OllamaMessage(
                    role = "system",
                    content = buildString {
                        appendLine("Tool '${requestedTool.id}' returned:")
                        appendLine(toolResult)
                        appendLine("Use this information to answer the user naturally without referencing tool invocations.")
                    },
                )
                if (toolOutcome.isSuccess && TASK_CREATION_TOOL_IDS.any { requestedTool.id.equals(it, ignoreCase = true) }) {
                    pendingTaskCreation = false
                }
            }
        }
        return Result.failure(IllegalStateException("Exceeded MCP tool request limit."))
    }

    suspend fun fetchBranch(root: Path?): Result<String> {
        val projectRoot = root ?: return Result.failure(IllegalStateException("Project path is not selected"))
        return mcpGitClient.fetchCurrentBranch(projectRoot)
    }

    suspend fun mcpServers(projectRoot: Path?): List<McpServerState> = mcpService.servers(projectRoot)

    suspend fun setMcpToolEnabled(toolId: String, enabled: Boolean, projectRoot: Path?): List<McpServerState> =
        mcpService.setToolEnabled(toolId, enabled, projectRoot)

    suspend fun listPullRequests(projectRoot: Path?): Result<List<PullRequestSummary>> =
        mcpService.listPullRequests(projectRoot)

    suspend fun reviewPullRequest(
        prNumber: Int,
        baseUrl: String,
        chatModel: String,
        embeddingModel: String,
        projectRoot: Path?,
    ): Result<String> {
        log("REVIEW", "Preparing review for PR #$prNumber")
        val bundle = mcpService.pullRequestReviewBundle(projectRoot, prNumber)
            .getOrElse { return Result.failure(it) }
        val contextQuery = buildString {
            appendLine("Pull request #${bundle.summary.number}: ${bundle.summary.title}")
            appendLine("Base branch: ${bundle.summary.baseBranch}, head: ${bundle.summary.headBranch}")
            appendLine("Summary: ${bundle.summary.body.takeIf { it.isNotBlank() } ?: "No description"}")
            appendLine("Changed files: ${bundle.files.joinToString(", ") { it.filename }}")
        }
        val ragContext = ragContextFromQuery(baseUrl, embeddingModel, contextQuery)
            .getOrElse { "RAG context unavailable (${it.message})" }
        val filesSummary = bundle.files.joinToString("\n") { file ->
            "- ${file.filename} (${file.status}, +${file.additions} -${file.deletions}, Δ${file.changes})"
        }
        val diffSnippet = limitText(bundle.diff, 120_000)
        val systemPrompt = buildString {
            appendLine("You are a senior software engineer performing an in-depth pull request review.")
            appendLine("Identify correctness issues, security concerns, style problems, missing tests, and offer actionable suggestions.")
            appendLine("Write the final review in Russian language, even if the diff/comments are in another language.")
            appendLine("Project context snippets:")
            appendLine(ragContext)
        }
        val userPrompt = buildString {
            appendLine("Review pull request #${bundle.summary.number}: ${bundle.summary.title}")
            appendLine("Author: ${bundle.summary.author} (${bundle.summary.headBranch} -> ${bundle.summary.baseBranch})")
            appendLine("Pull request URL: ${bundle.summary.url}")
            appendLine()
            appendLine("Pull request description:")
            appendLine(bundle.summary.body.ifBlank { "No description provided." })
            appendLine()
            appendLine("Changed files:")
            appendLine(filesSummary.ifBlank { "No file data." })
            appendLine()
            appendLine("Unified diff (truncated if necessary):")
            appendLine("```diff")
            appendLine(diffSnippet)
            appendLine("```")
            appendLine()
            appendLine("Return a structured review with headings for Findings, Potential Bugs, and Recommendations.")
        }
        val messages = listOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userPrompt),
        )
        log("REVIEW", "Sending review request for PR #$prNumber (diff length=${diffSnippet.length})")
        val response = ollamaClient.chat(baseUrl = baseUrl, model = chatModel, messages = messages)
        response.onSuccess { log("REVIEW", "Review completed for PR #$prNumber") }
        response.onFailure { log("REVIEW", "Review failed for PR #$prNumber: ${it.message}") }
        return response
    }

    suspend fun loadUserIssues(projectRoot: Path?): Result<List<UserIssue>> =
        userIssueRepository.loadIssues(projectRoot)

    suspend fun loadTasks(projectRoot: Path?): Result<List<ProjectTask>> =
        taskTrackerRepository.loadTasks(projectRoot)

    suspend fun createTask(projectRoot: Path?, draft: TaskDraft): Result<ProjectTask> =
        taskTrackerRepository.createTask(projectRoot, draft)

    suspend fun createTasks(projectRoot: Path?, drafts: List<TaskDraft>): Result<List<ProjectTask>> =
        taskTrackerRepository.createTasks(projectRoot, drafts)

    suspend fun deleteTask(projectRoot: Path?, taskId: String): Result<ProjectTask> =
        taskTrackerRepository.deleteTask(projectRoot, taskId)

    suspend fun proposeIssueSolution(
        issue: UserIssue,
        baseUrl: String,
        chatModel: String,
        embeddingModel: String,
        projectRoot: Path?,
    ): Result<String> {
        val query = buildString {
            appendLine(issue.issue.subject)
            appendLine(issue.issue.description)
        }.trim()
        val ragContext = ragContextFromQuery(baseUrl, embeddingModel, query)
            .getOrElse { error ->
                "RAG context unavailable: ${error.message}"
            }
        val systemPrompt = buildString {
            appendLine("Ты — инженер поддержки AI Challenge Assistant.")
            appendLine("Объясняй поведение и предлагай шаги решения так, будто отвечаешь пользователю — без глубоких технических деталей реализации.")
            appendLine("Опирайся на FAQ, README, docs и исходники, но пересказывай выводы простыми словами. Всегда отвечай по-русски.")
            appendLine("Контекст из поискового запроса по базе знаний:")
            appendLine(ragContext)
        }
        val userPrompt = buildString {
            appendLine("Пользователь ${issue.userName} отправил обращение.")
            appendLine("ID: ${issue.issue.issueId}, номер: ${issue.issue.issueNumber}")
            appendLine("Тема: ${issue.issue.subject}")
            appendLine("Описание: ${issue.issue.description}")
            appendLine()
            appendLine("Предложи решение или объясни текущее поведение продукта простыми словами. Если нужно, предложи шаги диагностики или workaround.")
            appendLine("Не углубляйся в детали реализации кода; сосредоточься на действиях пользователя и ожидаемом эффекте.")
        }
        val messages = listOf(
            OllamaMessage(role = "system", content = systemPrompt),
            OllamaMessage(role = "user", content = userPrompt),
        )
        return ollamaClient.chat(baseUrl = baseUrl, model = chatModel, messages = messages)
    }

    suspend fun detectRepository(projectRoot: Path?): Result<GithubRepoRef> {
        val root = projectRoot ?: return Result.failure(IllegalStateException("Project path is not selected"))
        return mcpGitClient.detectGithubRepo(root).mapCatching { repo ->
            repo ?: error("Unable to detect git remote for the selected project.")
        }
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

    private fun parseToolRequest(response: String): ToolRequest? {
        val marker = "MCP_REQUEST:"
        val index = response.lastIndexOf(marker)
        if (index < 0) return null
        val remainderRaw = response.substring(index + marker.length)
        val remainder = remainderRaw.trimStart()
        if (remainder.isEmpty()) return null
        val newlineIndex = remainder.indexOf('\n')
        val firstLine = if (newlineIndex >= 0) remainder.substring(0, newlineIndex) else remainder
        if (firstLine.isBlank()) return null
        val inlineParts = firstLine.split(" ", limit = 2)
        val toolId = inlineParts.first().trim()
        if (toolId.isEmpty()) return null
        val inlinePayload = inlineParts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val blockPayload = if (newlineIndex >= 0) {
            remainder.substring(newlineIndex + 1).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
        val priorContext = response.substring(0, index)
        val fencedPayload = extractLastCodeBlock(priorContext)
        val payload = inlinePayload ?: blockPayload ?: fencedPayload
        return ToolRequest(toolId, payload)
    }

    private data class ToolRequest(
        val id: String,
        val payload: String?,
    )

    private suspend fun runTool(toolName: String, payload: String?, projectRoot: Path?): Result<String> {
        log("MCP", "Executing tool '$toolName' with payload=${payload != null}")
        val result = mcpService.runTool(toolName, payload, projectRoot)
        result.onSuccess { log("MCP", "Tool '$toolName' succeeded: ${snippet(it)}") }
        result.onFailure { log("MCP", "Tool '$toolName' failed: ${it.message}") }
        return result
    }

    private fun historyToOllama(history: List<ChatMessage>): List<OllamaMessage> =
        history.takeLast(MAX_HISTORY_MESSAGES).mapNotNull { message ->
            val content = message.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            when (message.role) {
                MessageRole.USER -> OllamaMessage(role = "user", content = content)
                MessageRole.ASSISTANT -> OllamaMessage(role = "assistant", content = content)
                MessageRole.SYSTEM -> OllamaMessage(role = "system", content = content)
            }
        }

    private fun extractLastCodeBlock(text: String): String? {
        val matches = fencedCodeBlockRegex.findAll(text).toList()
        if (matches.isEmpty()) return null
        val jsonBlock = matches.lastOrNull { it.groups[1]?.value.equals("json", ignoreCase = true) }
        val target = jsonBlock ?: matches.last()
        return target.groups[2]?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun embedChunk(baseUrl: String, model: String, chunk: TextChunk): Result<List<Double>> {
        val enriched = "Source: ${chunk.source}\n${chunk.content}"
        log("OLLAMA", "Embedding chunk source='${chunk.source}' model=$model")
        val result = ollamaClient.embed(baseUrl, model, enriched)
        result.onSuccess { log("OLLAMA", "Chunk embedding success for '${chunk.source}' dim=${it.size}") }
        result.onFailure { log("OLLAMA", "Chunk embedding failed for '${chunk.source}': ${it.message}") }
        return result
    }

    private suspend fun ragContextFromQuery(baseUrl: String, model: String, query: String): Result<String> {
        log("RAG", "Generating context for query '${snippet(query)}'")
        val embeddingResult = ollamaClient.embed(baseUrl, model, query)
        embeddingResult.onFailure { log("RAG", "Embedding for PR context failed: ${it.message}") }
        val embedding = embeddingResult.getOrElse { return Result.failure(it) }
        val matches = knowledgeBase.search(embedding, topK = 8)
        if (matches.isEmpty()) {
            log("RAG", "No RAG matches for query.")
            return Result.success("No relevant context found in the knowledge base.")
        }
        val context = matches.joinToString("\n---\n") { chunk ->
            "[${chunk.source}]\n${chunk.content}"
        }
        return Result.success(context)
    }
}

private const val MAX_TOOL_REQUEST_ATTEMPTS = 6
private const val MAX_HISTORY_MESSAGES = 10
private val TASK_CREATION_TOOL_IDS = setOf("workspace-create-task", "workspace-create-tasks-batch")
private val fencedCodeBlockRegex = Regex("```(?:([a-zA-Z0-9_-]+))?\\s*([\\s\\S]*?)```", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

private fun log(tag: String, message: String) {
    println("[Assistant][$tag] $message")
}

private fun snippet(text: String, max: Int = 160): String {
    val sanitized = text.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
    return if (sanitized.length <= max) sanitized else sanitized.take(max) + "..."
}

private fun limitText(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "\n... (diff truncated)"

private val wordRegex = Regex("[\\p{L}\\p{Nd}]+")
