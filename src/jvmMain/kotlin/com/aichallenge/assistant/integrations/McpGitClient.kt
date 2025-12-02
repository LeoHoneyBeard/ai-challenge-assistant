package com.aichallenge.assistant.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.nio.file.Path

data class GithubRepoRef(
    val host: String,
    val owner: String,
    val repo: String,
)

class McpGitClient(
    private val gitExecutable: String = "git",
) {

    suspend fun fetchCurrentBranch(repository: Path): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            runGit(repository, "rev-parse", "--abbrev-ref", "HEAD").trim()
        }
    }

    suspend fun detectGithubRepo(repository: Path): Result<GithubRepoRef?> = withContext(Dispatchers.IO) {
        runCatching {
            val remoteUrl = runCatching { runGit(repository, "config", "--get", "remote.origin.url").trim() }
                .getOrElse { return@runCatching null }
            if (remoteUrl.isBlank()) return@runCatching null
            parseGithubRemote(remoteUrl)
        }
    }

    private fun runGit(repository: Path, vararg args: String): String {
        val command = listOf(gitExecutable, *args)
        val process = ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error(output.trim().ifBlank { "git exited with code $exitCode" })
        }
        return output
    }

    private fun parseGithubRemote(url: String): GithubRepoRef? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val patterns = listOf(
            Regex("^https?://([^/]+)/([^/]+)/(.+)$", RegexOption.IGNORE_CASE),
            Regex("^[\\w.-]+@([^:]+):([^/]+)/(.+)$"),
            Regex("^ssh://(?:[\\w.-]+@)?([^/]+)/([^/]+)/(.+)$", RegexOption.IGNORE_CASE),
            Regex("^git://([^/]+)/([^/]+)/(.+)$", RegexOption.IGNORE_CASE),
        )
        patterns.forEach { pattern ->
            val match = pattern.matchEntire(trimmed)
            if (match != null) {
                val host = match.groupValues[1]
                val owner = match.groupValues[2]
                val repoRaw = match.groupValues[3]
                val repo = repoRaw.removeSuffix(".git").substringBefore("/").trim()
                val normalizedOwner = owner.trim().trim('/')
                val normalizedHost = host.trim().trimEnd('/')
                if (normalizedHost.isEmpty() || normalizedOwner.isEmpty() || repo.isEmpty()) {
                    return null
                }
                if (!normalizedHost.contains("github", ignoreCase = true)) {
                    return null
                }
                return GithubRepoRef(
                    host = normalizedHost,
                    owner = normalizedOwner,
                    repo = repo,
                )
            }
        }
        return null
    }
}
