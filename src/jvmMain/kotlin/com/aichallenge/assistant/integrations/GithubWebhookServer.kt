package com.aichallenge.assistant.integrations

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIO
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GithubPullRequestEvent(
    val owner: String,
    val repo: String,
    val number: Int,
    val action: String,
    val ref: String?,
)

class GithubWebhookServer(
    private val port: Int,
    private val secret: String?,
    private val scope: CoroutineScope,
    private val handler: suspend (GithubPullRequestEvent) -> Unit,
) {

    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port) {
            routing {
                post("/github/webhook") {
                    val eventType = call.request.headers["X-GitHub-Event"]
                    val signatureHeader = call.request.headers["X-Hub-Signature-256"]
                    val payload = call.receiveText()
                    if (!verifySignature(payload, signatureHeader)) {
                        call.respondText("Invalid signature", status = HttpStatusCode.Unauthorized)
                        return@post
                    }

                    if (eventType != "pull_request") {
                        call.respondText("Ignored", status = HttpStatusCode.Accepted)
                        return@post
                    }

                    runCatching {
                        val json = jsonParser.parseToJsonElement(payload).jsonObject
                        val action = json["action"]?.jsonPrimitive?.content ?: "unknown"
                        val repository = json["repository"]?.jsonObject
                        val owner = repository?.get("owner")?.jsonObject?.get("login")?.jsonPrimitive?.content
                        val repo = repository?.get("name")?.jsonPrimitive?.content
                        val pull = json["pull_request"]?.jsonObject
                        val number = pull?.get("number")?.jsonPrimitive?.int
                        val headRef = pull?.get("head")?.jsonObject?.get("ref")?.jsonPrimitive?.content
                        if (owner != null && repo != null && number != null) {
                            scope.launch {
                                handler(
                                    GithubPullRequestEvent(
                                        owner = owner,
                                        repo = repo,
                                        number = number,
                                        action = action,
                                        ref = headRef,
                                    ),
                                )
                            }
                        }
                    }
                    call.respondText("Accepted", status = HttpStatusCode.Accepted)
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun verifySignature(payload: String, signatureHeader: String?): Boolean {
        val secret = secret ?: return true
        val computed = hmacSha256(secret, payload) ?: return false
        val expected = "sha256=$computed"
        return signatureHeader == expected
    }

    private fun hmacSha256(secret: String, value: String): String? =
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val bytes = mac.doFinal(value.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: InvalidKeyException) {
            null
        } catch (_: NoSuchAlgorithmException) {
            null
        }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }
}
