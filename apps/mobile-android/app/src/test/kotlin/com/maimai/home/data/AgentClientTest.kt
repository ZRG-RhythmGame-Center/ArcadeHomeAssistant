package com.maimai.home.data

import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * GREEN phase: downloadFile must throw a clear AgentRequestException
 * when the response body is null, instead of silently doing nothing
 * and producing an empty file.
 *
 * Closes R1 #17 (Minor - download null body).
 */
class AgentClientTest {

    @Test
    fun downloadFile_nullBody_throws() = runBlocking {
        // Build a mock OkHttpClient that returns a 200 response with null body.
        // OkHttp 4.x never returns null body from real HTTP, so we use Mockito to
        // exercise the defensive null-body check in downloadFile().
        val nullBodyResponse = Response.Builder()
            .request(Request.Builder().url("http://localhost/api/files/download?rootId=root&path=%2Ffile.txt").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(null)
            .build()

        val mockCall = mock(Call::class.java)
        `when`(mockCall.execute()).thenReturn(nullBodyResponse)

        val mockOkHttp = mock(OkHttpClient::class.java)
        // Generic unchecked cast bypasses Kotlin's null-safety for Mockito's any() placeholder.
        @Suppress("UNCHECKED_CAST")
        fun <T> anyMatcher(): T = org.mockito.ArgumentMatchers.any<T>() as T
        org.mockito.Mockito.doReturn(mockCall).`when`(mockOkHttp).newCall(anyMatcher())

        val client = AgentClient(mockOkHttp, Json { ignoreUnknownKeys = true; explicitNulls = false })

        val target = File.createTempFile("download-test", ".bin").apply { delete() }
        var thrown: Throwable? = null
        try {
            client.downloadFile("http://localhost", "root", "/file.txt", target)
            fail("Expected AgentRequestException for null body, but call succeeded")
        } catch (e: AgentRequestException) {
            thrown = e
        } catch (e: Throwable) {
            thrown = e
        } finally {
            if (target.exists()) target.delete()
        }

        assertEquals(
            "Expected AgentRequestException, got " + thrown?.javaClass?.name + ": " + thrown?.message,
            AgentRequestException::class.java,
            thrown!!.javaClass
        )
        val apiError = (thrown as AgentRequestException).apiError
        assertEquals(ApiError.Kind.Network, apiError.kind)
        assertEquals("响应为空", apiError.message)
    }
}