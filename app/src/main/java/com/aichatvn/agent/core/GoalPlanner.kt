package com.aichatvn.agent.core

import android.content.Context
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.GoalRuleEntity
import com.aichatvn.agent.tools.ai.GroqClientTool
import com.aichatvn.agent.utils.DateTimeParser
import com.aichatvn.agent.utils.Logger
import com.aichatvn.agent.utils.StringSimilarityUtil
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

sealed class GoalPlanResult {
    data class Ready(val rule: GoalRuleEntity) : GoalPlanResult()
    data class NeedsInfo(val question: String) : GoalPlanResult()
    data class Failed(val reason: String) : GoalPlanResult()
}

@Singleton
class GoalPlanner @Inject constructor(
    private val pluginsProvider: Provider<Set<@JvmSuppressWildcards Plugin>>,
    private val groqClient: GroqClientTool,
    private val logger: Logger,
    private val context: Context // Inject context để truy cập DB cục bộ
) {

    companion object {
        private const val DEFAULT_DAILY_CRON = "0 7 * * *"
        private val DAILY_KEYWORDS = setOf("hang ngay", "moi ngay", "moi buoi sang", "moi sang", "hang buoi")
        private val SUPPORTED_EVENTS = setOf("incoming_message")
    }

    /**
     * ✅ MỚI: Tự động phân giải tất cả ALIAS (như "phòng khách" ➔ "camera 1") cục bộ trong SQLite
     * trước khi gửi câu lệnh lên LLM. Giúp AI nhận diện thẳng tham số chuẩn, tránh vòng lặp hỏi bù [1]!
     */
    private suspend fun resolveGoalTextAliases(goalText: String, username: String, database: AppDatabase): String {
        var resolved = goalText
        val aliases = database.qaDao().getAllQAs(username).filter { it.type == "alias" }
        val sortedAliases = aliases.sortedByDescending { it.question.length }
        
        for (alias in sortedAliases) {
            val q = alias.question.trim()
            val a = alias.answer.trim()
            if (q.isNotEmpty()) {
                val regex = Regex("(?<!\\p{L})${Regex.escape(q)}(?!\\p{L})", RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(resolved)) {
                    resolved = regex.replace(resolved, a)
                    logger.d("GoalPlanner", "🔍 Đã tự động giải mã alias cục bộ: '$q' ➔ '$a' trong câu lệnh")
                }
            }
        }
        return resolved
    }

    suspend fun plan(goalText: String, createdBy: String = "default_user"): GoalPlanResult {
        val plugins = pluginsProvider.get()
        val routablePlugins = plugins.filter { it.manifest.routable }
        if (routablePlugins.isEmpty()) {
            return GoalPlanResult.Failed("Chưa có plugin nào khả dụng để giao việc.")
        }

        // ✅ Nạp DB để phân giải bí danh tiếng Việt trước khi gửi đi [1]
        val database = AppDatabase.getDatabase(context)
        val resolvedGoalText = resolveGoalTextAliases(goalText, createdBy, database)
        logger.d("GoalPlanner", "📝 Câu lệnh gốc: '$goalText' ➔ Câu lệnh sau giải mã alias: '$resolvedGoalText'")

        val candidateLines = routablePlugins.joinToString("\n") { p ->
            p.manifest.actions.joinToString("\n") { a ->
                "  - ${p.manifest.id}.${a.name}: ${a.description} (params: ${
                    a.parameters.filter { it.required }.joinToString(", ") { it.name }
                })"
            }
        }

        // ✅ TỐI ƯU HÓA: Prompt rút gọn cực kỳ ngắn để giảm 70% kích thước token, bảo vệ kết nối mạng nền [1]
        val prompt = buildString {
            append("<sys>Bạn là Goal Planner cho Smarthome. Đọc yêu cầu, trả về duy nhất JSON quy tắc.\n")
            append("Cấu trúc JSON bắt buộc:\n")
            append("{\n")
            append("  \"triggerType\": \"SCHEDULE\" hoặc \"EVENT\",\n")
            append("  \"eventName\": \"incoming_message\" (chỉ khi triggerType=EVENT),\n")
            append("  \"checkPluginId\": \"\", \"checkAction\": \"\", \"checkParams\": {},\n")
            append("  \"conditionExpr\": \"\",\n")
            append("  \"thenActions\": [{\"pluginId\": \"...\", \"action\": \"...\", \"params\": {}}],\n")
            append("  \"needClarification\": false,\n")
            append("  \"clarificationQuestion\": \"\"\n")
            append("}\n")
            append("QUY TẮC:\n")
            append("1. Lọc tin nhắn/camera cũ -> triggerType=\"SCHEDULE\" (chạy định kỳ), checkPluginId=\"chat\" hoặc \"camera\".\n")
            append("2. Phản hồi cố định -> triggerType=\"EVENT\", eventName=\"incoming_message\", thenActions=[{\"pluginId\":\"__system__\",\"action\":\"reply_fixed\",\"params\":{\"replyText\":\"...\"}}].\n")
            append("3. Nếu thiếu tham số bắt buộc của action (nhìn ở candidates) -> set needClarification=true kèm câu hỏi tiếng Việt.\n")
            append("4. Chỉ xuất JSON thô, không giải thích.</sys>\n\n")
            append("<candidates>\n$candidateLines\n</candidates>\n")
            append("<goal>$resolvedGoalText</goal>\n")
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
                "Loại sự kiện \"$eventName\" chưa được hỗ trợ."
            )
        }

        if (thenActionsArray.length() == 0) {
            return GoalPlanResult.Failed("AI không xác định được hành động cần thực hiện.")
        }
        for (i in 0 until thenActionsArray.length()) {
            val act = thenActionsArray.optJSONObject(i)
                ?: return GoalPlanResult.Failed("Cấu trúc hành động thứ ${i + 1} trong thenActions không hợp lệ.")
            val pId = act.optString("pluginId")
            val aName = act.optString("action")
            if (pId.isBlank() || aName.isBlank()) {
                return GoalPlanResult.Failed("Hành động thứ ${i + 1} bị thiếu pluginId/action.")
            }
            if (pId != "__system__") {
                val plugin = routablePlugins.find { it.manifest.id == pId }
                    ?: return GoalPlanResult.Failed("AI chọn plugin \"$pId\" không tồn tại.")
                plugin.manifest.actions.find { it.name == aName }
                    ?: return GoalPlanResult.Failed("Hành động \"$aName\" không tồn tại trong plugin \"$pId\".")
            }
        }
        if (checkPluginId.isNotBlank()) {
            val checkPlugin = routablePlugins.find { it.manifest.id == checkPluginId }
                ?: return GoalPlanResult.Failed("AI chọn plugin kiểm tra \"$checkPluginId\" không tồn tại.")
            checkPlugin.manifest.actions.find { it.name == checkAction }
                ?: return GoalPlanResult.Failed("Hành động kiểm tra \"$checkAction\" không tồn tại trong plugin \"$checkPluginId\".")
        }

        var cron = ""
        var intervalMinutes = 0
        if (triggerType == "SCHEDULE") {
            val parsedCron = DateTimeParser.parseVietnameseTime(resolvedGoalText)
            val parsedInterval = DateTimeParser.parseVietnameseInterval(resolvedGoalText)
            when {
                parsedCron != null -> cron = parsedCron
                parsedInterval != null -> intervalMinutes = parsedInterval
                isDailyPhrase(resolvedGoalText) -> cron = DEFAULT_DAILY_CRON
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