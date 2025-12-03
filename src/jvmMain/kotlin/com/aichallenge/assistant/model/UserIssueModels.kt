package com.aichallenge.assistant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserIssue(
    @SerialName("userName")
    val userName: String,
    @SerialName("issue")
    val issue: IssueDetails,
)

@Serializable
data class IssueDetails(
    @SerialName("issueId")
    val issueId: String,
    @SerialName("issueNumber")
    val issueNumber: Int,
    @SerialName("subject")
    val subject: String,
    @SerialName("issue")
    val description: String,
)
