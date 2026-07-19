package com.aichatvn.agent.skills

import com.aichatvn.agent.config.AppConfigDefaults
import com.aichatvn.agent.config.AppConfigProvider
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FacebookSkill @Inject constructor(
    private val configProvider: AppConfigProvider,
    private val database: AppDatabase, // ✅ ĐÃ THÊM: Inject database để lấy Page Token theo từng Page ID
    logger: Logger
) : BaseSkill("facebook", "Facebook Assistant", logger) {

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(background = true),
        routable = false,
        visibleOnDashboard = false,
        autoGenerateQA = false,
        actions = listOf(
            PluginAction(
                name = "post_status",
                description = "Đăng một trạng thái hoặc bài viết mới lên Facebook",
                examples = listOf("đăng bài viết lên facebook", "post bài fb"),
                parameters = listOf(
                    PluginParameter("content", "string", "Nội dung bài viết", true, "string")
                )
            ),
            PluginAction(
                name = "send_messenger",
                description = "Gửi một tin nhắn trực tiếp qua Messenger đến ID khách hàng cụ thể",
                examples = listOf("gửi tin nhắn facebook cho", "inbox messenger"),
                parameters = listOf(
                    PluginParameter("recipient_id", "string", "ID người nhận (PSID)", true, "string"),
                    PluginParameter("message", "string", "Nội dung tin nhắn cần gửi", false, "string"), // ✅ SỬA: không còn bắt buộc — cho phép gửi CHỈ ảnh, không kèm text
                    PluginParameter("page_id", "string", "ID của Fanpage gửi tin", false, "string"), // ✅ ĐÃ THÊM: Nhận diện Page ID động
                    PluginParameter("image_base64", "string", "Ảnh đính kèm mã hoá Base64 (tuỳ chọn)", false, "string") // ✅ MỚI
                )
            )
        )
    )

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("FacebookSkill", "execute: action=$action")
        return when (action) {
            "post_status" -> {
                val content = params["content"] as? String ?: ""
                success("✅ Đã tiếp nhận và chuẩn bị đăng trạng thái lên Facebook: \"$content\"")
            }
            "send_messenger" -> {
                val recipientId = params["recipient_id"] as? String ?: ""
                val message = params["message"] as? String ?: ""
                val pageId = params["page_id"] as? String ?: "" // ✅ ĐÃ THÊM: Lấy pageId truyền sang
                // ✅ MỚI: Ảnh đính kèm (đã được đọc bytes cục bộ + mã hoá base64 từ nơi gọi,
                // vd. WebhookGatewayService/ChatSkill khi trả lời khách bằng ảnh chụp camera).
                val imageBase64 = params["image_base64"] as? String

                if (recipientId.isBlank() || (message.isBlank() && imageBase64.isNullOrEmpty())) {
                    return failure("Thiếu recipient_id hoặc nội dung tin nhắn/ảnh cần gửi.")
                }

                // ✅ SỬA: thiếu default -> ngầm định "" khi đọc DB thất bại, trong khi seed thật
                // có URL/token cụ thể (cùng lỗi đã sửa ở ChatSkill.kt). Dùng defaultOf() để khớp seed.
                val gatewayUrl = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_URL, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_GATEWAY_URL)).trim()
                val gatewayToken = configProvider.getString(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN, AppConfigDefaults.defaultOf(AppConfigDefaults.GLOBAL_GATEWAY_TOKEN)).trim()

                // 🔍 TRUY VẤN: Lấy thực thể trang đã lưu trong cơ sở dữ liệu SQLite theo Page ID động
                val pageEntity = withContext(Dispatchers.IO) {
                    database.facebookPageDao().getPageById(pageId)
                }

                // Nếu tìm thấy cấu hình trang trong DB thì lấy Page Token riêng biệt, ngược lại tự động fallback về Page Token mặc định cũ
                val pageAccessToken = pageEntity?.accessToken ?: configProvider.getString(AppConfigDefaults.FACEBOOK_PAGE_ACCESS_TOKEN).trim()

                if (gatewayUrl.isBlank() || gatewayToken.isBlank() || pageAccessToken.isBlank()) {
                    return failure("Thiếu thông tin cấu hình cổng đám mây hoặc Access Token cho Page ID: $pageId")
                }

                withContext(Dispatchers.IO) {
                    var connection: HttpURLConnection? = null
                    try {
                        val url = URL("$gatewayUrl/send/$gatewayToken")
                        connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000

                        val payload = JSONObject().apply {
                            put("platform", "facebook")
                            put("recipientId", recipientId)
                            put("message", message)
                            put("pageAccessToken", pageAccessToken)
                            // ✅ MỚI: chỉ đính kèm field ảnh khi thực sự có, tránh phá JSON payload
                            // của các lời gọi cũ (text-only) vốn không mong đợi field này.
                            if (!imageBase64.isNullOrEmpty()) {
                                put("imageBase64", imageBase64)
                            }
                        }.toString()

                        connection.outputStream.use { os ->
                            os.write(payload.toByteArray(Charsets.UTF_8))
                        }

                        val responseCode = connection.responseCode
                        if (responseCode == 200) {
                            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                            val jsonResp = JSONObject(responseBody)
                            
                            if (jsonResp.optString("status") == "success") {
                                val sentDesc = if (!imageBase64.isNullOrEmpty()) "tin nhắn kèm ảnh" else "tin nhắn"
                                success("✅ Gửi $sentDesc qua Messenger thành công!")
                            } else {
                                failure("❌ Lỗi từ máy chủ: ${jsonResp.optString("message", "Unknown error")}")
                            }
                        } else {
                            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            failure("❌ Lỗi HTTP $responseCode: $errorBody")
                        }
                    } catch (e: Exception) {
                        logger.e("FacebookSkill", "Gửi phản hồi qua Messenger thất bại: ${e.message}", e)
                        failure("❌ Lỗi kết nối gửi tin: ${e.message}")
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
            else -> failure("Không tìm thấy hành động: $action")
        }
    }
}