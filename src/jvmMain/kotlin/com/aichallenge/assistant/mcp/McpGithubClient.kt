package com.aichallenge.assistant.mcp

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class McpGithubClient(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 30_000
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    },
) {

    suspend fun fetchRepository(config: McpGithubConfig): Result<GithubRepository> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}") {
            githubHeaders(config.token)
        }.body()
    }

    suspend fun fetchOpenIssues(config: McpGithubConfig, limit: Int = 5): Result<List<GithubIssue>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/issues") {
            githubHeaders(config.token)
            parameter("state", "open")
            parameter("per_page", limit)
        }.body()
    }

    suspend fun fetchOpenPullRequests(config: McpGithubConfig, limit: Int = 5): Result<List<GithubPullRequest>> =
        fetchPullRequests(config, state = "open", limit = limit)

    suspend fun fetchPullRequests(
        config: McpGithubConfig,
        state: String = "open",
        limit: Int = 20,
    ): Result<List<GithubPullRequest>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/pulls") {
            githubHeaders(config.token)
            parameter("state", state)
            parameter("per_page", limit)
        }.body()
    }

    suspend fun fetchPullRequest(config: McpGithubConfig, number: Int): Result<GithubPullRequest> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/pulls/$number") {
            githubHeaders(config.token)
        }.body()
    }

    suspend fun fetchPullRequestDiff(config: McpGithubConfig, number: Int): Result<String> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/pulls/$number") {
            githubHeaders(config.token)
            header(HttpHeaders.Accept, "application/vnd.github.v3.diff")
        }.bodyAsText()
    }

    suspend fun fetchPullRequestFiles(
        config: McpGithubConfig,
        number: Int,
        limit: Int = 100,
    ): Result<List<GithubPullRequestFile>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/pulls/$number/files") {
            githubHeaders(config.token)
            parameter("per_page", limit)
        }.body()
    }

    suspend fun fetchLatestCommits(config: McpGithubConfig, limit: Int = 5): Result<List<GithubCommit>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/commits") {
            githubHeaders(config.token)
            parameter("per_page", limit)
        }.body()
    }

    suspend fun fetchBranches(config: McpGithubConfig, limit: Int = 5): Result<List<GithubBranch>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/branches") {
            githubHeaders(config.token)
            parameter("per_page", limit)
        }.body()
    }

    suspend fun fetchContributors(config: McpGithubConfig, limit: Int = 5): Result<List<GithubContributor>> = runCatching {
        httpClient.get("${config.apiUrl}/repos/${config.owner}/${config.repo}/contributors") {
            githubHeaders(config.token)
            parameter("per_page", limit)
        }.body()
    }

    private fun HttpRequestBuilder.githubHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header(HttpHeaders.UserAgent, "AI-Challenge-Assistant")
        header(HttpHeaders.ContentType, ContentType.Application.Json)
    }
}

@Serializable
data class GithubRepository(
    val name: String? = null,
    val description: String? = null,
    val language: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("open_issues_count") val openIssues: Int? = null,
    @SerialName("stargazers_count") val stars: Int? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GithubIssue(
    val number: Int? = null,
    val title: String? = null,
    val state: String? = null,
    val user: GithubUser? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GithubPullRequest(
    val number: Int? = null,
    val title: String? = null,
    val state: String? = null,
    val user: GithubUser? = null,
    val body: String? = null,
    val draft: Boolean? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    @SerialName("changed_files") val changedFiles: Int? = null,
    val base: GithubPullRequestBranch? = null,
    val head: GithubPullRequestBranch? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GithubUser(
    val login: String? = null,
)

@Serializable
data class GithubCommit(
    val sha: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val commit: GithubCommitDetail? = null,
) {
    @Serializable
    data class GithubCommitDetail(
        val message: String? = null,
        val author: GithubCommitAuthor? = null,
    )

    @Serializable
    data class GithubCommitAuthor(
        val name: String? = null,
    )
}

@Serializable
data class GithubBranch(
    val name: String? = null,
    val commit: GithubBranchCommit? = null,
) {
    @Serializable
    data class GithubBranchCommit(
        val sha: String? = null,
    )
}

@Serializable
data class GithubContributor(
    val login: String? = null,
    val contributions: Int? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class GithubPullRequestBranch(
    val label: String? = null,
    @SerialName("ref") val ref: String? = null,
)

@Serializable
data class GithubPullRequestFile(
    val filename: String? = null,
    val status: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val changes: Int? = null,
    val patch: String? = null,
)
