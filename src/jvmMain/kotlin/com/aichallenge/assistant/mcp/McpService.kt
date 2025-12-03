package com.aichallenge.assistant.mcp

import com.aichallenge.assistant.integrations.McpGitClient
import com.aichallenge.assistant.model.PullRequestFileDiff
import com.aichallenge.assistant.model.PullRequestReviewBundle
import com.aichallenge.assistant.model.PullRequestSummary
import com.aichallenge.assistant.service.UserIssueRepository
import com.aichallenge.assistant.service.SettingsStore
import java.nio.file.Path

class McpService(
    private val gitClient: McpGitClient = McpGitClient(),
    private val githubClient: McpGithubClient = McpGithubClient(),
    private val userIssueRepository: UserIssueRepository = UserIssueRepository(),
) {

    suspend fun servers(projectRoot: Path?): List<McpServerState> {
        log("Refreshing MCP servers for project=${projectRoot?.toString() ?: "none"}")
        val enabledMap = SettingsStore.load().mcpTools
        return buildServers(projectRoot).map { server ->
            McpServerState(
                id = server.id,
                name = server.name,
                description = server.description,
                online = server.online,
                tools = server.tools.map { tool ->
                    McpToolState(
                        id = tool.id,
                        serverId = server.id,
                        label = tool.label,
                        description = tool.description,
                        enabled = enabledMap[tool.id] ?: tool.enabledByDefault,
                    )
                },
            )
        }
    }

    suspend fun enabledTools(projectRoot: Path?): List<McpToolSummary> =
        servers(projectRoot)
            .flatMap { server -> server.tools.filter { it.enabled }.map { tool -> server to tool } }
            .map { (server, tool) ->
                McpToolSummary(
                    id = tool.id,
                    serverName = server.name,
                    description = tool.description,
                )
            }

    suspend fun setToolEnabled(toolId: String, enabled: Boolean, projectRoot: Path?): List<McpServerState> {
        SettingsStore.updateMcpTool(toolId, enabled)
        return servers(projectRoot)
    }

    suspend fun listPullRequests(projectRoot: Path?, limit: Int = 10): Result<List<PullRequestSummary>> =
        resolveGithubConfigOrError(projectRoot).mapCatching { config ->
            val listing = githubClient.fetchPullRequests(config, limit = limit).getOrThrow()
            listing.mapNotNull { pr ->
                val number = pr.number ?: return@mapNotNull null
                val detailed = runCatching { githubClient.fetchPullRequest(config, number).getOrThrow() }
                    .onFailure { log("Failed to fetch PR detail for #$number: ${it.message}") }
                    .getOrNull() ?: pr
                detailed.toSummary()
            }
        }

    suspend fun pullRequestReviewBundle(projectRoot: Path?, prNumber: Int): Result<PullRequestReviewBundle> =
        resolveGithubConfigOrError(projectRoot).mapCatching { config ->
            val detail = githubClient.fetchPullRequest(config, prNumber).getOrThrow()
            val summary = detail.toSummary() ?: error("Pull request $prNumber is missing required metadata.")
            val files = githubClient.fetchPullRequestFiles(config, prNumber).getOrThrow()
                .mapNotNull { file ->
                    val filename = file.filename ?: return@mapNotNull null
                    PullRequestFileDiff(
                        filename = filename,
                        status = file.status.orEmpty(),
                        additions = file.additions ?: 0,
                        deletions = file.deletions ?: 0,
                        changes = file.changes ?: 0,
                        patch = file.patch,
                    )
                }
            val diff = githubClient.fetchPullRequestDiff(config, prNumber).getOrThrow()
            PullRequestReviewBundle(
                summary = summary,
                files = files,
                diff = diff,
            )
        }

    suspend fun runTool(toolId: String, projectRoot: Path?): Result<String> {
        log("Executing tool request id=$toolId project=${projectRoot?.toString() ?: "none"}")
        val definitions = buildServers(projectRoot)
        val definition = definitions.flatMap { it.tools }.firstOrNull { it.id.equals(toolId, ignoreCase = true) }
            ?: return Result.failure(IllegalArgumentException("Unknown MCP tool: $toolId"))
        val enabled = SettingsStore.load().mcpTools[definition.id] ?: definition.enabledByDefault
        if (!enabled) {
            return Result.failure(IllegalStateException("Tool $toolId is disabled by the user"))
        }
        val result = definition.runner(projectRoot)
        result.onSuccess { log("Tool $toolId succeeded (${snippet(it)}).") }
        result.onFailure { log("Tool $toolId failed: ${it.message}") }
        return result
    }

    private suspend fun buildServers(projectRoot: Path?): List<McpServerDefinition> {
        val servers = mutableListOf<McpServerDefinition>()
        buildWorkspaceServer(projectRoot)?.let { servers += it }
        val githubSettings = LocalPropertiesConfig.githubSettings()
        if (githubSettings != null) {
            val resolved = resolveGithubConfig(githubSettings, projectRoot)
            if (resolved != null) {
                log("GitHub MCP configured for ${resolved.owner}/${resolved.repo}")
                servers += buildGithubServer(resolved)
            } else {
                log("GitHub MCP configuration missing owner/repo and could not be inferred.")
            }
        } else {
            log("No GitHub MCP token found in local.properties; skipping MCP servers.")
        }
        return servers
    }

    private fun buildWorkspaceServer(projectRoot: Path?): McpServerDefinition? {
        val hasProject = projectRoot != null
        val tools = listOf(
            McpToolDefinition(
                id = "workspace-user-issues",
                serverId = "workspace",
                label = "User issues",
                description = "Reads issues/user_issues.json from the selected project.",
                enabledByDefault = true,
                runner = { path ->
                    userIssueRepository.loadIssues(path).map { issues ->
                        userIssueRepository.formatForMcp(issues)
                    }
                },
            ),
        )
        return McpServerDefinition(
            id = "workspace",
            name = "Workspace",
            description = "Local tools for the currently selected project.",
            online = hasProject,
            tools = tools,
        )
    }

    private fun buildGithubServer(config: McpGithubConfig): McpServerDefinition {
        val repoLabel = "${config.owner}/${config.repo}"
        val tools = listOf(
            McpToolDefinition(
                id = "github-repo-overview",
                serverId = "github",
                label = "Repository overview",
                description = "Summarizes description, language, branch, and counters for $repoLabel.",
                enabledByDefault = true,
                runner = {
                    githubClient.fetchRepository(config).map { repo ->
                        buildString {
                            appendLine("Repository: $repoLabel")
                            repo.description?.let { appendLine("Description: $it") }
                            repo.language?.let { appendLine("Language: $it") }
                            repo.defaultBranch?.let { appendLine("Default branch: $it") }
                            repo.stars?.let { appendLine("Stars: $it") }
                            repo.openIssues?.let { appendLine("Open issues: $it") }
                            repo.htmlUrl?.let { appendLine("URL: $it") }
                        }.trim().ifBlank { "No metadata available for $repoLabel." }
                    }
                },
            ),
            McpToolDefinition(
                id = "github-open-issues",
                serverId = "github",
                label = "Open issues",
                description = "Lists the latest open GitHub issues for $repoLabel.",
                enabledByDefault = false,
                runner = {
                    githubClient.fetchOpenIssues(config).map { issues ->
                        if (issues.isEmpty()) {
                            "No open issues found for $repoLabel."
                        } else {
                            issues.joinToString(
                                prefix = "Open issues for $repoLabel:\n",
                                separator = "\n",
                            ) { issue ->
                                val author = issue.user?.login ?: "unknown"
                                "#${issue.number ?: "?"} ${issue.title.orEmpty()} (by $author) ${issue.htmlUrl.orEmpty()}"
                            }
                        }
                    }
                },
            ),
            McpToolDefinition(
                id = "github-open-prs",
                serverId = "github",
                label = "Open pull requests",
                description = "Lists the latest open pull requests for $repoLabel.",
                enabledByDefault = false,
                runner = {
                    githubClient.fetchOpenPullRequests(config).map { pulls ->
                        if (pulls.isEmpty()) {
                            "No open pull requests found for $repoLabel."
                        } else {
                            pulls.joinToString(
                                prefix = "Open pull requests for $repoLabel:\n",
                                separator = "\n",
                            ) { pr ->
                                val author = pr.user?.login ?: "unknown"
                                "PR #${pr.number ?: "?"} ${pr.title.orEmpty()} (by $author) ${pr.htmlUrl.orEmpty()}"
                            }
                        }
                    }
                },
            ),
            McpToolDefinition(
                id = "github-latest-commits",
                serverId = "github",
                label = "Recent commits",
                description = "Shows the latest commits merged into $repoLabel.",
                enabledByDefault = false,
                runner = {
                    githubClient.fetchLatestCommits(config).map { commits ->
                        if (commits.isEmpty()) {
                            "GitHub returned no recent commits for $repoLabel."
                        } else {
                            commits.joinToString(
                                prefix = "Latest commits for $repoLabel:\n",
                                separator = "\n",
                            ) { commit ->
                                val title = commit.commit?.message?.lineSequence()?.firstOrNull().orEmpty()
                                val author = commit.commit?.author?.name ?: "unknown"
                                val sha = commit.sha?.take(7) ?: "???????"
                                "$sha ${title.ifBlank { "(no message)" }} by $author ${commit.htmlUrl.orEmpty()}"
                            }
                        }
                    }
                },
            ),
            McpToolDefinition(
                id = "github-branches",
                serverId = "github",
                label = "Latest branches",
                description = "Lists the most recently updated branches for $repoLabel.",
                enabledByDefault = false,
                runner = {
                    githubClient.fetchBranches(config).map { branches ->
                        if (branches.isEmpty()) {
                            "No branches returned for $repoLabel."
                        } else {
                            branches.joinToString(
                                prefix = "Branches for $repoLabel:\n",
                                separator = "\n",
                            ) { branch ->
                                val sha = branch.commit?.sha?.take(7) ?: "???????"
                                "${branch.name ?: "unknown"} @ $sha"
                            }
                        }
                    }
                },
            ),
            McpToolDefinition(
                id = "github-top-contributors",
                serverId = "github",
                label = "Top contributors",
                description = "Lists the most active contributors for $repoLabel.",
                enabledByDefault = false,
                runner = {
                    githubClient.fetchContributors(config).map { contributors ->
                        if (contributors.isEmpty()) {
                            "GitHub returned no contributors for $repoLabel."
                        } else {
                            contributors.joinToString(
                                prefix = "Top contributors for $repoLabel:\n",
                                separator = "\n",
                            ) { contributor ->
                                val login = contributor.login ?: "unknown"
                                "$login (${contributor.contributions ?: 0} commits) ${contributor.htmlUrl.orEmpty()}"
                            }
                        }
                    }
                },
            ),
        )
        return McpServerDefinition(
            id = "github",
            name = "GitHub",
            description = "Model Context Protocol bridge for $repoLabel via the GitHub API.",
            online = true,
            tools = tools,
        )
    }

    private suspend fun resolveGithubConfigOrError(projectRoot: Path?): Result<McpGithubConfig> {
        val settings = LocalPropertiesConfig.githubSettings()
            ?: return Result.failure(IllegalStateException("GitHub MCP is not configured."))
        val config = resolveGithubConfig(settings, projectRoot)
            ?: return Result.failure(IllegalStateException("Unable to detect GitHub repository for the selected project."))
        return Result.success(config)
    }

    private suspend fun resolveGithubConfig(settings: McpGithubSettings, projectRoot: Path?): McpGithubConfig? {
        val owner = settings.owner
        val repo = settings.repo
        if (owner != null && repo != null) {
            val apiUrl = settings.apiUrl ?: DEFAULT_GITHUB_API
            log("Using explicit GitHub settings owner=$owner repo=$repo api=$apiUrl")
            return McpGithubConfig(
                token = settings.token,
                owner = owner,
                repo = repo,
                apiUrl = apiUrl,
            )
        }
        if (projectRoot == null) {
            log("Cannot derive GitHub repo without project path.")
            return null
        }
        val repoRef = gitClient.detectGithubRepo(projectRoot).getOrElse {
            log("Failed to detect git remote: ${it.message}")
            return null
        } ?: return null
        log("Detected GitHub remote ${repoRef.host}/${repoRef.owner}/${repoRef.repo}")
        val apiUrl = settings.apiUrl ?: defaultApiUrlForHost(repoRef.host)
        return McpGithubConfig(
            token = settings.token,
            owner = repoRef.owner,
            repo = repoRef.repo,
            apiUrl = apiUrl,
        )
    }

    private fun defaultApiUrlForHost(host: String): String {
        val normalizedHost = host.trim().trimEnd('/')
        return if (normalizedHost.equals("github.com", ignoreCase = true)) {
            DEFAULT_GITHUB_API
        } else {
            "https://$normalizedHost/api/v3"
        }
    }

    companion object {
        private const val DEFAULT_GITHUB_API = "https://api.github.com"
    }
}

private fun log(message: String) {
    println("[MCP] $message")
}

private fun snippet(text: String, max: Int = 120): String {
    val normalized = text.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
    return if (normalized.length <= max) normalized else normalized.take(max) + "..."
}

private fun GithubPullRequest.toSummary(): PullRequestSummary? {
    val number = number ?: return null
    return PullRequestSummary(
        number = number,
        title = title.orEmpty(),
        author = user?.login ?: "unknown",
        url = htmlUrl.orEmpty(),
        updatedAt = updatedAt.orEmpty(),
        body = body.orEmpty(),
        additions = additions ?: 0,
        deletions = deletions ?: 0,
        changedFiles = changedFiles ?: 0,
        baseBranch = base?.ref ?: base?.label ?: "unknown",
        headBranch = head?.ref ?: head?.label ?: "unknown",
    )
}

private data class McpServerDefinition(
    val id: String,
    val name: String,
    val description: String,
    val online: Boolean,
    val tools: List<McpToolDefinition>,
)

private data class McpToolDefinition(
    val id: String,
    val serverId: String,
    val label: String,
    val description: String,
    val enabledByDefault: Boolean,
    val runner: suspend (Path?) -> Result<String>,
)
