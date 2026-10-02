package com.shai.riven.data.provider.openrouter

import com.shai.riven.data.credential.ProviderSecret
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int?,
)

sealed interface OpenRouterModelCatalogResult {
    data class Success(val models: List<OpenRouterModel>) : OpenRouterModelCatalogResult
    data class Failure(val code: OpenRouterModelCatalogError) : OpenRouterModelCatalogResult
}

enum class OpenRouterModelCatalogError {
    AUTHENTICATION,
    RATE_LIMITED,
    INVALID_RESPONSE,
    UNAVAILABLE,
    TIMEOUT,
}

class OpenRouterModelCatalog(
    private val httpClient: OpenRouterHttpClient = HttpUrlConnectionOpenRouterHttpClient(),
) {
    suspend fun models(
        credential: ProviderSecret,
        endpointBaseUrl: String = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
    ): OpenRouterModelCatalogResult {
        val endpoint = modelsEndpointOrNull(endpointBaseUrl)
            ?: return OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.INVALID_RESPONSE)
        val body = StringBuilder()
        val response = try {
            httpClient.execute(
                OpenRouterHttpRequest(
                    method = "GET",
                    url = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${credential.reveal()}",
                        "Accept" to "application/json",
                        "X-OpenRouter-Title" to OpenRouterConversationAdapter.APP_TITLE,
                    ),
                    readTimeoutMillis = 30_000,
                    maxLineChars = MAX_CATALOG_CHARS,
                ),
            ) { line ->
                if (body.length.toLong() + line.length + 1L > MAX_CATALOG_CHARS) {
                    throw OpenRouterResponseLimitException()
                }
                body.append(line).append('\n')
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OpenRouterTransportTimeoutException) {
            return OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.TIMEOUT)
        } catch (_: Exception) {
            return OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.UNAVAILABLE)
        }
        if (response.statusCode !in 200..299) {
            return OpenRouterModelCatalogResult.Failure(
                when (response.statusCode) {
                    401, 403 -> OpenRouterModelCatalogError.AUTHENTICATION
                    429 -> OpenRouterModelCatalogError.RATE_LIMITED
                    else -> OpenRouterModelCatalogError.UNAVAILABLE
                },
            )
        }
        return try {
            val data = JSONObject(body.toString()).getJSONArray("data")
            val models = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    add(
                        OpenRouterModel(
                            id = id,
                            name = item.optString("name").takeIf(String::isNotBlank) ?: id,
                            contextLength = item.optInt("context_length").takeIf { it > 0 },
                        ),
                    )
                }
            }.distinctBy(OpenRouterModel::id).sortedBy { it.name.lowercase() }
            if (models.isEmpty()) {
                OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.INVALID_RESPONSE)
            } else {
                OpenRouterModelCatalogResult.Success(models)
            }
        } catch (_: JSONException) {
            OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.INVALID_RESPONSE)
        }
    }

    private fun modelsEndpointOrNull(baseUrl: String): String? = try {
        val uri = URI(baseUrl)
        if (uri.scheme?.lowercase() != "https" ||
            uri.host?.lowercase() != OpenRouterConversationAdapter.OPENROUTER_HOST ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        ) {
            null
        } else {
            baseUrl.trimEnd('/') + MODELS_PATH
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MODELS_PATH = "/models"
        const val MAX_CATALOG_CHARS = 8 * 1_024 * 1_024
    }
}
