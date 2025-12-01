# RAG knowledge base

- Required sources: the root `README.md` plus everything inside `project/docs`.
- The loader also accepts user-selected `.md/.txt/.json/.kt/.java/.kts` files when they live under the chosen directory.
- Text is chunked by paragraph into ~900 character windows to keep context manageable.
- Retrieval uses a simple bag-of-words cosine similarity (can be swapped for embeddings later).
- The system prompt sent to Ollama includes up to four `[relative/path]\nchunk` blocks with the most relevant excerpts.
