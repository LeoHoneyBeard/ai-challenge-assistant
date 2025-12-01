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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
import com.aichallenge.assistant.model.ChatMessage
import com.aichallenge.assistant.model.MessageRole
import com.aichallenge.assistant.model.RagStatus
import com.aichallenge.assistant.service.AssistantController
import com.aichallenge.assistant.service.SettingsStore
import kotlinx.coroutines.CoroutineScope
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
                Text("Chat", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
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
                    onValueChange = { userInput = it },
                    onSubmit = {
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
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
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
                        },
                    ) {
                        Text("Send")
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = {
                        messages.clear()
                        status = "History cleared"
                    }) {
                        Text("Clear chat")
                    }
                }
            }
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
    if (options.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onSelect,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
            )
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
