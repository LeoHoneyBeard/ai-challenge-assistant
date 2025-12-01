# Architecture Notes

## Layers
1. **UI (Compose Desktop)** – chat window, project source chooser, MCP/Git button. Uses Material3 components.
2. **Service layer** – `AssistantController` orchestrates document ingestion, Ollama calls, MCP branch fetch and `/help` replies.
3. **RAG** – `DocumentLoader` (loads README + `project/docs`), `KnowledgeBase` (chunking + cosine scoring).
4. **Integrations** – `OllamaClient` (HTTP) and `McpGitClient` (`git branch` via CLI).

## Data flow
```
user -> UI -> AssistantController -> (RAG search + Ollama) -> reply -> UI
```

`/help` bypasses Ollama and uses only filesystem metadata + the active RAG sources.
