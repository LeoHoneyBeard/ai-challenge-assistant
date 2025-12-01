package com.aichallenge.assistant.rag

import java.util.UUID
import kotlin.math.sqrt

data class KnowledgeChunk(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val content: String,
    val embedding: List<Double>,
)

class KnowledgeBase {
    private val chunks = mutableListOf<KnowledgeChunk>()
    private val lock = Any()

    fun replaceAll(newChunks: List<KnowledgeChunk>) {
        synchronized(lock) {
            chunks.clear()
            chunks.addAll(newChunks)
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { chunks.isEmpty() }

    fun clear() = replaceAll(emptyList())

    fun snapshot(): List<KnowledgeChunk> = synchronized(lock) { chunks.toList() }

    fun sources(): List<String> = synchronized(lock) {
        chunks.map { it.source }.distinct()
    }

    fun search(queryEmbedding: List<Double>, topK: Int = 4): List<KnowledgeChunk> {
        if (queryEmbedding.isEmpty()) return emptyList()
        val state = snapshot()
        if (state.isEmpty()) return emptyList()
        return state
            .asSequence()
            .map { chunk ->
                val score = cosineSimilarity(queryEmbedding, chunk.embedding)
                chunk to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
            .toList()
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val length = minOf(a.size, b.size)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until length) {
            val av = a[i]
            val bv = b[i]
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
