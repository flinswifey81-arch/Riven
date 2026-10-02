package com.shai.riven.data.provider.openrouter

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUrlConnectionOpenRouterHttpClientTest {
    @Test
    fun cancellationDisconnectsAReaderBlockedBeforeAnyResponseBytes() = runBlocking {
        val connection = BlockingHttpConnection()
        val client = HttpUrlConnectionOpenRouterHttpClient(Dispatchers.IO) { connection }
        val request = OpenRouterHttpRequest(
            method = "GET",
            url = "https://openrouter.ai/api/v1/models",
            headers = emptyMap(),
        )

        val call = async(Dispatchers.Default) { client.execute(request) { true } }
        assertTrue(connection.readEntered.await(2, TimeUnit.SECONDS))

        call.cancel()
        withTimeout(2_000) { call.join() }

        assertTrue("Cancellation must disconnect the blocking transport immediately", connection.disconnected)
    }

    private class BlockingHttpConnection : HttpURLConnection(URL("https://openrouter.ai")) {
        val readEntered = CountDownLatch(1)
        private val disconnectedLatch = CountDownLatch(1)

        @Volatile
        var disconnected = false
            private set

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
            disconnectedLatch.countDown()
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = 200

        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                readEntered.countDown()
                check(disconnectedLatch.await(2, TimeUnit.SECONDS)) {
                    "The connection was not disconnected while read was blocked"
                }
                return -1
            }
        }
    }
}
