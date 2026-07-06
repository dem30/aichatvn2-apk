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

data class GoalEventOutcome(val handled: Boolean, val replyText: String? = null)

@Singleton
class GoalRuleEngine @Inject constructor(
    private val database: AppDatabase,
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
    private val logger: Logger
) {
    companion object {
        // Tránh chạy trùng lặp nhiều lần nếu vòng lặp tick bị lệch trong cùng một phút
        private const val MIN_GAP_BETWEEN_CRON_RUNS_MS = 55_000L
        private const val CONFIG_AUTO_MODE_KEY = "housekeeper.auto_mode"
    }

    /**
     * Polling định kỳ cho các quy tắc thời gian SCHEDULE
     */
    suspend fun tickScheduleRules() = withContext(Dispatchers.IO) {
        // ✅ BỔ SUNG MASTER SWITCH: Ngắt quét lịch trình ngầm ngay lập tức nếu Quản gia đang bị tắt
        val autoMode = database.appConfigDao().getConfig(CONFIG_AUTO_MODE_KEY)?.value?.toBoolean() ?: false
        if (!autoMode) {
            return@withContext
        }

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
     * Kích hoạt xử lý tức thời cho các sự kiện EVENT (Ví dụ: incoming_message)
     */
    suspend fun fireEvent(eventName: String, eventPayload: Map<String, Any> = emptyMap()): GoalEventOutcome = withContext(Dispatchers.IO) {
        // ✅ BỔ SUNG MASTER SWITCH: Nếu Quản gia bị tắt, im lặng trả về handled = false để nhường luồng xử lý cho AgentKernel
        val autoMode = database.appConfigDao().getConfig(CONFIG_AUTO_MODE_KEY)?.value?.toBoolean() ?: false
        if (!autoMode) {
            return@withContext GoalEventOutcome(handled = false)
        }

        val rules = try {
            database.goalRuleDao().getEnabledEventRules(eventName)
        } catch (e: Exception) {
            logger.e("GoalRuleEngine", "Không đọc được event rules cho \"$eventName\"", e)
            return@withContext GoalEventOutcome(handled = false)
        }
        val rule = rules.firstOrNull() ?: return@withContext GoalEventOutcome(handled = false)

        val conditionAndThenOk = runRule(rule, eventPayload)
        database.goalRuleDao().updateLastRun(rule.id, System.currentTimeMillis())

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

    /**
     * Lõi thực thi quy tắc: check -> evaluate condition -> thenActions tuần tự
     */
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
                summary = "Đã kiểm tra \"${rule.rawGoalText}\" — không thỏa mãn điều kiện."
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

        // Chạy TUẦN TỰ từng hành động; dừng ngay khi gặp bước lỗi đầu tiên
        for (i in 0 until actionsJson.length()) {
            val actObj = actionsJson.optJSONObject(i) ?: continue
            val pluginId = actObj.optString("pluginId")
            val action = actObj.optString("action")
            val actionParams = actObj.optJSONObject("params")?.toParamMap() ?: emptyMap()

            if (pluginId == "__system__") {
                completedSteps.add("Trả lời tự động")
                continue
            }

            val thenPlugin = plugins.find { it.manifest.id == pluginId }
            if (thenPlugin == null) {
                completedSteps.add("Lỗi: không tìm thấy plugin \"$pluginId\"")
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
            if (!allSuccess) break
        }

        val summary = if (allSuccess) {
            "Đã tự động hoàn thành: \"${rule.rawGoalText}\"."
        } else {
            "Tiến trình bị gián đoạn: " + completedSteps.joinToString(" -> ")
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

    /**
     * ✅ ĐỒNG BỘ CHUẨN XÁC VỚI DATETIMEPARSER:
     * Hỗ trợ đầy đủ giờ, phút và thứ trong tuần (Monday = 1, Sunday = 0) để quét lịch trình [1].
     */
    private fun matchesCronMinute(cron: String, nowMillis: Long): Boolean {
        val parts = cron.trim().split(Regex("\\s+"))
        if (parts.size < 5) return false 
        
        val minute = parts[0].toIntOrNull() ?: return false
        val hour = parts[1].toIntOrNull() ?: return false
        val dayOfWeekPart = parts[4]

        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
        }

        if (cal.get(Calendar.MINUTE) != minute) return false
        if (cal.get(Calendar.HOUR_OF_DAY) != hour) return false

        if (dayOfWeekPart != "*") {
            val currentCalendarDay = cal.get(Calendar.DAY_OF_WEEK)
            
            // Khớp lịch thứ trong tuần của Java Calendar sang định dạng DateTimeParser
            val currentCronDay = when (currentCalendarDay) {
                Calendar.SUNDAY -> 0
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                else -> -1
            }

            val allowedDays = dayOfWeekPart.split(",").mapNotNull { it.toIntOrNull() }
            if (currentCronDay !in allowedDays) return false
        }

        return true
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