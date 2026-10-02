package com.shai.riven.data.personality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockedRivenPersonalityContextSourceTest {
    @Test
    fun packagedLockedCanonPassesExactIntegrityCheck() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val result = LockedRivenPersonalityContextSource(context).read(request())

        assertTrue(result is RivenContextSourceResult.Success)
        val payload = (result as RivenContextSourceResult.Success).payloads.single()
        assertEquals(LockedRivenPersonalityContextSource.DOCUMENT_ID, payload.fragmentId)
        assertTrue(payload.content.contains("\"status\": \"LOCKED PERSONALITY CANON\""))
        assertTrue(payload.content.contains("\"document_id\": \"riven_app_personality_canon_2026_09_24\""))
    }

    @Test
    fun alteredCanonFailsClosedAsRequiredContext() = runBlocking {
        val result = LockedRivenPersonalityContextSource { "{}" }.read(request())

        assertTrue(result is RivenContextSourceResult.Failure)
    }

    @Test
    fun repositoryLineEndingConversionDoesNotAlterTheLockedCanon() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crlf = context.assets.open(LockedRivenPersonalityContextSource.ASSET_NAME)
            .bufferedReader()
            .use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\r\n")

        val result = LockedRivenPersonalityContextSource { crlf }.read(request())

        assertTrue(result is RivenContextSourceResult.Success)
    }

    private fun request() = RivenContextReadRequest(now = 100)
}
