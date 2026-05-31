package com.maimai.home.data

import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * RED phase: downloadFile must throw a clear AgentRequestException
 * when the response body is null, instead of silently doing nothing
 * and producing an empty file.
 *
 * Closes R1 #17 (Minor - download null body).
 */
class AgentClientTest {

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
    fun downloadFile_nullBody_throws() = runBlocking {
        // 304 Not Modified has no body by HTTP spec; OkHttp surfaces body as null.
        server.enqueue(
            MockResponse()
                .setResponseCode(304)
        )

        val target = File.createTempFile("download-test", ".bin").apply { delete() }
        var thrown: Throwable? = null
        try {
            client.downloadFile(server.url("/").toString(), "root", "/file.txt", target)
            fail("Expected AgentRequestException for null body, but call succeeded")
        } catch (e: AgentRequestException) {
            thrown = e
        } catch (e: Throwable) {
            thrown = e
        } finally {
            if (target.exists()) target.delete()
        }

        assertEquals(
            "Expected AgentRequestException, got \${thrown?.javaClass?.name}: \${thrown?.message}",
            AgentRequestException::class.java,
            thrown!!.javaClass
        )
        val apiError = (thrown as AgentRequestException).apiError
        assertEquals(ApiError.Kind.Network, apiError.kind)
        assertEquals("响应为空", apiError.message)
    }
}