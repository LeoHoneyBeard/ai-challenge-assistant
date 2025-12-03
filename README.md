# AI Challenge Assistant



AI Challenge Assistant is a Kotlin/Compose desktop helper that indexes the project README and `project/docs`, talks to MCP/Git to obtain the current branch, and forwards prompts to a local Ollama instance. The deliverable satisfies the minimal requirements: README + docs in RAG, `/help` command for structure hints, and git branch awareness via MCP.




## Features

- **RAG ingestion** вЂ“ loads README, everything under `project/docs`, the optional `project/faq` knowledge base, and code under `src/**` (with size caps) so the assistant can embed both documentation and implementation details.

- **Ollama client** вЂ“ configurable base URL/model; uses Ktor to call `POST /api/chat`.

- **MCP/Git bridge** вЂ“ lightweight `McpGitClient` runs `git rev-parse --abbrev-ref HEAD` inside the selected folder and feeds the branch into the system prompt.

- **MCP control center** вЂ“ a dedicated UI tab lists the GitHub MCP tools (overview, issues, PRs, commits, branches, contributors) and lets the user enable or disable each one before the model can request it.

- **Pull request reviewer** вЂ“ a dedicated tab fetches open GitHub PRs via MCP, displays accurate change stats, and renders the latest Russian-language review next to the list for quick scanning.
- **User issues triage** вЂ“ the new User Issues tab reads `issues/user_issues.json`, refreshes the list on demand, and lets you ask the LLM for a Russian-language solution backed by README/docs/src/FAQ snippets.

- **`/help` command** вЂ“ summarizes folders, key files and active RAG sources without calling the model.

- **Desktop chat UI** вЂ“ Compose Material3 layout with project selector, branch refresh button, warnings, chat history and clear action.



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
# Optional GitHub App webhook listener
github.webhook.port=9000
github.webhook.secret=super_secret
```



After saving the file, restart the app or click **Reload** inside the MCP tab. Each GitHub tool (overview, issues, pull requests, commits, branches, contributors) can be switched on or off per your preference, and only the enabled tools are advertised to the model.



Workspace tools:

- `workspace-user-issues`

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
GITHUB_WEBHOOK_PORT
GITHUB_WEBHOOK_SECRET
```



## Pull request reviews

- Switch to the **Pull Requests** tab to fetch open PRs from GitHub. Each row contains number, author, branch information, and accurate change stats (files, additions, deletions).
- Press **Review** to fetch the full diff + file metadata and ask the LLM for an annotated review. The assistant constructs a RAG query from the PR title, description, and changed files, retrieves project snippets, and attaches them to the prompt.
- Toggle **Auto review** to let the app poll GitHub periodically; whenever a new or updated PR is detected, the reviewer runs automatically and posts the latest findings in the panel.
- Toggle **GitHub App webhook listener** to accept signed `pull_request` events and trigger reviews instantly without polling.
- The generated review is rendered in the right-hand panel of the Pull Requests tab (always in Russian) so you can inspect the findings without leaving the PR context.

The diff provided to the model is truncated to ~120k characters; if a PR is larger, the assistant still lists the affected files and highlights that portions of the diff were omitted.

## User issues

- Populate `issues/user_issues.json` with an array of user requests. Each entry must have a `userName` plus an `issue` object with `issueId`, `issueNumber`, `subject`, and `issue` (detailed description).
- Open the **User Issues** tab to review the queue, refresh it from disk, and click **Предложить решение** to ask the assistant for diagnostics or a workaround.
- The model automatically builds a query from the issue, searches README, docs, source code, and the `project/faq` folder, and returns a Russian-language response summarizing how to fix or explain the behavior.
- The `workspace-user-issues` MCP tool exposes the same JSON data to the model so it can request freshness when composing answers in the chat tab.

## Headless CLI runner
You can still run the reviewer in CI or from scripts even though the default GitHub workflows were removed:
`
MCP_GITHUB_TOKEN=ghp_xxx OLLAMA_BASE_URL=http://localhost:11434 PR_NUMBER=123 ./gradlew -q prReview
`
The command ingests the project, fetches PR metadata/diff/files via MCP, and prints the LLM review to stdout; feed the output into any automation that needs it (for example, a custom GitHub Action).

### GitHub App / Webhook listener
### GitHub App / Webhook listener
To trigger reviews directly on the running desktop app:
1. Create (or reuse) a GitHub App with the `pull_request` webhook enabled. Set the webhook URL to `http://<public-host>:<github.webhook.port>/github/webhook` and copy the webhook secret.
2. Configure `github.webhook.port` and `github.webhook.secret` (or the `GITHUB_WEBHOOK_PORT` / `GITHUB_WEBHOOK_SECRET` environment variables) so the app knows where to listen and how to verify signatures.
3. Forward the chosen port to your workstation (ngrok/cloudflared/SSH) if the desktop app is not reachable from the public internet.
4. In the Pull Requests tab, enable **GitHub App webhook listener**. When a signed webhook arrives and the repository matches the currently selected project, the assistant refreshes the PR list and automatically runs the review for that PR.

Webhook delivery only triggers the review; all GitHub API access still relies on `MCP_GITHUB_TOKEN` (or your PAT) for fetching diffs and files.
Creating a GitHub App (quick steps):
1. Go to **Settings в†’ Developer settings в†’ GitHub Apps** (or the org equivalent) and click **New GitHub App**.
2. Fill in name/homepage, paste your public webhook URL, and set a webhook secret (`openssl rand -hex 32` or `python -c "import secrets;print(secrets.token_hex(32))"`).
3. Grant repository permissions: `Pull requests: Read-only`, `Contents: Read-only` (optional).
4. Subscribe to the `pull_request` event.
5. Click **Create GitHub App**, then **Install App** on the target repositories.
6. In the desktop app, enable the webhook listener toggle once the App is installed and the tunnel is running.


## Repository layout

- `src/jvmMain/kotlin/com/aichallenge/assistant/Main.kt` вЂ“ UI + app entry point.

- `src/jvmMain/kotlin/com/aichallenge/assistant/service` вЂ“ controller and project analyzer.

- `src/jvmMain/kotlin/com/aichallenge/assistant/rag` вЂ“ document ingestion and retrieval.

- `src/jvmMain/kotlin/com/aichallenge/assistant/integrations` вЂ“ Ollama and MCP/Git clients.

- `project/docs` вЂ“ base knowledge for the RAG index.
- `project/faq` – frequently asked questions auto-indexed into RAG.
- `issues/user_issues.json` – editable queue of user requests for the User Issues tab.



## MCP/Git notes

The included MCP bridge executes the Git CLI to obtain the current branch. To switch to a full MCP server (for example, GitHub MCP), replace `McpGitClient.fetchCurrentBranch` with the appropriate RPC call; the rest of the UI already expects branch information via `AssistantController.fetchBranch`.



## Next steps

- Replace the naive bag-of-words scorer with actual embeddings (Ollama embeddings API, ONNX, etc.).

- Support additional MCP providers (open files, tickets) and surface them in the chat.

- Extend `/help` with coding guidelines or style rules sourced from docs.











