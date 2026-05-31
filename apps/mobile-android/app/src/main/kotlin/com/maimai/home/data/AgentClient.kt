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
        execute(request).use { response ->
            if (!response.isSuccessful) throw mapError(response.code, response.body?.string())
            val body = response.body ?: throw AgentRequestException(ApiError(ApiError.Kind.Network, "响应为空"))
            target.outputStream().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
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

    private suspend fun <B, T> postJson(address: String, path: String, body: B, bodySerializer: kotlinx.serialization.KSerializer<B>, responseSerializer: kotlinx.serialization.KSerializer<T>): T {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody("application/json".toMediaType())
        return request("${normalizedBaseUrl(address)}$path", "POST", payload, responseSerializer)
    }

    private suspend fun <B> postJsonUnit(address: String, path: String, body: B, bodySerializer: kotlinx.serialization.KSerializer<B>) {
        val payload = json.encodeToString(bodySerializer, body).toRequestBody("application/json".toMediaType())
        requestUnit("${normalizedBaseUrl(address)}$path", "POST", payload)
    }

    private suspend fun requestUnit(url: String, method: String, body: okhttp3.RequestBody?) {
        val request = Request.Builder().url(url).method(method, body).build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw mapError(response.code, response.body?.string())
        }
    }

    private suspend fun <T> request(url: String, method: String, body: okhttp3.RequestBody?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val request = Request.Builder().url(url).method(method, body).build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapError(response.code, text)
            return json.decodeFromString(serializer, text)
        }
    }

    private suspend fun execute(request: Request): okhttp3.Response =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = okHttpClient.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                try {
                    val response = call.execute()
                    cont.resume(response)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cont.cancel(e)
                } catch (e: java.net.SocketTimeoutException) {
                    cont.resumeWithException(AgentRequestException(ApiError(ApiError.Kind.Timeout, "连接超时")))
                } catch (e: java.io.IOException) {
                    if (cont.isCancelled) {
                        cont.cancel()
                    } else {
                        cont.resumeWithException(AgentRequestException(ApiError(ApiError.Kind.Network, e.message ?: "网络错误")))
                    }
                }
            }
        }

    private fun mapError(statusCode: Int, body: String?): AgentRequestException {
        val code = parseErrorCode(body)
        val error = when {
            statusCode == 404 -> ApiError(ApiError.Kind.NotFound, "未找到 Agent（404）", statusCode, code)
            statusCode == 503 -> ApiError(ApiError.Kind.Busy, "服务忙，请稍后重试", statusCode, code)
            statusCode == 502 -> ApiError(ApiError.Kind.DeviceUnavailable, "设备不可用", statusCode, code)
            statusCode == 413 -> ApiError(ApiError.Kind.FileTooLarge, "文件过大（超 100 MB）", statusCode, code)
            statusCode == 409 -> ApiError(ApiError.Kind.Conflict, "文件已存在", statusCode, code)
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

    private fun normalizedBaseUrl(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "empty address" }
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
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
@Serializable private data class ErrorResponse(val error: String)
