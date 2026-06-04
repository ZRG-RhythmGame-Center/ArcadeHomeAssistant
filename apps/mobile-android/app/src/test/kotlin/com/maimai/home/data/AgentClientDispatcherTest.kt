package com.maimai.home.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * AgentClient.execute() must run on Dispatchers.IO,
 * not on the caller's coroutine context.
 */
class AgentClientDispatcherTest {

    private lateinit var server: MockWebServer
    private val capturedThread = AtomicReference<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        capturedThread.set(null)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun executesOnIoDispatcher() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"deviceName\":\"x\",\"agentVersion\":\"1\",\"audio\":null,\"running\":true}")
        )
        val captureInterceptor = Interceptor { chain ->
            capturedThread.set(Thread.currentThread().name)
            chain.proceed(chain.request())
        }
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(captureInterceptor)
            .build()
        val client = AgentClient(okHttp, Json { ignoreUnknownKeys = true; explicitNulls = false })

        try {
            client.fetchStatus(server.url("/").toString())
        } catch (_: Throwable) {
            // Even if deserialization fails, we only care about the thread the call ran on.
        }

        val name = capturedThread.get()
        assertNotNull("Interceptor should have captured a thread name", name)
        // When execute() uses withContext(Dispatchers.IO), the blocking OkHttp call runs on
        // a DefaultDispatcher-worker thread. Without withContext, it runs on the test thread.
        val pattern = Pattern.compile("DefaultDispatcher.*-\\d+|OkHttp.*|.*[Ii][Oo].*-\\d+")
        assertTrue(
            "Expected thread to be on IO/OkHttp pool, got: $name",
            pattern.matcher(name).find()
        )
    }
}
