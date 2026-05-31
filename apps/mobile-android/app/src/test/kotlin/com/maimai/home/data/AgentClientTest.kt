package com.maimai.home.data

import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.ApiError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

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

/**
 * Wave 3 task 13: comprehensive MockWebServer-driven coverage of every
 * public AgentClient endpoint, plus the documented error mappings.
 *
 * Robolectric is required so [android.net.Uri.encode] resolves while
 * AgentClient builds query parameters for fetchFiles and downloadFile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentClientMockWebServerTest {

    private lateinit var server: MockWebServer
    private lateinit var okHttp: OkHttpClient
    private lateinit var json: Json
    private lateinit var client: AgentClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        okHttp = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        client = AgentClient(okHttp, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun address(): String = server.url("/").toString().removeSuffix("/")

    // -------------------------- fetchStatus ---------------------------------

    @Test
    fun fetchStatus_happyPath_parsesAgentStatus() = runBlocking {
        val body = """
            {
              "machineName": "DESKTOP-ABC",
              "version": "1.2.3",
              "uptimeSeconds": 4242,
              "capabilities": {
                "audioVolume": true,
                "audioMute": true,
                "audioDeviceSwitch": false,
                "fileManagement": true,
                "discoveryBroadcast": false
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val status = client.fetchStatus(address())

        assertThat(status.machineName).isEqualTo("DESKTOP-ABC")
        assertThat(status.version).isEqualTo("1.2.3")
        assertThat(status.uptimeSeconds).isEqualTo(4242L)
        assertThat(status.capabilities.audioVolume).isTrue()
        assertThat(status.capabilities.audioMute).isTrue()
        assertThat(status.capabilities.audioDeviceSwitch).isFalse()
        assertThat(status.capabilities.fileManagement).isTrue()
        assertThat(status.capabilities.discoveryBroadcast).isFalse()

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.path).isEqualTo("/api/status")
    }

    @Test
    fun fetchStatus_extraUnknownCapabilityField_isIgnored() = runBlocking {
        // Closes R1 #18 forward-compat: unknown server-side fields must not break decoding.
        val body = """
            {
              "machineName": "M",
              "version": "9.9",
              "uptimeSeconds": 1,
              "capabilities": {
                "audioVolume": true,
                "audioMute": false,
                "audioDeviceSwitch": false,
                "fileManagement": false,
                "discoveryBroadcast": false,
                "extraCapability": true,
                "anotherFutureField": "foo"
              },
              "futureTopLevel": 99
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val status = client.fetchStatus(address())

        assertThat(status.machineName).isEqualTo("M")
        assertThat(status.capabilities.audioVolume).isTrue()
    }

    @Test
    fun fetchStatus_404_mapsToNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))

        val ex = assertAgentException { client.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.NotFound)
        assertThat(ex.apiError.statusCode).isEqualTo(404)
        assertThat(ex.apiError.message).isEqualTo("未找到 Agent（404）")
    }

    @Test
    fun fetchStatus_503_mapsToBusy() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))

        val ex = assertAgentException { client.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.Busy)
        assertThat(ex.apiError.statusCode).isEqualTo(503)
        assertThat(ex.apiError.message).isEqualTo("服务忙，请稍后重试")
    }

    @Test
    fun fetchStatus_502_mapsToDeviceUnavailable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody(""))

        val ex = assertAgentException { client.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.DeviceUnavailable)
        assertThat(ex.apiError.statusCode).isEqualTo(502)
        assertThat(ex.apiError.message).isEqualTo("设备不可用")
    }

    @Test
    fun fetchStatus_500_mapsToUnknownAndIncludesBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal-explosion"))

        val ex = assertAgentException { client.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.Unknown)
        assertThat(ex.apiError.statusCode).isEqualTo(500)
        assertThat(ex.apiError.message).contains("internal-explosion")
    }

    @Test
    fun fetchStatus_malformedJson_propagatesSerializationException() = runBlocking {
        // Characterization test: production currently lets SerializationException
        // bubble out of decodeFromString; this test pins that contract.
        server.enqueue(MockResponse().setResponseCode(200).setBody("this-is-not-json"))

        var caught: Throwable? = null
        try {
            client.fetchStatus(address())
            fail("Expected SerializationException")
        } catch (t: Throwable) {
            caught = t
        }
        assertThat(caught).isNotNull()
        val isSerialization = caught is SerializationException ||
            generateSequence(caught) { it.cause }.any { it is SerializationException }
        assertTrue(
            "Expected SerializationException, got ${caught!!::class.qualifiedName}: ${caught.message}",
            isSerialization
        )
    }

    @Test
    fun fetchStatus_addressWithoutScheme_prependsHttp() = runBlocking {
        // normalizedBaseUrl prepends http:// when the supplied address lacks a scheme.
        val body = """
            {
              "machineName": "noscheme",
              "version": "1",
              "uptimeSeconds": 0,
              "capabilities": {
                "audioVolume": false,
                "audioMute": false,
                "audioDeviceSwitch": false,
                "fileManagement": false,
                "discoveryBroadcast": false
              }
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val noScheme = address().removePrefix("http://").removeSuffix("/")

        val status = client.fetchStatus(noScheme)

        assertThat(status.machineName).isEqualTo("noscheme")
    }

    // -------------------------- audio endpoints ----------------------------

    @Test
    fun fetchAudioState_parsesStateAndAllowsAbsentDefaultDeviceId() = runBlocking {
        // defaultDeviceId is a nullable field with a default of null; absent in
        // the JSON should not break decoding.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"masterVolume":0.65,"muted":false}""")
        )

        val state = client.fetchAudioState(address())

        assertThat(state.masterVolume).isEqualTo(0.65)
        assertThat(state.muted).isFalse()
        assertThat(state.defaultDeviceId).isNull()
        assertThat(server.takeRequest().path).isEqualTo("/api/audio/state")
    }

    @Test
    fun fetchAudioDevices_parsesListOfDevices() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"id":"dev-1","name":"Speakers","isDefault":true,"state":"Active"},
                  {"id":"dev-2","name":"HDMI","isDefault":false,"state":"Disabled"}
                ]
                """.trimIndent()
            )
        )

        val devices = client.fetchAudioDevices(address())

        assertThat(devices).hasSize(2)
        assertThat(devices[0].id).isEqualTo("dev-1")
        assertThat(devices[0].isDefault).isTrue()
        assertThat(devices[1].name).isEqualTo("HDMI")
        assertThat(devices[1].state).isEqualTo("Disabled")
        assertThat(server.takeRequest().path).isEqualTo("/api/audio/devices")
    }

    @Test
    fun setVolume_postsLevelJsonAndParsesAudioState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"masterVolume":0.42,"muted":false,"defaultDeviceId":"dev-x"}""")
        )

        val state = client.setVolume(address(), 0.42)

        assertThat(state.masterVolume).isEqualTo(0.42)
        assertThat(state.muted).isFalse()
        assertThat(state.defaultDeviceId).isEqualTo("dev-x")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/audio/volume")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["level"]?.jsonPrimitive?.double).isEqualTo(0.42)
    }

    @Test
    fun setMute_postsMutedJsonAndParsesAudioState() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"masterVolume":0.10,"muted":true}""")
        )

        val state = client.setMute(address(), true)

        assertThat(state.muted).isTrue()
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/audio/mute")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["muted"]?.jsonPrimitive?.boolean).isTrue()
    }

    @Test
    fun switchDevice_postsDeviceIdJsonAndReturnsDeviceList() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                  {"id":"abc","name":"New default","isDefault":true,"state":"Active"}
                ]
                """.trimIndent()
            )
        )

        val devices = client.switchDevice(address(), "abc")

        assertThat(devices).hasSize(1)
        assertThat(devices.first().id).isEqualTo("abc")
        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/audio/default-device")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["deviceId"]?.jsonPrimitive?.content).isEqualTo("abc")
    }

    // -------------------------- file roots & listings ---------------------

    @Test
    fun fetchFileRoots_parsesRoots() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""[{"id":"root","name":"Root","readOnly":false}]""")
        )

        val roots = client.fetchFileRoots(address())

        assertThat(roots).hasSize(1)
        assertThat(roots[0].id).isEqualTo("root")
        assertThat(roots[0].name).isEqualTo("Root")
        assertThat(roots[0].readOnly).isFalse()
        assertThat(server.takeRequest().path).isEqualTo("/api/file-roots")
    }

    @Test
    fun fetchFiles_buildsUrlWithEncodedQueryParams() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"entries":[],"total":0,"truncated":false}""")
        )

        val result = client.fetchFiles(address(), "root", "/sub dir/x", offset = 0, limit = 200)

        assertThat(result.entries).isEmpty()
        assertThat(result.total).isEqualTo(0)
        assertThat(result.truncated).isFalse()

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        val recordedPath = recorded.path ?: ""
        assertThat(recordedPath).startsWith("/api/files?")
        assertThat(recordedPath).contains("rootId=root")
        // Uri.encode replaces space with %20 and "/" with %2F.
        assertThat(recordedPath).contains("path=%2Fsub%20dir%2Fx")
        assertThat(recordedPath).contains("offset=0")
        assertThat(recordedPath).contains("limit=200")
    }

    @Test
    fun fetchFiles_truncated_resultExposesFlagAndEntries() = runBlocking {
        val entriesJson = (1..200).joinToString(",") { i ->
            """{"name":"f$i","kind":"file","size":${i.toLong()},"modified":"2026-01-01T00:00:00Z"}"""
        }
        val body = """{"entries":[$entriesJson],"total":500,"truncated":true}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client.fetchFiles(address(), "root", "/", offset = 0, limit = 200)

        assertThat(result.entries).hasSize(200)
        assertThat(result.total).isEqualTo(500)
        assertThat(result.truncated).isTrue()
        assertThat(result.entries.first().name).isEqualTo("f1")
        assertThat(result.entries.last().name).isEqualTo("f200")
    }

    // -------------------------- upload / download -------------------------

    @Test
    fun uploadFile_postsMultipartWithExpectedParts() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val payload = "hello-payload-bytes".toByteArray()
        val tempFile = File.createTempFile("upload-", ".bin").apply { writeBytes(payload) }

        try {
            client.uploadFile(address(), "root", "/uploads/dest.bin", tempFile)
        } finally {
            tempFile.delete()
        }

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/files/upload")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("name=\"rootId\"")
        assertThat(body).contains("name=\"path\"")
        assertThat(body).contains("name=\"overwrite\"")
        assertThat(body).contains("name=\"file\"")
        assertThat(body).contains("filename=\"${tempFile.name}\"")
        // form-data values are followed by CRLF + value + CRLF in multipart bodies.
        assertThat(body).contains("\r\nroot\r\n")
        assertThat(body).contains("\r\n/uploads/dest.bin\r\n")
        assertThat(body).contains("\r\nfalse\r\n")
        assertThat(body).contains("hello-payload-bytes")
    }

    @Test
    fun uploadFile_413_mapsToFileTooLarge() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413).setBody(""))
        val tempFile = File.createTempFile("upload-", ".bin").apply { writeBytes(ByteArray(8)) }

        val ex = assertAgentException {
            try {
                client.uploadFile(address(), "root", "/p", tempFile)
            } finally {
                tempFile.delete()
            }
        }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.FileTooLarge)
        assertThat(ex.apiError.statusCode).isEqualTo(413)
        assertThat(ex.apiError.message).isEqualTo("文件过大（超 100 MB）")
    }

    @Test
    fun uploadFile_409_mapsToConflict() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(""))
        val tempFile = File.createTempFile("upload-", ".bin").apply { writeBytes(ByteArray(4)) }

        val ex = assertAgentException {
            try {
                client.uploadFile(address(), "root", "/p", tempFile)
            } finally {
                tempFile.delete()
            }
        }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.Conflict)
        assertThat(ex.apiError.statusCode).isEqualTo(409)
        assertThat(ex.apiError.message).isEqualTo("文件已存在")
    }

    @Test
    fun downloadFile_writesBodyBytesToTarget() = runBlocking {
        val payload = "hello-download-bytes".toByteArray()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(Buffer().write(payload))
                .setHeader("Content-Type", "application/octet-stream")
        )
        val target = File.createTempFile("download-", ".bin").apply { delete() }

        try {
            client.downloadFile(address(), "root", "/file.bin", target)
            assertThat(target.readBytes()).isEqualTo(payload)
        } finally {
            if (target.exists()) target.delete()
        }

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        val recordedPath = recorded.path ?: ""
        assertThat(recordedPath).startsWith("/api/files/download?")
        assertThat(recordedPath).contains("rootId=root")
        assertThat(recordedPath).contains("path=%2Ffile.bin")
    }

    @Test
    fun downloadFile_404_mapsToNotFoundAndDoesNotCreateTarget() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        val target = File.createTempFile("download-404-", ".bin").apply { delete() }

        val ex = assertAgentException {
            client.downloadFile(address(), "root", "/missing", target)
        }

        try {
            assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.NotFound)
            assertThat(ex.apiError.statusCode).isEqualTo(404)
            assertThat(target.exists()).isFalse()
        } finally {
            if (target.exists()) target.delete()
        }
    }

    // -------------------------- delete / rename / move --------------------

    @Test
    fun deleteFile_sendsDeleteWithJsonBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        client.deleteFile(address(), "root", "/x/y.txt")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("DELETE")
        assertThat(recorded.path).isEqualTo("/api/files")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["rootId"]?.jsonPrimitive?.content).isEqualTo("root")
        assertThat(bodyJson["path"]?.jsonPrimitive?.content).isEqualTo("/x/y.txt")
        assertThat(bodyJson["confirm"]?.jsonPrimitive?.boolean).isTrue()
    }

    @Test
    fun renameFile_postsRenameJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        client.renameFile(address(), "root", "/x/y.txt", "z.txt")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/files/rename")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["rootId"]?.jsonPrimitive?.content).isEqualTo("root")
        assertThat(bodyJson["path"]?.jsonPrimitive?.content).isEqualTo("/x/y.txt")
        assertThat(bodyJson["newName"]?.jsonPrimitive?.content).isEqualTo("z.txt")
        assertThat(bodyJson["confirm"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(bodyJson["overwrite"]?.jsonPrimitive?.boolean).isFalse()
    }

    @Test
    fun moveFile_postsMoveJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        client.moveFile(address(), "root", "/from/a.txt", "/to/b.txt")

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/api/files/move")
        val bodyJson = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertThat(bodyJson["rootId"]?.jsonPrimitive?.content).isEqualTo("root")
        assertThat(bodyJson["fromPath"]?.jsonPrimitive?.content).isEqualTo("/from/a.txt")
        assertThat(bodyJson["toPath"]?.jsonPrimitive?.content).isEqualTo("/to/b.txt")
        assertThat(bodyJson["confirm"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(bodyJson["overwrite"]?.jsonPrimitive?.boolean).isFalse()
    }

    // -------------------------- error code wiring -------------------------

    @Test
    fun errorResponseJson_propagatesErrorCode() = runBlocking {
        // 400 falls through mapError's else branch (kind = Unknown) but still
        // parses the ErrorResponse body and exposes the server-side code.
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":"E_SOMETHING"}""")
        )

        val ex = assertAgentException { client.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.Unknown)
        assertThat(ex.apiError.statusCode).isEqualTo(400)
        assertThat(ex.apiError.code).isEqualTo("E_SOMETHING")
    }

    // -------------------------- timeout mapping ---------------------------

    @Test
    fun fetchStatus_socketTimeout_mapsToTimeoutKind() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortClient = AgentClient(
            OkHttpClient.Builder()
                .connectTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .writeTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
            Json { ignoreUnknownKeys = true; explicitNulls = false }
        )

        val ex = assertAgentException { shortClient.fetchStatus(address()) }

        assertThat(ex.apiError.kind).isEqualTo(ApiError.Kind.Timeout)
        assertThat(ex.apiError.message).isEqualTo("连接超时")
    }

    // -------------------------- helper ------------------------------------

    private inline fun assertAgentException(block: () -> Unit): AgentRequestException {
        val caught: Throwable? = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        if (caught == null) {
            fail("Expected AgentRequestException, but call returned normally")
            error("unreachable")
        }
        if (caught !is AgentRequestException) {
            fail(
                "Expected AgentRequestException, got " + caught::class.qualifiedName + ": " + caught.message
            )
            error("unreachable")
        }
        return caught
    }
}
