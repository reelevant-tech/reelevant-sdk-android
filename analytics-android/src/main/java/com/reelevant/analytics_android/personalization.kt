package com.reelevant.analytics_android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

internal const val DEFAULT_RUNNER_URL = "https://reelevant.run"
internal const val DEFAULT_TIMEOUT = 5000L
private const val SDK_VERSION = "android-0.1.0"

// ---------------------------------------------------------------------------
// Error types
// ---------------------------------------------------------------------------

open class ReelevantError(message: String, cause: Throwable? = null) : Exception(message, cause)

class TimeoutError(val timeoutMs: Long) : ReelevantError("Runner call timed out after ${timeoutMs}ms")

class RunnerError(val status: Int, val body: String) : ReelevantError("Runner returned HTTP $status")

// ---------------------------------------------------------------------------
// Fallback strategies
// ---------------------------------------------------------------------------

sealed class FallbackStrategy {
    object Empty : FallbackStrategy()
    object Error : FallbackStrategy()
    class Custom(val handler: suspend (RunOptions, Exception) -> RunResult) : FallbackStrategy()
}

// ---------------------------------------------------------------------------
// Run options & response types
// ---------------------------------------------------------------------------

data class RunOptions(
    val workflowId: String,
    val entrypoint: String,
    val userId: String? = null,
    val params: Map<String, String>? = null,
    val locale: String? = null,
    val timeout: Long? = null
)

sealed class RunContent {
    data class Html(val content: String) : RunContent()
    data class Json(val content: JSONObject) : RunContent()
    data class Image(val content: ByteArray) : RunContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return content.contentEquals(other.content)
        }
        override fun hashCode(): Int = content.contentHashCode()
    }
    object Empty : RunContent()
}

enum class RunSource { RUNNER, FALLBACK }

class RunResult(
    val status: Int,
    val source: RunSource,
    val body: RunContent,
    val metadata: Map<String, Any>,
    val properties: Map<String, Any>,
    val runId: String?,
    val executionPath: List<String>,
    val redirectionUrl: String,
    internal val onTrackClick: suspend () -> Unit
) {
    /** Fire-and-forget click tracking. Calls the runner click endpoint without following redirects. */
    suspend fun trackClick() = onTrackClick()
}

// ---------------------------------------------------------------------------
// Internal runner helpers
// ---------------------------------------------------------------------------

internal suspend fun executeRunnerCall(
    options: RunOptions,
    runnerUrl: String,
    timeout: Long,
    userId: String
): RunResult = withContext(Dispatchers.IO) {
    val effectiveTimeout = options.timeout ?: timeout

    val url = buildRunnerUrl(runnerUrl, options, userId)
    val redirectionUrl = buildRedirectionUrl(runnerUrl, options, userId)

    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = effectiveTimeout.toInt()
            readTimeout = effectiveTimeout.toInt()
            setRequestProperty("x-rlvt-sdk-version", SDK_VERSION)
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = try {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }
            throw RunnerError(responseCode, errorBody)
        }

        val contentType = connection.contentType ?: ""
        val runId = connection.getHeaderField("x-rlvt-workflow-run-id")
        val executionPathHeader = connection.getHeaderField("x-rlvt-execution-path")
        val metadataHeader = connection.getHeaderField("x-rlvt-output-node-metadata")
        val propertiesHeader = connection.getHeaderField("x-rlvt-output-properties")

        val executionPath = executionPathHeader?.split(",") ?: emptyList()
        val metadata = metadataHeader?.let { safeJsonParseMap(it) } ?: emptyMap()
        val properties = propertiesHeader?.let { safeJsonParseMap(it) } ?: emptyMap()
        val body = parseResponseBody(connection, contentType)

        RunResult(
            status = responseCode,
            source = RunSource.RUNNER,
            body = body,
            metadata = metadata,
            properties = properties,
            runId = runId,
            executionPath = executionPath,
            redirectionUrl = redirectionUrl,
            onTrackClick = { fireAndForgetClick(redirectionUrl, effectiveTimeout) }
        )
    } catch (e: SocketTimeoutException) {
        throw TimeoutError(effectiveTimeout)
    } finally {
        connection?.disconnect()
    }
}

internal suspend fun fireAndForgetClick(url: String, timeout: Long) {
    try {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeout.toInt()
            connection.readTimeout = timeout.toInt()
            connection.setRequestProperty("x-rlvt-sdk-version", SDK_VERSION)
            connection.instanceFollowRedirects = false
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }
    } catch (_: Exception) {
        // Fire-and-forget — swallow all errors
    }
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

private fun buildRunnerUrl(
    runnerUrl: String,
    options: RunOptions,
    userId: String
): String {
    val base = "$runnerUrl/${options.workflowId}/${options.entrypoint}"
    val params = mutableListOf("rlvt-u=${enc(userId)}")
    options.locale?.let { params.add("locale=${enc(it)}") }
    options.params?.forEach { (k, v) -> if (k != "rlvt-u") params.add("${enc(k)}=${enc(v)}") }
    return "$base?${params.joinToString("&")}"
}

private fun buildRedirectionUrl(
    runnerUrl: String,
    options: RunOptions,
    userId: String
): String {
    return "$runnerUrl/${options.workflowId}/${options.entrypoint}?rlvt-u=${enc(userId)}&mode=click"
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun parseResponseBody(connection: HttpURLConnection, contentType: String): RunContent {
    val inputStream = connection.inputStream

    if (contentType.contains("text/html")) {
        val text = inputStream.bufferedReader().readText()
        return if (text.isBlank()) RunContent.Empty else RunContent.Html(text)
    }

    if (contentType.contains("image/")) {
        val buffer = ByteArrayOutputStream()
        inputStream.copyTo(buffer)
        val bytes = buffer.toByteArray()
        return if (bytes.isEmpty()) RunContent.Empty else RunContent.Image(bytes)
    }

    if (contentType.contains("application/json")) {
        val text = inputStream.bufferedReader().readText()
        return if (text.isBlank()) RunContent.Empty else RunContent.Json(JSONObject(text))
    }

    // Unknown content-type: try JSON, fall back to HTML
    val text = inputStream.bufferedReader().readText()
    if (text.isBlank()) return RunContent.Empty
    return try {
        RunContent.Json(JSONObject(text))
    } catch (_: Exception) {
        RunContent.Html(text)
    }
}

private fun safeJsonParseMap(str: String): Map<String, Any> {
    return try {
        val json = JSONObject(str)
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key -> map[key] = json.get(key) }
        map
    } catch (_: Exception) {
        emptyMap()
    }
}
