package com.maimai.home.data

import com.maimai.home.data.models.AgentRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * RED phase: cancellation must propagate as kotlinx.coroutines.CancellationException,
 * not be swallowed/translated into AgentRequestException.
 *
 * Closes R1 #4 (Major - exception mapping breaks cancellation).
 */
class AgentClientCancellationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AgentClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
        client = AgentClient(okHttp, Json { ignoreUnknownKeys = true; explicitNulls = false })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cancelDuringSlowResponse_throwsCancellation() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
                .setBodyDelay(2, TimeUnit.SECONDS)
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deferred = scope.async {
            client.fetchStatus(server.url("/").toString())
        }

        // Allow request to start
        delay(150)
        deferred.cancel()

        var thrown: Throwable? = null
        try {
            deferred.await()
            fail("Expected cancellation, but call completed normally")
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull("Expected an exception", thrown)
        val isCancellation = thrown is kotlinx.coroutines.CancellationException ||
            generateSequence(thrown) { it.cause }.any { it is kotlinx.coroutines.CancellationException }
        val isAgentReqEx = thrown is AgentRequestException
        assertTrue(
            "Expected CancellationException but got \${thrown!!::class.qualifiedName}: \${thrown.message}",
            isCancellation && !isAgentReqEx
        )
        scope.cancel()
    }

    @Test
    fun cancelBeforeRequest_throwsCancellation() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.coroutineContext[Job]!!.cancel()

        var thrown: Throwable? = null
        try {
            scope.async {
                client.fetchStatus(server.url("/").toString())
            }.await()
            fail("Expected cancellation")
        } catch (e: Throwable) {
            thrown = e
        }
        assertNotNull(thrown)
        val isCancellation = thrown is kotlinx.coroutines.CancellationException ||
            generateSequence(thrown) { it.cause }.any { it is kotlinx.coroutines.CancellationException }
        assertTrue(
            "Expected CancellationException but got \${thrown!!::class.qualifiedName}: \${thrown.message}",
            isCancellation
        )
    }
}