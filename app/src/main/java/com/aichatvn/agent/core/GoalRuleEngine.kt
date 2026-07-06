package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.GoalRuleEntity
import com.aichatvn.agent.data.model.GoalRunLogEntity
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

/** Kết quả xử lý 1 sự kiện (vd "incoming_message"). handled=true nghĩa là caller nên BỎ QUA
 *  luồng xử lý mặc định vì GoalRuleEngine đã tự lo (vd đã trả lời "đang bận" thay). */
data class GoalEventOutcome(val handled: Boolean, val replyText: String? = null)

/**
 * GoalRuleEngine — bộ thực thi cho GoalRuleEntity (tính năng "quản gia GOD mode").
 *
 * - tickScheduleRules(): gọi định kỳ (polling, xem WebhookGatewayService) cho rule
 *   triggerType=SCHEDULE — tự kiểm tra hạn theo cron/intervalMinutes.
 * - fireEvent(eventName): gọi TRỰC TIẾP tại nơi phát sinh sự kiện thật (vd nhận tin nhắn
 *   khách trong WebhookGatewayService) cho rule triggerType=EVENT.
 *
 * Cả 2 đường đều chạy chung logic runRule(): check (nếu có) -> RuleConditionEvaluator ->
 * then -> ghi GoalRunLogEntity để Housekeeper.check_status/goal_report show lại cho người
 * dùng kiểm tra.
 */
