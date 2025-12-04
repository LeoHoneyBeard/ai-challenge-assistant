package com.aichallenge.assistant.service

import com.aichallenge.assistant.model.ProjectTask
import com.aichallenge.assistant.model.TaskDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories

class TaskTrackerRepository(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {

    suspend fun loadTasks(projectRoot: Path?): Result<List<ProjectTask>> = withContext(Dispatchers.IO) {
        val root = normalizeRoot(projectRoot) ?: return@withContext Result.failure(
            IllegalStateException("Project path is not selected."),
        )
        val file = tasksFileInternal(root)
        if (!Files.exists(file)) {
            return@withContext Result.failure(
                IllegalStateException("Task tracker file not found at ${file.toAbsolutePath()}"),
            )
        }
        val content = runCatching { Files.readString(file) }
            .getOrElse { error -> return@withContext Result.failure(error) }
        val sanitized = content.trimStart { it == '\uFEFF' || it == '\u0000' }.trim()
        if (sanitized.isEmpty()) {
            return@withContext Result.success(emptyList())
        }
        val tasks = runCatching {
            json.decodeFromString(ListSerializer(ProjectTask.serializer()), sanitized)
        }.getOrElse { error ->
            return@withContext Result.failure(
                IllegalStateException("Failed to parse task tracker JSON: ${error.message}", error),
            )
        }
        Result.success(tasks)
    }

    suspend fun createTask(projectRoot: Path?, draft: TaskDraft): Result<ProjectTask> = withContext(Dispatchers.IO) {
        val root = normalizeRoot(projectRoot) ?: return@withContext Result.failure(
            IllegalStateException("Project path is not selected."),
        )
        val file = tasksFileInternal(root)
        val current = if (Files.exists(file)) {
            loadTasks(root).getOrElse { error -> return@withContext Result.failure(error) }
        } else {
            emptyList()
        }
        val normalizedRequirements = draft.requirements.mapNotNull { requirement ->
            val trimmed = requirement.trim()
            trimmed.takeIf { it.isNotEmpty() }
        }
        val task = ProjectTask(
            id = draft.title.takeIf { it.isNotBlank() }?.let { generateTaskId(it) } ?: UUID.randomUUID().toString(),
            title = draft.title.trim(),
            description = draft.description.trim(),
            priority = draft.priority,
            requirements = normalizedRequirements,
        )
        val updated = current + task
        file.parent?.createDirectories()
        runCatching {
            Files.writeString(file, json.encodeToString(ListSerializer(ProjectTask.serializer()), updated))
        }.onFailure { error ->
            return@withContext Result.failure(error)
        }
        Result.success(task)
    }

    suspend fun createTasks(projectRoot: Path?, drafts: List<TaskDraft>): Result<List<ProjectTask>> = withContext(Dispatchers.IO) {
        if (drafts.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Task list for batch creation is empty."))
        }
        val root = normalizeRoot(projectRoot) ?: return@withContext Result.failure(
            IllegalStateException("Project path is not selected."),
        )
        val file = tasksFileInternal(root)
        val current = if (Files.exists(file)) {
            loadTasks(root).getOrElse { error -> return@withContext Result.failure(error) }
        } else {
            emptyList()
        }
        val newTasks = drafts.map { draft ->
            val normalizedRequirements = draft.requirements.mapNotNull { requirement ->
                val trimmed = requirement.trim()
                trimmed.takeIf { it.isNotEmpty() }
            }
            ProjectTask(
                id = draft.title.takeIf { it.isNotBlank() }?.let { generateTaskId(it) } ?: UUID.randomUUID().toString(),
                title = draft.title.trim(),
                description = draft.description.trim(),
                priority = draft.priority,
                requirements = normalizedRequirements,
            )
        }
        val updated = current + newTasks
        file.parent?.createDirectories()
        runCatching {
            Files.writeString(file, json.encodeToString(ListSerializer(ProjectTask.serializer()), updated))
        }.onFailure { error ->
            return@withContext Result.failure(error)
        }
        Result.success(newTasks)
    }

    fun parseDraftPayload(payload: String?): Result<TaskDraft> {
        if (payload.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Task creation tool requires a JSON payload with title, description, priority, and requirements."))
        }
        val normalized = normalizePayload(payload)
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Task creation tool received an empty payload after stripping formatting."))
        }
        return runCatching { json.decodeFromString(TaskDraft.serializer(), normalized) }
    }

    fun parseDraftListPayload(payload: String?): Result<List<TaskDraft>> {
        if (payload.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Task batch creation tool requires a JSON payload containing an array of tasks."))
        }
        val normalized = normalizePayload(payload)
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Task batch creation tool received an empty payload after stripping formatting."))
        }
        return runCatching {
            val element = json.parseToJsonElement(normalized)
            val arrayNode = when {
                element is kotlinx.serialization.json.JsonArray -> element
                element is kotlinx.serialization.json.JsonObject && element["tasks"] is kotlinx.serialization.json.JsonArray -> element["tasks"] as kotlinx.serialization.json.JsonArray
                else -> error("Expected an array of tasks or an object with a 'tasks' array.")
            }
            json.decodeFromJsonElement(ListSerializer(TaskDraft.serializer()), arrayNode)
        }
    }

    fun formatForMcp(tasks: List<ProjectTask>): String {
        if (tasks.isEmpty()) {
            return "Task tracker is empty."
        }
        return buildString {
            appendLine("Project task tracker (${tasks.size} tasks):")
            tasks.forEachIndexed { index, task ->
                appendLine("${index + 1}. [${task.priority}] ${task.title} (ID=${task.id})")
                if (task.description.isNotBlank()) {
                    appendLine("   Description: ${task.description}")
                }
                if (task.requirements.isNotEmpty()) {
                    appendLine("   Requirements:")
                    task.requirements.forEach { requirement ->
                        appendLine("     - ${requirement.trim()}")
                    }
                }
            }
        }.trim()
    }

    fun tasksFile(projectRoot: Path?): Path? = normalizeRoot(projectRoot)?.let { tasksFileInternal(it) }

    suspend fun deleteTask(projectRoot: Path?, taskId: String): Result<ProjectTask> = withContext(Dispatchers.IO) {
        val root = normalizeRoot(projectRoot) ?: return@withContext Result.failure(
            IllegalStateException("Project path is not selected."),
        )
        val file = tasksFileInternal(root)
        if (!Files.exists(file)) {
            return@withContext Result.failure(IllegalStateException("Task tracker file not found at ${file.toAbsolutePath()}"))
        }
        val current = loadTasks(root).getOrElse { error -> return@withContext Result.failure(error) }
        val index = current.indexOfFirst { it.id.equals(taskId, ignoreCase = true) }
        if (index < 0) {
            return@withContext Result.failure(IllegalArgumentException("Task with id=$taskId was not found."))
        }
        val removed = current[index]
        val updated = current.toMutableList().also { it.removeAt(index) }
        runCatching {
            Files.writeString(file, json.encodeToString(ListSerializer(ProjectTask.serializer()), updated))
        }.onFailure { error ->
            return@withContext Result.failure(error)
        }
        Result.success(removed)
    }

    private fun tasksFileInternal(root: Path): Path = root.resolve("task_tracker").resolve("tasks.json")

    private fun normalizeRoot(projectRoot: Path?): Path? {
        if (projectRoot == null) return null
        return if (Files.isDirectory(projectRoot)) projectRoot else projectRoot.parent
    }

    private fun generateTaskId(title: String): String {
        val safeTitle = title.trim().lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
        val suffix = java.lang.Long.toString(System.currentTimeMillis(), 16)
        return if (safeTitle.isNotEmpty()) "${safeTitle.take(24)}-$suffix" else suffix
    }

    private fun normalizePayload(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val fenceEnd = trimmed.indexOf('\n')
        if (fenceEnd <= 0) return trimmed
        val body = trimmed.substring(fenceEnd + 1)
        val closingIndex = body.lastIndexOf("```")
        if (closingIndex >= 0) {
            return body.substring(0, closingIndex).trim()
        }
        return body.trim()
    }
}
