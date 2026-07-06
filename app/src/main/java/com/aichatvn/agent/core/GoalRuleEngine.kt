package com.aichatvn.agent.core

import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.GoalRuleEntity
import com.aichatvn.agent.data.model.GoalRunLogEntity
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun fireEvent(eventName: String): GoalEventOutcome = withContext(Dispatchers.IO) {
        val rules = try {
            database.goalRuleDao().getEnabledEventRules(eventName)
        } catch (e: Exception) {
            logger.e("GoalRuleEngine", "Không đọc được event rules cho \"$eventName\"", e)
            return@withContext GoalEventOutcome(handled = false)
        }
        val rule = rules.firstOrNull() ?: return@withContext GoalEventOutcome(handled = false)

        val conditionAndThenOk = runRule(rule)
        database.goalRuleDao().updateLastRun(rule.id, System.currentTimeMillis())

        if (rule.thenPluginId == "__system__" && rule.thenAction == "reply_fixed") {
            val replyText = safeJson(rule.thenParams).optString("replyText").ifBlank { null }
            GoalEventOutcome(handled = true, replyText = replyText)
        } else {
            GoalEventOutcome(handled = conditionAndThenOk)
        }
    }

    /** Chạy 1 rule: check (nếu có) -> đánh giá điều kiện -> then (nếu đúng) -> ghi log.
     *  Trả về true nếu điều kiện đúng VÀ (không có thenAction thật hoặc thenAction thành công). */
    private suspend fun runRule(rule: GoalRuleEntity): Boolean {
        var checkData: Map<*, *> = emptyMap<String, Any>()

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
                is AgentKernel.PluginResult.Success -> checkData = (result.data as? Map<*, *>) ?: emptyMap<String, Any>()
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

        // __system__ không đi qua Plugin.execute thật — caller (fireEvent) tự lo phần phản hồi
        if (rule.thenPluginId == "__system__") {
            insertLog(rule.id, conditionMet = true, success = true, summary = "Đã áp dụng: \"${rule.rawGoalText}\".")
            return true
        }

        val thenPlugin = plugins.find { it.manifest.id == rule.thenPluginId }
        if (thenPlugin == null) {
            logAndSkip(rule, "Không tìm thấy plugin hành động \"${rule.thenPluginId}\" (có thể đã gỡ cài đặt).")
            return false
        }
        val thenResult = try {
            thenPlugin.execute(rule.thenAction, safeJson(rule.thenParams).toParamMap())
        } catch (e: Exception) {
            logAndSkip(rule, "Lỗi khi thực hiện hành động: ${e.message}")
            return false
        }

        val success = thenResult is AgentKernel.PluginResult.Success
        val summary = when (thenResult) {
            is AgentKernel.PluginResult.Success -> "Đã thực hiện: \"${rule.rawGoalText}\"."
            is AgentKernel.PluginResult.Failure -> "Thực hiện thất bại: \"${rule.rawGoalText}\" — ${thenResult.error}"
            is AgentKernel.PluginResult.NeedMoreInfo -> "Thiếu thông tin để thực hiện: \"${rule.rawGoalText}\" — ${thenResult.question}"
        }
        insertLog(rule.id, conditionMet = true, success = success, summary = summary)
        return success
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

    private fun JSONObject.toParamMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        keys().forEach { key ->
            val value = get(key)
            if (value != JSONObject.NULL) map[key] = value
        }
        return map
    }
}
