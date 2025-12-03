package com.aichallenge.assistant.service

import com.aichallenge.assistant.model.UserIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class UserIssueRepository(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {

    suspend fun loadIssues(projectRoot: Path?): Result<List<UserIssue>> = withContext(Dispatchers.IO) {
        val root = normalizeRoot(projectRoot) ?: return@withContext Result.failure(
            IllegalStateException("Project path is not selected."),
        )
        val file = issuesFileInternal(root)
        if (!Files.exists(file)) {
            return@withContext Result.failure(IllegalStateException("Issues file not found at ${file.toAbsolutePath()}"))
        }
        val content = runCatching { Files.readString(file) }
            .getOrElse { error -> return@withContext Result.failure(error) }
        val sanitized = content.trimStart { it == '\uFEFF' || it == '\u0000' }
        val issues = runCatching {
            json.decodeFromString(ListSerializer(UserIssue.serializer()), sanitized)
        }.getOrElse { error ->
            return@withContext Result.failure(IllegalStateException("Failed to parse user issues JSON: ${error.message}", error))
        }
        Result.success(issues)
    }

    fun formatForMcp(issues: List<UserIssue>): String {
        if (issues.isEmpty()) {
            return "No user issues recorded in issues/user_issues.json."
        }
        return buildString {
            appendLine("User issues:")
            issues.forEachIndexed { index, issue ->
                appendLine("${index + 1}. ${issue.issue.subject} (ID=${issue.issue.issueId}, #${issue.issue.issueNumber})")
                appendLine("   Reporter: ${issue.userName}")
                appendLine("   Details: ${issue.issue.description.trim()}")
            }
        }.trim()
    }

    fun issuesFile(projectRoot: Path?): Path? = normalizeRoot(projectRoot)?.let { issuesFileInternal(it) }

    private fun issuesFileInternal(root: Path): Path = root.resolve("issues").resolve("user_issues.json")

    private fun normalizeRoot(projectRoot: Path?): Path? {
        if (projectRoot == null) return null
        return if (Files.isDirectory(projectRoot)) projectRoot else projectRoot.parent
    }
}
