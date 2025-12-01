# AI Challenge Assistant

AI Challenge Assistant is a Kotlin/Compose desktop helper that indexes the project README and `project/docs`, talks to MCP/Git to obtain the current branch, and forwards prompts to a local Ollama instance. The deliverable satisfies the minimal requirements: README + docs in RAG, `/help` command for structure hints, and git branch awareness via MCP.

## Features
- **RAG ingestion** – loads README plus every file inside `project/docs`, chunks the text and performs cosine-similarity retrieval before sending a question to the model.
- **Ollama client** – configurable base URL/model; uses Ktor to call `POST /api/chat`.
- **MCP/Git bridge** – lightweight `McpGitClient` runs `git rev-parse --abbrev-ref HEAD` inside the selected folder and feeds the branch into the system prompt.
- **`/help` command** – summarizes folders, key files and active RAG sources without calling the model.
- **Desktop chat UI** – Compose Material3 layout with project selector, branch refresh button, warnings, chat history and clear action.

## Architecture
- Compose Desktop UI (`Main.kt`).
- `AssistantController` orchestrates ingestion, `/help`, Ollama calls and MCP lookups.
- `rag` package handles document discovery and chunk management.
- `integrations` contains the Ollama HTTP client and the MCP/Git CLI client.
- `project/docs` stores knowledge that is automatically indexed and also serves as documentation.

## Requirements
- JDK 17+
- Gradle Wrapper (included)
- Running Ollama (`ollama run llama3.1` or similar) reachable via `http://localhost:11434`
- Git available on PATH

## Getting started
```
./gradlew.bat run   # Windows
./gradlew run       # macOS/Linux
```
The command builds the app and opens the Compose window.

### Workflow
1. Press **Select & ingest** and choose a README or the project root; the RAG index refreshes automatically.
2. (Optional) press **Update git branch** so the assistant knows the active branch.
3. Adjust the Ollama URL/model if necessary.
4. Use `/help` for structure questions; send regular prompts for free-form assistance.
5. Clear the chat at any time with **Clear chat**.

## Repository layout
- `src/jvmMain/kotlin/com/aichallenge/assistant/Main.kt` – UI + app entry point.
- `src/jvmMain/kotlin/com/aichallenge/assistant/service` – controller and project analyzer.
- `src/jvmMain/kotlin/com/aichallenge/assistant/rag` – document ingestion and retrieval.
- `src/jvmMain/kotlin/com/aichallenge/assistant/integrations` – Ollama and MCP/Git clients.
- `project/docs` – base knowledge for the RAG index.

## MCP/Git notes
The included MCP bridge executes the Git CLI to obtain the current branch. To switch to a full MCP server (for example, GitHub MCP), replace `McpGitClient.fetchCurrentBranch` with the appropriate RPC call; the rest of the UI already expects branch information via `AssistantController.fetchBranch`.

## Next steps
- Replace the naive bag-of-words scorer with actual embeddings (Ollama embeddings API, ONNX, etc.).
- Support additional MCP providers (open files, tickets) and surface them in the chat.
- Extend `/help` with coding guidelines or style rules sourced from docs.
