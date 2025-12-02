package com.aichallenge.assistant.model

data class PullRequestSummary(
    val number: Int,
    val title: String,
    val author: String,
    val url: String,
    val updatedAt: String,
    val body: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val baseBranch: String,
    val headBranch: String,
)

data class PullRequestFileDiff(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String?,
)

data class PullRequestReviewBundle(
    val summary: PullRequestSummary,
    val files: List<PullRequestFileDiff>,
    val diff: String,
)
