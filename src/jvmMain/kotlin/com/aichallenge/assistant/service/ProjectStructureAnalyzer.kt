package com.aichallenge.assistant.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class ProjectStructureAnalyzer {

    fun describe(root: Path, ragSources: List<String>, question: String?): String {
        val builder = StringBuilder()
        builder.appendLine("Project ${root.fileName} structure:")

        val directories = Files.list(root).use { stream ->
            stream.filter { it.isDirectory() }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

        val importantFiles = listOf("README.md", "build.gradle.kts", "settings.gradle.kts")
            .filter { root.resolve(it).isRegularFile() }

        builder.appendLine("- Top level directories: ${directories.take(6).joinToString(", ").ifBlank { "n/a" }}")
        if (directories.size > 6) {
            builder.appendLine("  (and ${directories.size - 6} more)")
        }
        if (importantFiles.isNotEmpty()) {
            builder.appendLine("- Notable files: ${importantFiles.joinToString()}")
        }

        val docsRoot = root.resolve("project").resolve("docs")
        if (docsRoot.isDirectory()) {
            val docs = Files.walk(docsRoot, 2).use { stream ->
                stream.filter { it.isRegularFile() }
                    .map { docsRoot.relativize(it).toString().replace("\\", "/") }
                    .sorted()
                    .toList()
            }
            if (docs.isNotEmpty()) {
                builder.appendLine("- project/docs entries: ${docs.joinToString(limit = 5)}")
            }
        }

        if (ragSources.isNotEmpty()) {
            val head = ragSources.take(4).joinToString()
            builder.appendLine("- Active RAG sources: $head" + if (ragSources.size > 4) " (+${ragSources.size - 4})" else "")
        } else {
            builder.appendLine("- RAG sources are empty.")
        }

        if (!question.isNullOrBlank()) {
            builder.appendLine()
            builder.appendLine("Request: ${question.trim()}")
            builder.appendLine("Use /help for structure questions and regular prompts for Ollama responses.")
        }

        return builder.toString().trim()
    }
}
