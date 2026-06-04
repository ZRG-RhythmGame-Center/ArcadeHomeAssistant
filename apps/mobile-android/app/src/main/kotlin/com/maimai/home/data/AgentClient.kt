package com.maimai.home.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.maimai.home.data.models.AgentStatus
import com.maimai.home.data.models.ApiError
import com.maimai.home.data.models.AgentRequestException
import com.maimai.home.data.models.AudioDevice
import com.maimai.home.data.models.AudioState
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
import com.maimai.home.data.models.RemoteShutdownStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class FileListingResult(
    val entries: List<FileEntry>,
    val total: Int,
    val truncated: Boolean,
    /** Server-reported listing limit; defaults to the request limit. */
    val limit: Int = 200,
)

class AgentClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchStatus(address: String): AgentStatus = get(address, "/api/status", AgentStatus.serializer())

    suspend fun fetchAudioState(address: String): AudioState = get(address, "/api/audio/state", AudioState.serializer())

    suspend fun fetchAudioDevices(address: String): List<AudioDevice> = get(address, "/api/audio/devices", kotlinx.serialization.builtins.ListSerializer(AudioDevice.serializer()))

    suspend fun setVolume(address: String, level: Double): AudioState = postJson(
        address,
        "/api/audio/volume",
        VolumeRequest(level),
        VolumeRequest.serializer(),
        AudioState.serializer(),
    )

    suspend fun setMute(address: String, muted: Boolean): AudioState = postJson(
        address,
        "/api/audio/mute",
        MuteRequest(muted),
        MuteRequest.serializer(),
        AudioState.serializer(),
    )

    suspend fun switchDevice(address: String, deviceId: String): List<AudioDevice> = postJson(
        address,
        "/api/audio/default-device",
        DeviceRequest(deviceId),
        DeviceRequest.serializer(),
        kotlinx.serialization.builtins.ListSerializer(AudioDevice.serializer()),
    )

    suspend fun fetchFileRoots(address: String): List<FileRoot> = get(address, "/api/file-roots", kotlinx.serialization.builtins.ListSerializer(FileRoot.serializer()))

    suspend fun fetchRemoteShutdownStatus(address: String): RemoteShutdownStatus =
        get(address, "/api/power/shutdown", RemoteShutdownStatus.serializer())

    suspend fun executeRemoteShutdown(address: String, controlToken: String): RemoteShutdownStatus = postJson(
        address,
        "/api/power/shutdown",
        RemoteShutdownRequest(confirm = true),
        RemoteShutdownRequest.serializer(),
        RemoteShutdownStatus.serializer(),
        mapOf("Authorization" to "Bearer $controlToken"),
    )

    suspend fun fetchFiles(address: String, rootId: String, path: String, offset: Int = 0, limit: Int = 200): FileListingResult {
        val url = "${normalizedBaseUrl(address)}/api/files?rootId=${Uri.encode(rootId)}&path=${Uri.encode(path)}&offset=$offset&limit=$limit"
        return request(url, "GET", null, FileListingResult.serializer())
    }

    suspend fun uploadFile(address: String, rootId: String, path: String, file: File): Unit {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("rootId", rootId)
            .addFormDataPart("path", path)
            .addFormDataPart("overwrite", "false")
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        requestUnit("${normalizedBaseUrl(address)}/api/files/upload", "POST", body)
    }

    suspend fun uploadFile(address: String, rootId: String, path: String, contentResolver: ContentResolver, uri: Uri): Unit {
        // Tempfile copy + multipart upload must NOT run on the main thread.
        withContext(kotlinx.coroutines.Dispatchers.IO) { uploadFileBlocking(address, rootId, path, contentResolver, uri) }
    }

    private suspend fun uploadFileBlocking(address: String, rootId: String, path: String, contentResolver: ContentResolver, uri: Uri) {
        val temp = File.createTempFile("upload-", ".bin")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        } ?: throw AgentRequestException(ApiError(ApiError.Kind.Unknown, "读取文件失败"))

        val fileName = queryDisplayName(contentResolver, uri) ?: temp.name
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("rootId", rootId)
            .addFormDataPart("path", path)
            .addFormDataPart("overwrite", "false")
            .addFormDataPart("file", fileName, temp.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        try {
            requestUnit("${normalizedBaseUrl(address)}/api/files/upload", "POST", body)
        } finally {
            temp.delete()
        }
    }

    suspend fun downloadFile(address: String, rootId: String, path: String, target: File) {
        val request = Request.Builder()
            .url("${normalizedBaseUrl(address)}/api/files/download?rootId=${Uri.encode(rootId)}&path=${Uri.encode(path)}")
            .get()
            .build()
        // Stream the body on Dispatchers.IO — byteStream().copyTo() must not
        // run on the main thread (NetworkOnMainThreadException).
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            execute(request).use { response ->
                if (!response.isSuccessful) throw mapError(response.code, response.body?.string())
                val body = response.body ?: throw AgentRequestException(ApiError(ApiError.Kind.Network, "响应为空"))
                target.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
        }
    }

    suspend fun deleteFile(address: String, rootId: String, path: String) {
        requestUnit(
            "${normalizedBaseUrl(address)}/api/files",
            "DELETE",
            json.encodeToString(DeleteRequest.serializer(), DeleteRequest(rootId, path, true)).toRequestBody("application/json".toMediaType()),
        )
    }

    suspend fun renameFile(address: String, rootId: String, path: String, newName: String) {
        postJsonUnit(address, "/api/files/rename", RenameRequest(rootId, path, newName, true, false), RenameRequest.serializer())
    }

    suspend fun moveFile(address: String, rootId: String, fromPath: String, toPath: String) {
        postJsonUnit(address, "/api/files/move", MoveRequest(rootId, fromPath, toPath, true, false), MoveRequest.serializer())
    }

    private suspend fun <T> get(address: String, path: String, serializer: kotlinx.serialization.KSerializer<T>): T {
        return request("${normalizedBaseUrl(address)}$path", "GET", null, serializer)
    }

    private suspend fun <B, T> postJson(
        address: String,
        path: String,
        body: B,
        bodySerializer: kotlinx.serialization.KSerializer<B>,
        responseSerializer: kotlinx.serialization.KSerializer<T>,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody("application/json".toMediaType())
        return request("${normalizedBaseUrl(address)}$path", "POST", payload, responseSerializer, headers)
    }

    private suspend fun <B> postJsonUnit(address: String, path: String, body: B, bodySerializer: kotlinx.serialization.KSerializer<B>) {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody("application/json".toMediaType())
        requestUnit("${normalizedBaseUrl(address)}$path", "POST", payload)
    }

    private suspend fun requestUnit(url: String, method: String, body: okhttp3.RequestBody?) {
        val request = Request.Builder().url(url).method(method, body).build()
        // Run the entire response read on Dispatchers.IO. Android's StrictMode
        // (and the platform on UI-thread coroutines) flags response.body.string()
        // as NetworkOnMainThread because the body stream may still be reading
        // chunked-encoded data from the socket when string() is called.
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            execute(request).use { response ->
                if (!response.isSuccessful) throw mapError(response.code, response.body?.string())
            }
        }
    }

    private suspend fun <T> request(
        url: String,
        method: String,
        body: okhttp3.RequestBody?,
        serializer: kotlinx.serialization.KSerializer<T>,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val builder = Request.Builder().url(url).method(method, body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        // Stay on Dispatchers.IO for the body.string() read — see requestUnit().
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            execute(request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mapError(response.code, text)
                json.decodeFromString(serializer, text)
            }
        }
    }

    private suspend fun execute(request: Request): okhttp3.Response =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = okHttpClient.newCall(request)
                // Track the response so we can close it if the caller is
                // cancelled between resume() and the caller's first use.
                val responseRef = java.util.concurrent.atomic.AtomicReference<okhttp3.Response?>()
                cont.invokeOnCancellation {
                    call.cancel()
                    runCatching { responseRef.getAndSet(null)?.close() }
                }
                try {
                    val response = call.execute()
                    responseRef.set(response)
                    if (cont.isCancelled) {
                        // Lost the race — close immediately, don't leak.
                        runCatching { responseRef.getAndSet(null)?.close() }
                    } else {
                        cont.resume(response)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cont.cancel(e)
                } catch (e: java.net.SocketTimeoutException) {
                    cont.resumeWithException(AgentRequestException(ApiError(ApiError.Kind.Timeout, "连接超时")))
                } catch (e: java.io.IOException) {
                    if (cont.isCancelled) {
                        cont.cancel()
                    } else {
                        // Map specific IOException subtypes to user-friendly messages (R2 B7).
                        val message = when {
                            e is java.net.ConnectException ||
                                e.message?.contains("refused", ignoreCase = true) == true ||
                                e.message?.contains("ECONNREFUSED", ignoreCase = true) == true ->
                                "无法连接到 Agent，请确认地址、端口和防火墙设置。"
                            e is java.net.UnknownHostException ->
                                "无法解析 Agent 主机名，请检查地址。"
                            else -> e.message ?: "网络错误"
                        }
                        cont.resumeWithException(AgentRequestException(ApiError(ApiError.Kind.Network, message)))
                    }
                }
            }
        }

    private fun mapError(statusCode: Int, body: String?): AgentRequestException {
        val code = parseErrorCode(body)
        val serverMessage = parseErrorMessage(body)
        val error = when {
            statusCode == 401 -> ApiError(ApiError.Kind.Unauthorized, serverMessage ?: "未授权，请检查控制令牌", statusCode, code)
            statusCode == 404 -> ApiError(ApiError.Kind.NotFound, serverMessage ?: "未找到 Agent（404）", statusCode, code)
            statusCode == 503 -> ApiError(ApiError.Kind.Busy, serverMessage ?: "服务忙，请稍后重试", statusCode, code)
            statusCode == 502 -> ApiError(ApiError.Kind.DeviceUnavailable, serverMessage ?: "设备不可用", statusCode, code)
            statusCode == 413 -> ApiError(ApiError.Kind.FileTooLarge, "文件过大（超 100 MB）", statusCode, code)
            statusCode == 409 -> ApiError(ApiError.Kind.Conflict, serverMessage ?: "文件已存在", statusCode, code)
            else -> ApiError(ApiError.Kind.Unknown, body ?: "请求失败", statusCode, code)
        }
        return AgentRequestException(error)
    }

    private fun parseErrorCode(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(ErrorResponse.serializer(), body).error
        }.getOrNull()
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(ErrorResponse.serializer(), body).message?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun normalizedBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "empty address" }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        val host = LanAddressPolicy.extractHost(withScheme)
            ?: throw IllegalArgumentException("Refusing unparseable address \"$raw\"")
        LanAddressPolicy.requireLanHost(host)
        return withScheme
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    }
}

@Serializable private data class VolumeRequest(val level: Double)
@Serializable private data class MuteRequest(val muted: Boolean)
@Serializable private data class DeviceRequest(val deviceId: String)
@Serializable private data class DeleteRequest(val rootId: String, val path: String, val confirm: Boolean)
@Serializable private data class RenameRequest(val rootId: String, val path: String, val newName: String, val confirm: Boolean, val overwrite: Boolean)
@Serializable private data class MoveRequest(val rootId: String, val fromPath: String, val toPath: String, val confirm: Boolean, val overwrite: Boolean)
@Serializable private data class RemoteShutdownRequest(val confirm: Boolean)
@Serializable private data class ErrorResponse(val error: String, val message: String? = null)
