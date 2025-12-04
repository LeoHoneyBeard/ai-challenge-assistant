package com.aichallenge.assistant.rag

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile


data class RagLoadResult(
    val chunks: List<TextChunk>,
    val sources: List<Path>,
    val skipped: List<String>,
)

data class TextChunk(
    val source: String,
    val content: String,
)

class DocumentLoader(
    private val allowedExtensions: Set<String> = setOf("md", "txt", "json", "kt", "kts", "java", "js", "ts"),
    private val maxFileSizeBytes: Long = 512 * 1024,
) {

    fun loadSources(selectedPath: Path): RagLoadResult {
        val normalized = selectedPath.toAbsolutePath().normalize()
        val root = if (normalized.isDirectory()) normalized else normalized.parent
        val targets = collectTargets(normalized, root)

        val chunks = mutableListOf<TextChunk>()
        val errors = mutableListOf<String>()

        targets.forEach { file ->
            runCatching { Files.readString(file) }
                .onSuccess { content ->
                    val relativeLabel = labelFor(root, file)
                    chunks += chunkText(relativeLabel, content)
                }
                .onFailure { errors += "Cannot read ${'$'}{file.fileName}: ${'$'}{it.message}" }
        }

        return RagLoadResult(
            chunks = chunks,
            sources = targets,
            skipped = errors,
        )
    }

    private fun collectTargets(selection: Path, projectRoot: Path?): List<Path> {
        val files = linkedSetOf<Path>()
        val base = when {
            selection.isDirectory() -> selection
            projectRoot != null -> projectRoot
            else -> selection.parent
        }
        if (selection.isRegularFile()) {
            files.add(selection)
        }
        if (base == null || !base.isDirectory()) {
            return files.toList()
        }

        val readme = base.resolve("README.md")
        if (readme.isRegularFile()) files.add(readme)

        val docsRoot = base.resolve("project").resolve("docs")
        if (docsRoot.isDirectory()) {
            Files.walk(docsRoot).use { stream ->
                stream.filter { path ->
                    path.isRegularFile() && shouldInclude(path)
                }.forEach { files.add(it) }
            }
        }

        val projectFaqRoot = base.resolve("project").resolve("faq")
        if (projectFaqRoot.isDirectory()) {
            Files.walk(projectFaqRoot).use { stream ->
                stream.filter { path ->
                    path.isRegularFile() && shouldInclude(path)
                }.forEach { files.add(it) }
            }
        }

        val topLevelFaq = base.resolve("faq")
        if (topLevelFaq.isDirectory()) {
            Files.walk(topLevelFaq).use { stream ->
                stream.filter { path ->
                    path.isRegularFile() && shouldInclude(path)
                }.forEach { files.add(it) }
            }
        }

        val srcDir = base.resolve("src")
        if (srcDir.isDirectory()) {
            Files.walk(srcDir).use { stream ->
                stream.filter { path ->
                    path.isRegularFile() && shouldInclude(path)
                }.forEach { files.add(it) }
            }
        }

        Files.walk(base, 2).use { stream ->
            stream.filter { path ->
                path.isRegularFile() && path.extension.equals("md", ignoreCase = true)
            }.forEach { files.add(it) }
        }

        if (files.isEmpty()) {
            Files.walk(base, 2).use { stream ->
                stream.filter { it.isRegularFile() && shouldInclude(it) }
                    .forEach { files.add(it) }
            }
        }

        return files.toList()
    }

    private fun shouldInclude(path: Path): Boolean {
        if (!allowedExtensions.contains(path.extension.lowercase())) return false
        return runCatching { Files.size(path) <= maxFileSizeBytes }.getOrElse { false }
    }

    private fun labelFor(root: Path?, target: Path): String {
        if (root == null) return target.fileName.toString()
        return try {
            val relative = root.relativize(target.toAbsolutePath().normalize()).toString()
            if (relative.isBlank()) target.fileName.toString() else relative.replace("\\", "/")
        } catch (_: IllegalArgumentException) {
            target.fileName.toString()
        }
    }

    private fun chunkText(sourceLabel: String, text: String, maxChars: Int = 900): List<TextChunk> {
        if (text.isBlank()) return emptyList()
        val cleaned = text.replace("\r\n", "\n")
        val paragraphs = cleaned.split("\n\n")
        val buffer = StringBuilder()
        val chunks = mutableListOf<TextChunk>()

        fun flush() {
            if (buffer.isEmpty()) return
            val chunkText = buffer.toString().trim()
            if (chunkText.isNotEmpty()) {
                chunks += TextChunk(source = sourceLabel, content = chunkText)
            }
            buffer.clear()
        }

        paragraphs.forEach { paragraph ->
            if (buffer.length + paragraph.length + 2 > maxChars) {
                flush()
            }
            if (paragraph.isNotBlank()) {
                if (buffer.isNotEmpty()) buffer.append("\n\n")
                buffer.append(paragraph.trim())
            }
        }
        flush()
        return chunks
    }
}
