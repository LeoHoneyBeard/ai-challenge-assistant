# AI Challenge Assistant

AI Challenge Assistant is a Kotlin/Compose desktop helper that indexes the project README and `project/docs`, talks to MCP/Git to obtain the current branch, and forwards prompts to a local Ollama instance. The deliverable satisfies the minimal requirements: README + docs in RAG, `/help` command for structure hints, and git branch awareness via MCP.

## Features
- **RAG ingestion** – loads README, everything under `project/docs`, and code under `src/**` (with size caps) so the assistant can embed both documentation and implementation details.
- **Ollama client** – configurable base URL/model; uses Ktor to call `POST /api/chat`.
- **MCP/Git bridge** – lightweight `McpGitClient` runs `git rev-parse --abbrev-ref HEAD` inside the selected folder and feeds the branch into the system prompt.
- **MCP control center** – a dedicated UI tab lists the GitHub MCP tools (overview, issues, PRs, commits, branches, contributors) and lets the user enable or disable each one before the model can request it.
- **Pull request reviewer** – a dedicated tab fetches open GitHub PRs via MCP, displays accurate change stats, and renders the latest Russian-language review next to the list for quick scanning.
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
6. Switch to the **MCP** tab to review connected servers, inspect their tools, and toggle access for the model.

## MCP configuration
The app now reads MCP credentials from `local.properties` so secrets stay outside version control. The GitHub owner/repo are detected automatically from the currently selected project's `git remote origin`. Simply provide a Personal Access Token and the assistant will connect to the matching repository once you pick a project.

```
mcp.github.token=ghp_your_personal_access_token
```

If you need to override the detected values (for example, when the project is not cloned from GitHub) you can still supply the optional keys below:

```
mcp.github.owner=your-github-org-or-user
mcp.github.repo=your-repo-name
# Optional, defaults to https://api.github.com
mcp.github.apiUrl=https://api.github.com
```

After saving the file, restart the app or click **Reload** inside the MCP tab. Each GitHub tool (overview, issues, pull requests, commits, branches, contributors) can be switched on or off per your preference, and only the enabled tools are advertised to the model.

Currently available GitHub tools:
- `github-repo-overview`
- `github-open-issues`
- `github-open-prs`
- `github-latest-commits`
- `github-branches`
- `github-top-contributors`

Environment fallback: the assistant also recognises the following environment variables, which is useful for CI:

```
MCP_GITHUB_TOKEN
MCP_GITHUB_OWNER
MCP_GITHUB_REPO
MCP_GITHUB_API_URL
```

## Pull request reviews
- Switch to the **Pull Requests** tab to fetch open PRs from GitHub. Each row contains number, author, branch information, and accurate change stats (files, additions, deletions).
- Press **Review** to fetch the full diff + file metadata and ask the LLM for an annotated review. The assistant constructs a RAG query from the PR title, description, and changed files, retrieves project snippets, and attaches them to the prompt.
- The generated review is rendered in the right-hand panel of the Pull Requests tab (always in Russian) so you can inspect the findings without leaving the PR context.

The diff provided to the model is truncated to ~120k characters; if a PR is larger, the assistant still lists the affected files and highlights that portions of the diff were omitted.

## CI pipeline
The repository ships with `.github/workflows/pr-review.yml`, which runs the headless reviewer on every pull request. The workflow:
1. Checks out the PR.
2. Runs `./gradlew -q prReview`, which ingests the project, fetches PR metadata/diff/files via MCP, and streams the LLM review to stdout.
3. Posts the output as a comment on the pull request.

To enable the workflow:
1. Provide a GitHub token with `repo` scope via the `MCP_GITHUB_TOKEN` secret.
2. Provide the Ollama endpoint/model secrets (`OLLAMA_BASE_URL`, optional `OLLAMA_CHAT_MODEL`/`OLLAMA_EMBED_MODEL`).
3. Optionally set organisation-level `vars` for the model names.

You can also run the reviewer locally with:

```
MCP_GITHUB_TOKEN=ghp_xxx OLLAMA_BASE_URL=http://localhost:11434 PR_NUMBER=123 ./gradlew -q prReview
```

The CLI respects the same `local.properties` or environment-based MCP configuration used by the desktop app.

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
