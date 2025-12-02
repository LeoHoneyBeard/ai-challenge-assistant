package com.aichallenge.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import com.aichallenge.assistant.integrations.GithubRepoRef
import com.aichallenge.assistant.integrations.GithubWebhookServer
import com.aichallenge.assistant.mcp.LocalPropertiesConfig
import com.aichallenge.assistant.mcp.McpServerState
import com.aichallenge.assistant.model.ChatMessage
import com.aichallenge.assistant.model.MessageRole
import com.aichallenge.assistant.model.PullRequestSummary
import com.aichallenge.assistant.model.RagStatus
import com.aichallenge.assistant.service.AssistantController
import com.aichallenge.assistant.service.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

fun main() {
    try {
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
        System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8))
    } catch (_: Exception) {
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "AI Challenge Assistant") {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AssistantScreen()
                }
            }
        }
    }
}

@Composable
private fun AssistantScreen() {
    val controller = remember { AssistantController() }
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    val initialSettings = remember { SettingsStore.load() }
    val initialPath = remember(initialSettings) {
        initialSettings.lastProject?.let { runCatching { Paths.get(it) }.getOrNull() }
            ?.takeIf { Files.exists(it) }
    }
    val initialChatModelValue = initialSettings.lastChatModel.ifBlank { "llama3.1" }
    val initialEmbeddingModelValue = initialSettings.lastEmbeddingModel.ifBlank { initialChatModelValue }
    var userInput by remember { mutableStateOf("") }
    var selectedSource by remember { mutableStateOf<Path?>(initialPath) }
    var projectPath by remember {
        mutableStateOf(
            initialPath?.let { if (Files.isDirectory(it)) it else it.parent },
        )
    }
    var ragStatus by remember { mutableStateOf(RagStatus(emptyList(), 0)) }
    var baseUrl by remember { mutableStateOf("http://localhost:11434") }
    var selectedChatModel by remember { mutableStateOf(initialChatModelValue) }
    var selectedEmbeddingModel by remember { mutableStateOf(initialEmbeddingModelValue) }
    val availableModels = remember { mutableStateListOf<String>() }
    var status by remember { mutableStateOf("Choose a README or project folder and press \"Select & ingest\".") }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }
    val mcpServers = remember { mutableStateListOf<McpServerState>() }
    var selectedMcpServerId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(AssistantTab.CHAT) }
    val pullRequests = remember { mutableStateListOf<PullRequestSummary>() }
    var prStatus by remember { mutableStateOf("Pull requests are not loaded.") }
    var isLoadingPrs by remember { mutableStateOf(false) }
    var isReviewingPr by remember { mutableStateOf(false) }
    var lastReviewedPr by remember { mutableStateOf<PullRequestSummary?>(null) }
    var lastReviewText by remember { mutableStateOf<String?>(null) }
    var autoReviewEnabled by remember { mutableStateOf(false) }
    var autoReviewStatus by remember { mutableStateOf("Auto review is disabled.") }
    val autoReviewMarks = remember { mutableStateMapOf<Int, String>() }
    var webhookEnabled by remember { mutableStateOf(false) }
    var webhookStatus by remember { mutableStateOf("Webhook listener disabled.") }
    val webhookServerState = remember { mutableStateOf<GithubWebhookServer?>(null) }
    var currentRepoRef by remember { mutableStateOf<GithubRepoRef?>(null) }

    fun updateMcpServers(servers: List<McpServerState>) {
        mcpServers.clear()
        mcpServers.addAll(servers)
        if (servers.isEmpty()) {
            selectedMcpServerId = null
        } else if (selectedMcpServerId == null || servers.none { it.id == selectedMcpServerId }) {
            selectedMcpServerId = servers.first().id
        }
    }

    suspend fun loadMcpServers() {
        val result = runCatching { controller.mcpServers(projectPath) }
        result.onSuccess { servers ->
            updateMcpServers(servers)
        }.onFailure { error ->
            status = "Failed to load MCP servers: ${error.message}"
        }
    }

    fun refreshMcpServers() {
        scope.launch {
            loadMcpServers()
        }
    }

    suspend fun loadPullRequests() {
        if (projectPath == null) {
            pullRequests.clear()
            prStatus = "Select a project to load pull requests."
            return
        }
        isLoadingPrs = true
        val result = controller.listPullRequests(projectPath)
        result.onSuccess { prs ->
            pullRequests.clear()
            pullRequests.addAll(prs)
            prStatus = "Loaded ${prs.size} pull request(s)."
        }.onFailure { error ->
            prStatus = "Failed to load PRs: ${error.message}"
        }
        isLoadingPrs = false
    }

    fun refreshPullRequests() {
        scope.launch {
            loadPullRequests()
        }
    }

    suspend fun performPullRequestReview(pr: PullRequestSummary, updateStatus: Boolean = false): Result<String> {
        if (!isReviewingPr) {
            isReviewingPr = true
        }
        val result = controller.reviewPullRequest(
            prNumber = pr.number,
            baseUrl = baseUrl,
            chatModel = selectedChatModel,
            embeddingModel = selectedEmbeddingModel,
            projectRoot = projectPath,
        )
        result.onSuccess { review ->
            lastReviewedPr = pr
            lastReviewText = review
            autoReviewMarks[pr.number] = pr.updatedAt
        }.onFailure { error ->
            lastReviewedPr = pr
            lastReviewText = "Review failed: ${error.message}"
        }
        if (updateStatus) {
            result.onSuccess {
                status = "Review ready for PR #${pr.number}"
            }.onFailure { error ->
                status = "Review failed: ${error.message}"
            }
        }
        isReviewingPr = false
        return result
    }

    fun runPullRequestReview(pr: PullRequestSummary) {
        scope.launch {
            if (projectPath == null) {
                status = "Select a project to review pull requests."
                return@launch
            }
            isReviewingPr = true
            status = "Reviewing PR #${pr.number}..."
            performPullRequestReview(pr, updateStatus = true)
        }
    }

    fun choosePath(): Path? {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        }
        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

    fun ingestPath(path: Path) {
        selectedSource = path
        projectPath = if (Files.isDirectory(path)) path else path.parent
        scope.launch {
            isBusy = true
            status = "Indexing documents..."
            val result = runCatching { controller.ingest(path, baseUrl, selectedEmbeddingModel) }
            result.onSuccess { ingestResult ->
                ragStatus = ingestResult.ragStatus
                warnings = ingestResult.warnings
                status = "RAG ready: ${ingestResult.ragStatus.chunkCount} chunks from ${ingestResult.ragStatus.sources.size} sources."
                SettingsStore.update(
                    projectPath = selectedSource,
                    chatModel = selectedChatModel,
                    embeddingModel = selectedEmbeddingModel,
                )
            }.onFailure { error ->
                status = "Load failed: ${error.message}"
            }
            isBusy = false
        }
    }
    
    fun sendMessage() {
        sendCurrentMessage(
            scope = scope,
            userInput = userInput,
            setUserInput = { userInput = it },
            messages = messages,
            controller = controller,
            chatModel = selectedChatModel,
            embeddingModel = selectedEmbeddingModel,
            baseUrl = baseUrl,
            projectPath = projectPath,
            setStatus = { status = it },
            setBusy = { isBusy = it },
        )
    }
    
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(320.dp).fillMaxHeight().padding(end = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Project source", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    choosePath()?.let { ingestPath(it) }
                }) {
                    Text("Select & ingest")
                }
                Spacer(Modifier.height(4.dp))
                Text("Selected path: ${selectedSource?.toString() ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Ollama URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ModelDropdown(
                    label = "Chat model",
                    value = selectedChatModel,
                    options = availableModels,
                    onSelect = { selectedChatModel = it },
                )
                Spacer(Modifier.height(8.dp))
                ModelDropdown(
                    label = "Embedding model",
                    value = selectedEmbeddingModel,
                    options = availableModels,
                    onSelect = { selectedEmbeddingModel = it },
                )
                Spacer(Modifier.height(16.dp))
                Text("RAG sources (${ragStatus.sources.size})", style = MaterialTheme.typography.titleSmall)
                if (ragStatus.sources.isEmpty()) {
                    Text("Sources are not loaded", style = MaterialTheme.typography.bodySmall)
                } else {
                    ragStatus.sources.take(10).forEach { source ->
                        Text("- $source", style = MaterialTheme.typography.bodySmall)
                    }
                    if (ragStatus.sources.size > 10) {
                        Text("... and ${ragStatus.sources.size - 10} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (warnings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Warnings:", color = MaterialTheme.colorScheme.error)
                    warnings.forEach { Text("- $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                if (isBusy) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f).fillMaxHeight()) {
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    AssistantTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.title) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (selectedTab) {
                    AssistantTab.CHAT -> {
                        ChatTabContent(
                            messages = messages,
                            listState = listState,
                            userInput = userInput,
                            onUserInputChange = { userInput = it },
                            onSend = { sendMessage() },
                            onClear = {
                                messages.clear()
                                status = "History cleared"
                            },
                        )
                    }
                    AssistantTab.MCP -> {
                        McpTabContent(
                            servers = mcpServers,
                            selectedServerId = selectedMcpServerId,
                            onSelectServer = { selectedMcpServerId = it },
                            onToggleTool = { toolId, enabled ->
                                scope.launch {
                                    val updated = controller.setMcpToolEnabled(toolId, enabled, projectPath)
                                    updateMcpServers(updated)
                                }
                            },
                            onReloadServers = { refreshMcpServers() },
                        )
                    }
                    AssistantTab.PRS -> {
                        PullRequestTabContent(
                            pullRequests = pullRequests,
                            status = prStatus,
                            isLoading = isLoadingPrs,
                            isReviewing = isReviewingPr,
                            reviewedPr = lastReviewedPr,
                            reviewText = lastReviewText,
                            autoReviewEnabled = autoReviewEnabled,
                            autoReviewStatus = autoReviewStatus,
                            webhookEnabled = webhookEnabled,
                            webhookStatus = webhookStatus,
                            onToggleAutoReview = { enabled ->
                                autoReviewEnabled = enabled
                                if (!enabled) {
                                    autoReviewStatus = "Auto review is disabled."
                                }
                            },
                            onToggleWebhook = { enabled ->
                                webhookEnabled = enabled
                                if (!enabled) {
                                    webhookStatus = "Webhook listener disabled."
                                }
                            },
                            onRefresh = { refreshPullRequests() },
                            onReview = { pr -> runPullRequestReview(pr) },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(projectPath) {
        loadMcpServers()
    }

    LaunchedEffect(projectPath) {
        loadPullRequests()
    }

    LaunchedEffect(autoReviewEnabled, projectPath, baseUrl, selectedChatModel, selectedEmbeddingModel) {
        if (!autoReviewEnabled) {
            autoReviewStatus = "Auto review is disabled."
            return@LaunchedEffect
        }
        val root = projectPath
        if (root == null) {
            autoReviewStatus = "Select a project to enable auto review."
            return@LaunchedEffect
        }
        autoReviewStatus = "Auto review is active."
        while (autoReviewEnabled && projectPath != null) {
            autoReviewStatus = "Checking pull requests..."
            val result = controller.listPullRequests(projectPath)
            result.onSuccess { prs ->
                val next = prs.firstOrNull { pr ->
                    val marker = autoReviewMarks[pr.number]
                    marker == null || marker != pr.updatedAt
                }
                if (next != null) {
                    autoReviewStatus = "Reviewing PR #${next.number}..."
                    val reviewResult = performPullRequestReview(next)
                    reviewResult.onSuccess {
                        autoReviewStatus = "Auto-reviewed PR #${next.number}"
                    }.onFailure { error ->
                        autoReviewStatus = "Auto review failed: ${error.message}"
                    }
                } else {
                    autoReviewStatus = "Auto review idle (no changes)."
                }
            }.onFailure { error ->
                autoReviewStatus = "Auto review failed to load PRs: ${error.message}"
            }
            delay(60_000)
        }
    }

    LaunchedEffect(webhookEnabled, projectPath, currentRepoRef) {
        if (!webhookEnabled) {
            webhookServerState.value?.stop()
            webhookServerState.value = null
            webhookStatus = "Webhook listener disabled."
            return@LaunchedEffect
        }
        val settings = LocalPropertiesConfig.webhookSettings()
        if (settings == null) {
            webhookStatus = "Webhook settings are missing. Define github.webhook.port and github.webhook.secret."
            webhookEnabled = false
            return@LaunchedEffect
        }
        try {
            val server = GithubWebhookServer(
                port = settings.port,
                secret = settings.secret,
                scope = scope,
            ) { event ->
                val repo = currentRepoRef
                if (repo == null) {
                    webhookStatus = "Webhook ignored: repository not detected for current project."
                    return@GithubWebhookServer
                }
                if (!(repo.owner.equals(event.owner, ignoreCase = true) && repo.repo.equals(event.repo, ignoreCase = true))) {
                    webhookStatus = "Webhook ignored: event for ${event.owner}/${event.repo}."
                    return@GithubWebhookServer
                }
                if (projectPath == null) {
                    webhookStatus = "Webhook ignored: project path not selected."
                    return@GithubWebhookServer
                }
                scope.launch {
                    webhookStatus = "Webhook: reviewing PR #${event.number}"
                    loadPullRequests()
                    val pr = pullRequests.firstOrNull { it.number == event.number }
                    if (pr == null) {
                        webhookStatus = "Webhook: PR #${event.number} not found after refresh."
                    } else {
                        val result = performPullRequestReview(pr, updateStatus = false)
                        webhookStatus = if (result.isSuccess) {
                            "Webhook: review ready for PR #${pr.number}"
                        } else {
                            "Webhook: review failed - ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
            }
            server.start()
            webhookServerState.value = server
            webhookStatus = "Listening for GitHub App webhooks on port ${settings.port}."
        } catch (error: Exception) {
            webhookStatus = "Failed to start webhook server: ${error.message}"
            webhookEnabled = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webhookServerState.value?.stop()
        }
    }

    LaunchedEffect(projectPath) {
        if (projectPath == null) {
            currentRepoRef = null
        } else {
            val result = controller.detectRepository(projectPath)
            currentRepoRef = result.getOrNull()
        }
    }

    LaunchedEffect(initialSettings.lastProject) {
        val cachedPath = initialSettings.lastProject?.let { runCatching { Paths.get(it) }.getOrNull() }
        if (cachedPath != null && Files.exists(cachedPath)) {
            ingestPath(cachedPath)
        }
    }

    LaunchedEffect(selectedChatModel) {
        SettingsStore.update(chatModel = selectedChatModel)
    }

    LaunchedEffect(selectedEmbeddingModel) {
        SettingsStore.update(embeddingModel = selectedEmbeddingModel)
    }

    LaunchedEffect(baseUrl) {
        val result = controller.listModels(baseUrl)
        result.onSuccess { models ->
            availableModels.clear()
            availableModels.addAll(models)
            if (models.isNotEmpty()) {
                if (selectedChatModel !in models) {
                    selectedChatModel = models.first()
                }
                if (selectedEmbeddingModel !in models) {
                    selectedEmbeddingModel = models.first()
                }
            }
        }.onFailure { error ->
            status = "Failed to load models: ${error.message}"
            availableModels.clear()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == MessageRole.USER
    val bubbleColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.onSurfaceVariant
        MessageRole.SYSTEM -> MaterialTheme.colorScheme.onErrorContainer
    }
    val label = when (message.role) {
        MessageRole.USER -> "You"
        MessageRole.ASSISTANT -> "Assistant"
        MessageRole.SYSTEM -> "System"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .clickable {
                    clipboard.setText(AnnotatedString(message.text))
                },
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
            ),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = textColor)
                Spacer(Modifier.height(4.dp))
                Text(message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MessageInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Type a message (/help for structure)") },
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                    onSubmit()
                    true
                } else {
                    false
                }
            },
    )
}

@Composable
private fun ModelDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Box {
            Button(
                onClick = { expanded = true },
                enabled = options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value.ifBlank { if (options.isEmpty()) "No models available" else "Select" })
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onSelect(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTabContent(
    messages: List<ChatMessage>,
    listState: LazyListState,
    userInput: String,
    onUserInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(messages) { message ->
                    ChatBubble(message)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MessageInputField(
            value = userInput,
            onValueChange = onUserInputChange,
            onSubmit = onSend,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSend) {
                Text("Send")
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onClear) {
                Text("Clear chat")
            }
        }
    }
}

@Composable
private fun McpTabContent(
    servers: List<McpServerState>,
    selectedServerId: String?,
    onSelectServer: (String) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onReloadServers: () -> Unit,
) {
    if (servers.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No MCP servers detected. Configure GitHub credentials in local.properties and reload.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onReloadServers) {
                Text("Reload")
            }
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(220.dp).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            servers.forEach { server ->
                val isSelected = server.id == selectedServerId
                val cardColors = if (isSelected) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clickable { onSelectServer(server.id) },
                    colors = cardColors,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(server.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(server.description, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (server.online) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (server.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        val currentServer = servers.firstOrNull { it.id == selectedServerId } ?: servers.first()
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(currentServer.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReloadServers) {
                    Text("Reload")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(currentServer.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (currentServer.tools.isEmpty()) {
                Text("Server does not expose any tools.", style = MaterialTheme.typography.bodyMedium)
            } else {
                currentServer.tools.forEach { tool ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.label, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(tool.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = tool.enabled,
                                onCheckedChange = { checked -> onToggleTool(tool.id, checked) },
                                enabled = currentServer.online,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PullRequestTabContent(
    pullRequests: List<PullRequestSummary>,
    status: String,
    isLoading: Boolean,
    isReviewing: Boolean,
    reviewedPr: PullRequestSummary?,
    reviewText: String?,
    autoReviewEnabled: Boolean,
    autoReviewStatus: String,
    webhookEnabled: Boolean,
    webhookStatus: String,
    onToggleAutoReview: (Boolean) -> Unit,
    onToggleWebhook: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReview: (PullRequestSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Open pull requests", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = onRefresh, enabled = !isLoading && !isReviewing) {
                Text("Refresh")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto review pull requests", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Switch(checked = autoReviewEnabled, onCheckedChange = onToggleAutoReview, enabled = !isReviewing)
        }
        Spacer(Modifier.height(4.dp))
        Text(autoReviewStatus, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GitHub App webhook listener", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Switch(checked = webhookEnabled, onCheckedChange = onToggleWebhook, enabled = !isReviewing)
        }
        Spacer(Modifier.height(4.dp))
        Text(webhookStatus, style = MaterialTheme.typography.bodySmall)
        if (isLoading || isReviewing) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                if (pullRequests.isEmpty()) {
                    Text("No pull requests to display.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn {
                        items(pullRequests) { pr ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("#${pr.number} ${pr.title}", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Author: ${pr.author} | Branches: ${pr.headBranch} → ${pr.baseBranch}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Files: ${pr.changedFiles} (+${pr.additions}/-${pr.deletions})",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Updated: ${pr.updatedAt}", style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.weight(1f))
                                        Button(onClick = { onReview(pr) }, enabled = !isReviewing) {
                                            Text(if (isReviewing) "Reviewing..." else "Review")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            ) {
                val title = reviewedPr?.let { "#${it.number} ${it.title}" } ?: "No review yet"
                Text("Review output", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val content = reviewText ?: "Run the reviewer to see results here."
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .padding(8.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(content, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}



private enum class AssistantTab(val title: String) {
    CHAT("Chat"),
    MCP("MCP"),
    PRS("Pull Requests"),
}

private fun sendCurrentMessage(
    scope: CoroutineScope,
    userInput: String,
    setUserInput: (String) -> Unit,
    messages: MutableList<ChatMessage>,
    controller: AssistantController,
    chatModel: String,
    embeddingModel: String,
    baseUrl: String,
    projectPath: Path?,
    setStatus: (String) -> Unit,
    setBusy: (Boolean) -> Unit,
) {
    val trimmed = userInput.trim()
    if (trimmed.isEmpty()) return
    messages += ChatMessage(MessageRole.USER, trimmed)
    setUserInput("")

    scope.launch {
        setStatus("Sending request to Ollama...")
        setBusy(true)
        val gitBranch = projectPath?.let { controller.fetchBranch(it).getOrNull() }
            val response = controller.ask(
                question = trimmed,
                chatModel = chatModel,
                embeddingModel = embeddingModel,
                baseUrl = baseUrl,
                gitBranch = gitBranch,
                projectRoot = projectPath,
        )
        response.onSuccess {
            messages += ChatMessage(MessageRole.ASSISTANT, it)
            setStatus("Response received")
        }.onFailure {
            val errorText = "Error: ${it.message ?: "no details"}"
            messages += ChatMessage(MessageRole.SYSTEM, errorText)
            setStatus(errorText)
        }
        setBusy(false)
    }
}

