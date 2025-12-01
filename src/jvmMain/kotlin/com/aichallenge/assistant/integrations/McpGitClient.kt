package com.aichallenge.assistant.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

class McpGitClient(
    private val gitExecutable: String = "git",
) {

    suspend fun fetchCurrentBranch(repository: Path): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(gitExecutable, "rev-parse", "--abbrev-ref", "HEAD")
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error(if (output.isBlank()) "git exited with code $exitCode" else output)
            }
            output
        }
    }
}

