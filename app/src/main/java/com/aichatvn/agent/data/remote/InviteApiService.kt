package com.aichatvn.agent.data.remote

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class InviteResult {
    data class Success(val code: String) : InviteResult()
    data class Error(val message: String) : InviteResult()
}

// ✅ MỚI: Client cho 3 endpoint mời/kích hoạt/tải trên app.py. QUAN TRỌNG: KHÔNG gửi
// GLOBAL_GATEWAY_TOKEN — /invites, /activate hoàn toàn độc lập với token đó (xem docstring
// create_invite() trong app.py). Chỉ cần base URL đúng (GLOBAL_GATEWAY_URL) + device_id.
@Singleton
class InviteApiService @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val logger: Logger
) {
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    private suspend fun baseUrl(): String =
        configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL, "").trimEnd('/')

    private suspend fun postJson(path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${baseUrl()}$path")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            executeAsync(request)
        }

    private suspend fun executeAsync(request: Request): JSONObject =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bodyStr = it.body?.string().orEmpty()
                        try {
                            cont.resume(JSONObject(bodyStr))
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    }
                }
            })
        }

    /** Device đã activate tạo mã mời mới. */
    suspend fun createInvite(deviceId: String): InviteResult = try {
        val json = postJson("/invites", JSONObject().put("device_id", deviceId))
        if (json.optString("status") == "success") {
            InviteResult.Success(json.getString("code"))
        } else {
            InviteResult.Error(json.optString("message", "Không tạo được mã mời."))
        }
    } catch (e: Exception) {
        logger.e("InviteApiService", "createInvite lỗi: ${e.message}")
        InviteResult.Error("Lỗi kết nối, thử lại sau.")
    }

    /** Kích hoạt device lần đầu bằng mã mời. */
    suspend fun activateInvite(code: String, deviceId: String): InviteResult = try {
        val json = postJson(
            "/invites/${code.trim()}/activate",
            JSONObject().put("device_id", deviceId)
        )
        if (json.optString("status") == "success") {
            InviteResult.Success(code)
        } else {
            InviteResult.Error(json.optString("message", "Mã mời không hợp lệ."))
        }
    } catch (e: Exception) {
        logger.e("InviteApiService", "activateInvite lỗi: ${e.message}")
        InviteResult.Error("Lỗi kết nối, thử lại sau.")
    }

    /** Trả về presigned R2 URL để tải APK (chỉ cần code hợp lệ, không cần device_id). */
    suspend fun getDownloadUrl(code: String): InviteResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/download/apk?code=${code.trim()}")
                .get()
                .build()
            val json = executeAsync(request)
            if (json.optString("status") == "success") {
                InviteResult.Success(json.getString("url"))
            } else {
                InviteResult.Error(json.optString("message", "Không lấy được link tải."))
            }
        } catch (e: Exception) {
            InviteResult.Error("Lỗi kết nối, thử lại sau.")
        }
    }
}
