package com.shai.riven.data.provider.openrouter

import com.shai.riven.data.credential.ProviderSecret
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterModelCatalogTest {
    @Test
    fun loadsAuthenticatedSortedDistinctModels() = runBlocking {
        var captured: OpenRouterHttpRequest? = null
        val catalog = OpenRouterModelCatalog { request, onLine ->
            captured = request
            onLine(
                """{"data":[{"id":"z/model","name":"Zulu","context_length":8192},{"id":"a/model","name":"Alpha","context_length":4096},{"id":"a/model","name":"Duplicate"}]}""",
            )
            OpenRouterHttpResponse(200, emptyMap())
        }

        val result = catalog.models(ProviderSecret.fromPlaintext("catalog-secret"))

        assertTrue(result is OpenRouterModelCatalogResult.Success)
        assertEquals(listOf("a/model", "z/model"), (result as OpenRouterModelCatalogResult.Success).models.map { it.id })
        assertEquals("GET", captured?.method)
        assertEquals("https://openrouter.ai/api/v1/models", captured?.url)
        assertEquals("Bearer catalog-secret", captured?.headers?.get("Authorization"))
        assertEquals(null, captured?.body)
    }

    @Test
    fun reportsAuthenticationAndInvalidPayloadWithoutInventingModels() = runBlocking {
        val unauthorized = OpenRouterModelCatalog { _, _ -> OpenRouterHttpResponse(401, emptyMap()) }
        assertEquals(
            OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.AUTHENTICATION),
            unauthorized.models(ProviderSecret.fromPlaintext("bad")),
        )

        val malformed = OpenRouterModelCatalog { _, onLine ->
            onLine("not-json")
            OpenRouterHttpResponse(200, emptyMap())
        }
        assertEquals(
            OpenRouterModelCatalogResult.Failure(OpenRouterModelCatalogError.INVALID_RESPONSE),
            malformed.models(ProviderSecret.fromPlaintext("bad")),
        )
    }

    @Test
    fun distinguishesSupportedUnsupportedAndUnknownImageCapabilities() = runBlocking {
        val catalog = OpenRouterModelCatalog { _, onLine ->
            onLine(
                """{"data":[{"id":"vision","architecture":{"input_modalities":["text","image"]}},{"id":"text","architecture":{"input_modalities":["text"]}},{"id":"unknown"}]}""",
            )
            OpenRouterHttpResponse(200, emptyMap())
        }

        val models = (catalog.models(ProviderSecret.fromPlaintext("key")) as
            OpenRouterModelCatalogResult.Success).models.associateBy(OpenRouterModel::id)

        assertEquals(OpenRouterImageInputCapability.SUPPORTED, models.getValue("vision").imageInputCapability)
        assertEquals(OpenRouterImageInputCapability.UNSUPPORTED, models.getValue("text").imageInputCapability)
        assertEquals(OpenRouterImageInputCapability.UNKNOWN, models.getValue("unknown").imageInputCapability)
    }
}