@Singleton
class GoalRuleEngine @Inject constructor(
    private val database: AppDatabase,
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val logger: Logger
) {
    companion object {
        // Rule cron chỉ được coi là "chưa chạy lại trong phút này" nếu lần chạy trước cách
        // đây > 55s — tránh chạy trùng nhiều lần nếu vòng lặp tick có tick lệch nhẹ trong cùng 1 phút.
        private const val MIN_GAP_BETWEEN_CRON_RUNS_MS = 55_000L
    }

    suspend fun tickScheduleRules() = withContext(Dispatchers.IO) {
        val rules = try {
            database.goalRuleDao().getEnabledScheduleRules()
        } catch (e: Exception) {
            logger.e("GoalRuleEngine", "Không đọc được danh sách goal_rules", e)
            return@withContext
        }

        val now = System.currentTimeMillis()
        for (rule in rules) {
            val isDue = when {
                rule.intervalMinutes > 0 -> (now - rule.lastRunAt) >= rule.intervalMinutes * 60_000L
                rule.cron.isNotBlank() ->
                    matchesCronMinute(rule.cron, now) && (now - rule.lastRunAt) > MIN_GAP_BETWEEN_CRON_RUNS_MS
                else -> false
            }
            if (isDue) {
                runRule(rule)
                database.goalRuleDao().updateLastRun(rule.id, now)
            }
        }
    }

    /**
     * @param eventPayload ngữ cảnh sự kiện (vd text/senderId/platform của tin nhắn khách) —
     * được đưa thẳng vào checkData để conditionExpr có thể tham chiếu (vd "text contains 'abc'")
     * ngay cả khi rule không có bước checkAction riêng.
     */
    suspend fun fireEvent(eventName: String, eventPayload: Map<String, Any> = emptyMap()): GoalEventOutcome = withContext(Dispatchers.IO) {
        val rules = try {
            database.goalRuleDao().getEnabledEventRules(eventName)
        } catch (e: Exception) {
            logger.e("GoalRuleEngine", "Không đọc được event rules cho \"$eventName\"", e)
            return@withContext GoalEventOutcome(handled = false)
        }
        val rule = rules.firstOrNull() ?: return@withContext GoalEventOutcome(handled = false)

        val conditionAndThenOk = runRule(rule, eventPayload)
        database.goalRuleDao().updateLastRun(rule.id, System.currentTimeMillis())

        // Quy ước: rule EVENT dùng cho trả lời cố định chỉ nên có 1 phần tử duy nhất trong
        // thenActions (xem ràng buộc ở prompt của GoalPlanner) -> chỉ cần đọc phần tử đầu.
        val actionsArray = safeJsonArray(rule.thenActions)
        val firstAction = if (actionsArray.length() > 0) actionsArray.optJSONObject(0) else null

        return@withContext if (firstAction != null &&
            firstAction.optString("pluginId") == "__system__" &&
            firstAction.optString("action") == "reply_fixed"
        ) {
            val replyText = firstAction.optJSONObject("params")?.optString("replyText")?.ifBlank { null }
            GoalEventOutcome(handled = true, replyText = replyText)
        } else {
            GoalEventOutcome(handled = conditionAndThenOk)
        }
    }

    /** Chạy 1 rule: check (nếu có) -> đánh giá điều kiện -> chạy TUẦN TỰ từng phần tử trong
     *  thenActions (dừng ở bước lỗi đầu tiên) -> ghi log.
     *  Trả về true nếu điều kiện đúng VÀ toàn bộ chuỗi thenActions chạy thành công. */
    private suspend fun runRule(rule: GoalRuleEntity, eventPayload: Map<String, Any> = emptyMap()): Boolean {
        var checkData: Map<*, *> = eventPayload

        if (rule.checkPluginId.isNotBlank()) {
            val checkPlugin = plugins.find { it.manifest.id == rule.checkPluginId }
            if (checkPlugin == null) {
                logAndSkip(rule, "Không tìm thấy plugin kiểm tra \"${rule.checkPluginId}\" (có thể đã gỡ cài đặt).")
                return false
            }
            val result = try {
                checkPlugin.execute(rule.checkAction, safeJson(rule.checkParams).toParamMap())
            } catch (e: Exception) {
                logAndSkip(rule, "Lỗi khi kiểm tra: ${e.message}")
                return false
            }
            when (result) {
                // Ghép kết quả check ĐÈ LÊN eventPayload (ưu tiên dữ liệu check mới hơn nếu trùng field)
                is AgentKernel.PluginResult.Success -> checkData = checkData + ((result.data as? Map<*, *>) ?: emptyMap<String, Any>())
                is AgentKernel.PluginResult.Failure -> {
                    logAndSkip(rule, "Bước kiểm tra thất bại: ${result.error}")
                    return false
                }
                is AgentKernel.PluginResult.NeedMoreInfo -> {
                    logAndSkip(rule, "Bước kiểm tra thiếu thông tin: ${result.question}")
                    return false
                }
            }
        }

        val conditionMet = RuleConditionEvaluator.evaluate(rule.conditionExpr, checkData)
        if (!conditionMet) {
            insertLog(
                rule.id, conditionMet = false, success = true,
                summary = "Đã kiểm tra \"${rule.rawGoalText}\" — không có gì bất thường."
            )
            return false
        }

        val actionsJson = try {
            safeJsonArray(rule.thenActions)
        } catch (e: Exception) {
            logAndSkip(rule, "Lỗi cú pháp chuỗi hành động thenActions: ${e.message}")
            return false
        }

        if (actionsJson.length() == 0) {
            insertLog(rule.id, conditionMet = true, success = true, summary = "Không có hành động nào để thực hiện.")
            return true
        }

        var allSuccess = true
        val completedSteps = mutableListOf<String>()

        // Chạy TUẦN TỰ từng hành động; dừng ngay khi gặp bước lỗi (không chạy tiếp các bước sau).
        for (i in 0 until actionsJson.length()) {
            val actObj = actionsJson.optJSONObject(i) ?: continue
            val pluginId = actObj.optString("pluginId")
            val action = actObj.optString("action")
            val actionParams = actObj.optJSONObject("params")?.toParamMap() ?: emptyMap()

            // __system__ không đi qua Plugin.execute thật — caller (fireEvent) tự lo phần phản hồi
            if (pluginId == "__system__") {
                completedSteps.add("Trả lời tự động")
                continue
            }

            val thenPlugin = plugins.find { it.manifest.id == pluginId }
            if (thenPlugin == null) {
                completedSteps.add("Lỗi: không tìm thấy plugin \"$pluginId\" (có thể đã gỡ cài đặt)")
                allSuccess = false
                break
            }

            val thenResult = try {
                thenPlugin.execute(action, actionParams)
            } catch (e: Exception) {
                AgentKernel.PluginResult.Failure("Ngoại lệ: ${e.message}")
            }

            when (thenResult) {
                is AgentKernel.PluginResult.Success -> completedSteps.add("$pluginId.$action thành công")
                is AgentKernel.PluginResult.Failure -> {
                    completedSteps.add("$pluginId.$action thất bại: ${thenResult.error}")
                    allSuccess = false
                }
                is AgentKernel.PluginResult.NeedMoreInfo -> {
                    completedSteps.add("$pluginId.$action thiếu thông tin: ${thenResult.question}")
                    allSuccess = false
                }
            }
            if (!allSuccess) break // gặp lỗi giữa chuỗi -> dừng ngay, không chạy các bước còn lại
        }

        val summary = if (allSuccess) {
            "Đã thực hiện: \"${rule.rawGoalText}\"."
        } else {
            "Tiến trình bị gián đoạn khi thực hiện \"${rule.rawGoalText}\": " + completedSteps.joinToString(" -> ")
        }
        insertLog(rule.id, conditionMet = true, success = allSuccess, summary = summary)
        return allSuccess
    }

    private suspend fun logAndSkip(rule: GoalRuleEntity, reason: String) {
        logger.w("GoalRuleEngine", "Rule ${rule.id} (\"${rule.rawGoalText}\"): $reason")
        insertLog(rule.id, conditionMet = false, success = false, summary = reason)
    }

    private suspend fun insertLog(goalId: String, conditionMet: Boolean, success: Boolean, summary: String) {
        database.goalRunLogDao().insertLog(
            GoalRunLogEntity(
                id = "log_${UUID.randomUUID()}",
                goalId = goalId,
                timestamp = System.currentTimeMillis(),
                conditionMet = if (conditionMet) 1 else 0,
                success = if (success) 1 else 0,
                summary = summary
            )
        )
    }

    /** Chỉ hỗ trợ cron tối giản "M H * * *" (phút giờ, mỗi ngày) — đúng định dạng
     *  DateTimeParser.parseVietnameseTime() sinh ra cho các câu như "mỗi ngày lúc 7 giờ". */
    private fun matchesCronMinute(cron: String, nowMillis: Long): Boolean {
        val parts = cron.trim().split(Regex("\\s+"))
        if (parts.size < 2) return false
        val minute = parts[0].toIntOrNull() ?: return false
        val hour = parts[1].toIntOrNull() ?: return false

        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        return cal.get(Calendar.HOUR_OF_DAY) == hour && cal.get(Calendar.MINUTE) == minute
    }

    private fun safeJson(raw: String): JSONObject = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }

    private fun safeJsonArray(raw: String): JSONArray = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }

    private fun JSONObject.toParamMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            if (value != JSONObject.NULL) map[key] = value
        }
        return map
    }
}