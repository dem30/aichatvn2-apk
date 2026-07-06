package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.model.GoalRuleEntity
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.DateTimeParser
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider // ✅ THÊM IMPORT: Provider để trì hoãn việc nạp Set<Plugin>
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

/**
 * Kết quả phân rã 1 câu lệnh tự nhiên (goal) thành GoalRuleEntity.
 */
sealed class GoalPlanResult {
    data class Ready(val rule: GoalRuleEntity) : GoalPlanResult()
    data class NeedsInfo(val question: String) : GoalPlanResult()
    data class Failed(val reason: String) : GoalPlanResult()
}

/**
 * GoalPlanner ("Bộ lập kế hoạch quản gia")
 *
 * Nhiệm vụ: nhận 1 câu lệnh tự nhiên phức tạp (vd "kiểm tra điện, có gì thì email cho tôi",
 * "nhớ tưới cây hàng ngày", "khách nhắn thì bảo tôi đang bận") và chuyển thành 1 GoalRuleEntity
 * có thể lưu vào DB rồi để GoalRuleEngine thực thi lặp lại.
 */
@Singleton
class GoalPlanner @Inject constructor(
    // ✅ ĐÃ SỬA: Bọc Set<Plugin> trong Provider để phá vỡ vòng lặp phụ thuộc của Hilt
    private val pluginsProvider: Provider<Set<@JvmSuppressWildcards Plugin>>,
    private val groqClient: GroqClientTool,
    private val logger: Logger
) {

    companion object {
        // Giờ mặc định khi người dùng nói "hàng ngày" nhưng không nêu giờ cụ thể.
        private const val DEFAULT_DAILY_CRON = "0 7 * * *"
        private val DAILY_KEYWORDS = setOf("hang ngay", "moi ngay", "moi buoi sang", "moi sang", "hang buoi")

        // Hiện chỉ hỗ trợ 1 loại sự kiện — mở rộng thêm khi có nhu cầu thực tế
        private val SUPPORTED_EVENTS = setOf("incoming_message")
    }

    suspend fun plan(goalText: String, createdBy: String = "default_user"): GoalPlanResult {
        // ✅ ĐÃ SỬA: Lấy tập hợp plugin thực tế tại thời điểm thực thi
        val plugins = pluginsProvider.get()
        val routablePlugins = plugins.filter { it.manifest.routable }
        
        if (routablePlugins.isEmpty()) {
            return GoalPlanResult.Failed("Chưa có plugin nào khả dụng để giao việc.")
        }

        val candidateLines = routablePlugins.joinToString("\n") { p ->
            p.manifest.actions.joinToString("\n") { a ->
                "  - ${p.manifest.id}.${a.name}: ${a.description} (tham số: ${
                    a.parameters.joinToString(", ") { it.name + if (it.required) "*" else "" }
                })"
            }
        }

        val prompt = buildString {
            append("<sys>Bạn là bộ lập kế hoạch (Goal Planner) cho quản gia tự động điều khiển nhà thông minh.\n")
            append("Nhiệm vụ: đọc YÊU CẦU của người dùng, chuyển thành ĐÚNG 1 JSON quy tắc. ")
            append("Chỉ được dùng pluginId/action CÓ THẬT trong <candidates>, TUYỆT ĐỐI không bịa ra plugin/action không tồn tại.\n\n")
            append("Cấu trúc JSON output bắt buộc:\n")
            append("{\n")
            append("  \"triggerType\": \"SCHEDULE\" hoặc \"EVENT\",\n")
            append("  \"eventName\": \"incoming_message\" (chỉ điền khi triggerType=EVENT; hiện chỉ hỗ trợ giá trị này),\n")
            append("  \"checkPluginId\": \"\" (để trống nếu không cần bước kiểm tra riêng trước khi hành động),\n")
            append("  \"checkAction\": \"\",\n")
            append("  \"checkParams\": {},\n")
            append("  \"conditionExpr\": \"\" (để trống nếu luôn hành động mỗi lần trigger; hỗ trợ so sánh số \\\"truong > gia_tri\\\" như \\\"onlineDevices < totalDevices\\\", hoặc so khớp chuỗi \\\"truong contains 'abc'\\\" / \\\"truong matches_regex '\\\\\\\\d{9,11}'\\\"),\n")
            append("  \"thenActions\": [\n")
            append("     {\"pluginId\": \"...\", \"action\": \"...\", \"params\": {}}\n")
            append("  ] (mảng các hành động chạy TUẦN TỰ, theo đúng thứ tự người dùng muốn; luôn có ÍT NHẤT 1 phần tử),\n")
            append("  \"needClarification\": false,\n")
            append("  \"clarificationQuestion\": \"\"\n")
            append("}\n\n")
            append("QUY TẮC:\n")
            append("🚨 QUY TẮC PHÂN TÁCH QUAN TRỌNG (ANTI-TIMEOUT BIAS):\n")
            append("0. Với câu dạng lọc/tìm kiếm trong TIN NHẮN LỊCH SỬ (vd: \"nếu tin nhắn có số điện thoại/chốt đơn thì báo cáo\"):\n")
            append("   - Bạn PHẢI dùng triggerType=\"SCHEDULE\" (vd: chạy hàng giờ hoặc cuối ngày), KHÔNG dùng triggerType=\"EVENT\".\n")
            append("   - Đặt checkPluginId=\"chat\", checkAction=\"list\" hoặc checkAction=\"search\" để quét lịch sử tin nhắn cục bộ từ SQLite.\n")
            append("   - Lý do: EVENT chạy đồng bộ ngay khi khách nhắn tin tới, dùng EVENT để quét/lọc nội dung sẽ chặn luồng nhận tin nhắn thời gian thực và gây nghẽn mạng.\n")
            append("1. Yêu cầu LẶP LẠI THEO THỜI GIAN (hàng ngày, mỗi giờ, mỗi X phút...) -> triggerType=\"SCHEDULE\".\n")
            append("2. Yêu cầu liên quan TIN NHẮN KHÁCH GỬI TỚI dạng phản hồi cố định tức thời (vd \"khách nhắn thì bảo đang bận\") -> triggerType=\"EVENT\", eventName=\"incoming_message\", thenActions=[{\"pluginId\":\"__system__\",\"action\":\"reply_fixed\",\"params\":{\"replyText\":\"nội dung trả lời cố định\"}}] (CHỈ 1 phần tử, không kèm hành động khác).\n")
            append("3. Câu dạng 'kiểm tra X, nếu có vấn đề thì Y' -> checkPluginId/checkAction là bước X, thenActions là (các) hành động Y (thường là gửi email/thông báo).\n")
            append("4. Câu dạng 'làm Z hàng ngày/định kỳ' KHÔNG có điều kiện kiểm tra -> để checkPluginId/checkAction/conditionExpr rỗng, thenActions chính là hành động Z.\n")
            append("5. Nếu người dùng yêu cầu NHIỀU việc nối tiếp trong 1 câu (vd \"bật đèn rồi gửi email báo tôi\") -> phân rã thành nhiều phần tử trong thenActions theo đúng thứ tự.\n")
            append("6. Nếu KHÔNG chắc chọn đúng plugin/action nào, hoặc thiếu thông tin bắt buộc (email người nhận, tên/khu vực thiết bị...) -> needClarification=true kèm câu hỏi lại bằng tiếng Việt, các trường còn lại để rỗng.\n")
            append("7. Không giải thích gì thêm, chỉ xuất JSON thô.</sys>\n\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            append("<goal>$goalText</goal>\n")
            append("<output>")
        }

        val rawJson = try {
            groqClient.routeIntent(prompt)
        } catch (e: Exception) {
            logger.e("GoalPlanner", "Lỗi gọi AI lập kế hoạch", e)
            return GoalPlanResult.Failed("Lỗi gọi AI lập kế hoạch: ${e.message}")
        }

        val json = parsePlanJson(rawJson)
            ?: return GoalPlanResult.Failed("Không đọc được kế hoạch do AI trả về sai định dạng.")

        if (json.optBoolean("needClarification", false)) {
            val question = json.optString("clarificationQuestion").ifBlank {
                "Bạn có thể mô tả rõ hơn yêu cầu này không?"
            }
            return GoalPlanResult.NeedsInfo(question)
        }

        val triggerType = json.optString("triggerType", "SCHEDULE").uppercase()
        val eventName = json.optString("eventName", "")
        val checkPluginId = json.optString("checkPluginId", "")
        val checkAction = json.optString("checkAction", "")
        val checkParams = json.optJSONObject("checkParams")?.toString() ?: "{}"
        val conditionExpr = json.optString("conditionExpr", "")
        val thenActionsArray = json.optJSONArray("thenActions") ?: JSONArray()

        if (triggerType != "SCHEDULE" && triggerType != "EVENT") {
            return GoalPlanResult.Failed("AI trả về triggerType không hợp lệ: \"$triggerType\".")
        }
        if (triggerType == "EVENT" && eventName !in SUPPORTED_EVENTS) {
            return GoalPlanResult.Failed(
                "Loại sự kiện \"$eventName\" chưa được hỗ trợ (hiện chỉ hỗ trợ: ${SUPPORTED_EVENTS.joinToString()})."
            )
        }

        // Chống hallucination: mảng thenActions phải có ít nhất 1 phần tử, và MỖI phần tử
        // phải trỏ tới pluginId/action CÓ THẬT trong manifest (trừ "__system__").
        if (thenActionsArray.length() == 0) {
            return GoalPlanResult.Failed("AI không xác định được hành động cần thực hiện. Vui lòng mô tả rõ hơn.")
        }
        for (i in 0 until thenActionsArray.length()) {
            val act = thenActionsArray.optJSONObject(i)
                ?: return GoalPlanResult.Failed("Cấu trúc hành động thứ ${i + 1} trong thenActions không hợp lệ.")
            val pId = act.optString("pluginId")
            val aName = act.optString("action")
            if (pId.isBlank() || aName.isBlank()) {
                return GoalPlanResult.Failed("Hành động thứ ${i + 1} trong chuỗi bị thiếu pluginId/action.")
            }
            if (pId != "__system__") {
                val plugin = routablePlugins.find { it.manifest.id == pId }
                    ?: return GoalPlanResult.Failed("AI chọn plugin \"$pId\" không tồn tại. Vui lòng mô tả yêu cầu rõ hơn.")
                plugin.manifest.actions.find { it.name == aName }
                    ?: return GoalPlanResult.Failed("AI chọn hành động \"$aName\" không tồn tại trong plugin \"$pId\".")
            }
        }
        if (checkPluginId.isNotBlank()) {
            val checkPlugin = routablePlugins.find { it.manifest.id == checkPluginId }
                ?: return GoalPlanResult.Failed("AI chọn plugin kiểm tra \"$checkPluginId\" không tồn tại.")
            checkPlugin.manifest.actions.find { it.name == checkAction }
                ?: return GoalPlanResult.Failed("AI chọn hành động kiểm tra \"$checkAction\" không tồn tại trong plugin \"$checkPluginId\".")
        }

        var cron = ""
        var intervalMinutes = 0
        if (triggerType == "SCHEDULE") {
            val parsedCron = DateTimeParser.parseVietnameseTime(goalText)
            val parsedInterval = DateTimeParser.parseVietnameseInterval(goalText)
            when {
                parsedCron != null -> cron = parsedCron
                parsedInterval != null -> intervalMinutes = parsedInterval
                isDailyPhrase(goalText) -> cron = DEFAULT_DAILY_CRON
                else -> return GoalPlanResult.NeedsInfo(
                    "Bạn muốn việc này lặp lại theo lịch nào? (vd: \"mỗi ngày lúc 7 giờ sáng\", \"mỗi 30 phút\")"
                )
            }
        }

        val rule = GoalRuleEntity(
            id = "goal_${UUID.randomUUID()}",
            rawGoalText = goalText,
            triggerType = triggerType,
            cron = cron,
            intervalMinutes = intervalMinutes,
            eventName = eventName,
            checkPluginId = checkPluginId,
            checkAction = checkAction,
            checkParams = checkParams,
            conditionExpr = conditionExpr,
            thenActions = thenActionsArray.toString(),
            enabled = 1,
            lastRunAt = 0L,
            createdAt = System.currentTimeMillis(),
            createdBy = createdBy
        )
        return GoalPlanResult.Ready(rule)
    }

    private fun isDailyPhrase(text: String): Boolean {
        val norm = StringSimilarityUtil.normalizeVietnamese(text.lowercase())
        return DAILY_KEYWORDS.any { norm.contains(it) }
    }

    private fun parsePlanJson(response: String): JSONObject? {
        return try {
            val cleaned = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            JSONObject(cleaned)
        } catch (e: Exception) {
            null
        }
    }
}