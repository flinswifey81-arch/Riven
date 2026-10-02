package com.shai.riven.data.provider.openrouter

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class OpenRouterHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
    val connectTimeoutMillis: Int = 20_000,
    val readTimeoutMillis: Int = 120_000,
    val maxLineChars: Int = 1_048_576,
)

data class OpenRouterHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
)

fun interface OpenRouterHttpClient {
    suspend fun execute(
        request: OpenRouterHttpRequest,
        onLine: suspend (String) -> Unit,
    ): OpenRouterHttpResponse
}

class HttpUrlConnectionOpenRouterHttpClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OpenRouterHttpClient {
    override suspend fun execute(
        request: OpenRouterHttpRequest,
        onLine: suspend (String) -> Unit,
    ): OpenRouterHttpResponse = withContext(dispatcher) {
        require(request.method == "GET" || request.method == "POST")
        require(request.maxLineChars > 0)
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = request.connectTimeoutMillis
            readTimeout = request.readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            doOutput = request.body != null
        }
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            request.body?.let { body ->
                coroutineContext.ensureActive()
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readBoundedLine(request.maxLineChars) ?: break
                        onLine(line)
                    }
                }
            }
            OpenRouterHttpResponse(
                statusCode = status,
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { (key, _) -> checkNotNull(key) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            throw OpenRouterTransportTimeoutException(timeout)
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
    }

    private fun BufferedReader.readBoundedLine(maxChars: Int): String? {
        val line = StringBuilder()
        while (true) {
            val next = read()
            if (next == -1) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString()
            if (next != '\r'.code) {
                if (line.length == maxChars) throw OpenRouterResponseLimitException()
                line.append(next.toChar())
            }
        }
    }
}

class OpenRouterTransportTimeoutException(cause: Throwable) : RuntimeException(cause)

class OpenRouterResponseLimitException : RuntimeException()
