package com.aichatvn.agent.skills

import android.content.Context
import com.aichatvn.agent.core.AgentKernel
import com.aichatvn.agent.core.GoalPlanResult
import com.aichatvn.agent.core.GoalPlanner
import com.aichatvn.agent.core.plugin.Plugin
import com.aichatvn.agent.core.plugin.PluginAction
import com.aichatvn.agent.core.plugin.PluginCapabilities
import com.aichatvn.agent.core.plugin.PluginManifest
import com.aichatvn.agent.core.plugin.PluginParameter
import com.aichatvn.agent.data.AppDatabase
import com.aichatvn.agent.data.model.AppConfigEntity
import com.aichatvn.agent.data.model.GoalRuleEntity
import com.aichatvn.agent.skills.base.BaseSkill
import com.aichatvn.agent.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HousekeeperSkill ("Quản gia tự động")
 *
 * Vai trò: điều phối trung tâm, KHÔNG tự viết lại logic của các skill khác.
 * - check_status  : đọc trạng thái THẬT từ DB (camera, Tuya, cảnh báo, lịch trình).
 * - set_auto_mode : bật/tắt cờ tự động hóa, lưu bền vững qua AppConfigDao.
 * - manage_schedule / manage_config : ủy quyền (delegate) cho ScheduleSkill / AppConfigSkill.
 *
 * Nguyên tắc an toàn: mọi thao tác KHÔNG THỂ HOÀN TÁC DỄ DÀNG (xóa lịch, tắt lịch cảnh báo,
 * reset cấu hình) đều bắt buộc phải có confirm=true, nếu không sẽ trả về NeedMoreInfo để
 * AgentKernel tự đưa vào vòng lặp hỏi-xác nhận "có/không" đã có sẵn trong hệ thống.
 */
@Singleton
class HousekeeperSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleSkill: ScheduleSkill,
    private val appConfigSkill: AppConfigSkill,
    private val goalPlanner: GoalPlanner,
    logger: Logger
) : BaseSkill("housekeeper", "Quản gia tự động", logger), Plugin {
    // ✅ ĐÃ SỬA — ĐÂY LÀ BUG GỐC: Class này thiếu ", Plugin" trong khai báo kế thừa.
    // AgentKernel nhận danh sách plugin qua Hilt multibinding `Set<Plugin>` (xem
    // `private val plugins: Set<@JvmSuppressWildcards Plugin>` trong AgentKernel.kt).
    // Vì HousekeeperSkill trước đây KHÔNG implement interface Plugin, Hilt không bao giờ đưa
    // nó vào tập hợp đó — AgentKernel hoàn toàn không biết tới HousekeeperSkill, dù đã khai
    // báo đầy đủ PluginManifest với routable=true/autoGenerateQA=true. Hậu quả dây chuyền:
    //   1) Không lệnh nào (kể cả set_auto_mode) có thể route tới được HousekeeperSkill.
    //   2) set_auto_mode không bao giờ chạy -> không bao giờ upsert key "auto_mode" (pluginId=
    //      "housekeeper") vào bảng app_config.
    //   3) SettingsScreen nhóm hiển thị theo configs.groupBy { it.pluginId } lấy trực tiếp từ
    //      DB (xem PluginConfigSection trong SettingsScreen.kt) -> vì không có dòng nào với
    //      pluginId="housekeeper" trong DB nên KHÔNG BAO GIỜ có mục "Quản gia" hiện ra trong Cài đặt.
    // Thêm ", Plugin" là đã đủ sửa toàn bộ chuỗi lỗi trên.

    companion object {
        // ✅ ĐÃ SỬA: Dùng constant chung từ AppConfigDefaults (namespace "housekeeper.auto_mode")
        // thay vì hằng số cục bộ "auto_mode" không tiền tố — bảng app_config dùng "key" làm
        // PRIMARY KEY toàn cục (không phân biệt theo pluginId), nên key trần dễ đụng độ với
        // plugin khác trong tương lai. Đồng thời đây cũng là key đã được seed sẵn trong
        // AppConfigDefaults.all() để mục "Quản gia" hiện ngay trong Cài đặt kể cả khi chưa
        // ai từng bật/tắt qua lệnh.
        private val CONFIG_KEY = com.aichatvn.agent.config.AppConfigDefaults.HOUSEKEEPER_AUTO_MODE

        // Các "op" của manage_schedule bị coi là phá hủy/khó hoàn tác -> luôn cần confirm
        private val DESTRUCTIVE_SCHEDULE_OPS = setOf("delete")

        // Các "op" của manage_config bị coi là phá hủy/khó hoàn tác -> luôn cần confirm
        private val DESTRUCTIVE_CONFIG_OPS = setOf("reset")
    }

    override val manifest = PluginManifest(
        id = id,
        name = name,
        capabilities = PluginCapabilities(schedule = true, background = true),
        routable = true,
        visibleOnDashboard = true,
        autoGenerateQA = true,
        actions = listOf(
            PluginAction(
                name = "check_status",
                description = "Kiểm tra trạng thái thật của camera, thiết bị Tuya, cảnh báo chưa đọc và lịch trình",
                examples = listOf(
                    "kiểm tra toàn bộ nhà",
                    "báo cáo tổng quan an ninh",
                    "quản gia báo cáo tình hình"
                )
            ),
            PluginAction(
                name = "set_auto_mode",
                description = "Bật hoặc tắt chế độ tự động hóa quản gia (lưu bền vững qua AppConfig)",
                examples = listOf("bật quản gia tự động", "tắt chế độ tự động nhà cửa"),
                parameters = listOf(
                    PluginParameter("enabled", "boolean", "Trạng thái bật/tắt tự động", true, "boolean")
                )
            ),
            PluginAction(
                name = "manage_schedule",
                description = "Quản lý lịch trình thay người dùng: op=add|list|delete|toggle. " +
                    "Với op=delete, hoặc op=toggle kèm enabled=false (tắt lịch), BẮT BUỘC kèm confirm=true, " +
                    "nếu không sẽ bị từ chối và hỏi lại để xác nhận.",
                examples = listOf(
                    "tự tạo lịch quét camera",
                    "xóa lịch trình cũ",
                    "tắt lịch trình abc",
                    "bật lại lịch trình abc"
                ),
                parameters = listOf(
                    PluginParameter("op", "string", "add|list|delete|toggle", true),
                    PluginParameter("pluginId", "string", "Plugin đích khi op=add", false),
                    PluginParameter("action", "string", "Action đích khi op=add", false),
                    PluginParameter("cron", "string", "Cron khi op=add", false),
                    PluginParameter("intervalMinutes", "number", "Phút lặp khi op=add", false),
                    PluginParameter("id", "string", "ID lịch trình khi op=delete/toggle", false),
                    PluginParameter("enabled", "boolean", "Bật/tắt khi op=toggle", false, "boolean"),
                    PluginParameter("confirm", "boolean", "Xác nhận thao tác xóa/tắt lịch (bắt buộc=true)", false, "boolean", defaultValue = false)
                )
            ),
            PluginAction(
                name = "manage_config",
                description = "Quản lý cấu hình hệ thống thay người dùng: op=get|set|list|reset. " +
                    "Với op=reset BẮT BUỘC kèm confirm=true, nếu không sẽ bị từ chối và hỏi lại.",
                examples = listOf(
                    "chỉnh cấu hình cooldown",
                    "reset cấu hình mặc định",
                    "xem toàn bộ cấu hình"
                ),
                parameters = listOf(
                    PluginParameter("op", "string", "get|set|list|reset", true),
                    PluginParameter("key", "string", "Tên biến cấu hình", false),
                    PluginParameter("value", "string", "Giá trị mới khi op=set", false),
                    PluginParameter("pluginId", "string", "Lọc theo plugin khi op=list", false),
                    PluginParameter("confirm", "boolean", "Xác nhận thao tác reset (bắt buộc=true)", false, "boolean", defaultValue = false)
                )
            ),
            PluginAction(
                name = "create_goal",
                description = "Giao một việc phức tạp cho quản gia tự lo liệu lâu dài (không phải lệnh chạy 1 lần), " +
                    "quản gia sẽ tự phân tích và lặp lại theo lịch hoặc theo sự kiện, không cần nhắc lại mỗi lần. " +
                    "Dùng cho các câu như 'kiểm tra X, có gì thì Y', 'nhớ làm Z hàng ngày', 'nếu khách nhắn thì trả lời...'.",
                examples = listOf(
                    "kiểm tra điện an toàn, tôi ra ngoài, có gì thì email cho tôi",
                    "nhớ tưới cây hàng ngày",
                    "nếu có khách nhắn tin tới thì bảo là tôi đang bận",
                    "quản gia giúp tôi trông nhà"
                ),
                parameters = listOf(
                    PluginParameter("goalText", "string", "Nguyên văn yêu cầu người dùng muốn giao cho quản gia", true)
                )
            ),
            PluginAction(
                name = "manage_goal",
                description = "Quản lý các việc đã giao cho quản gia (tạo bởi create_goal): op=list|toggle|delete. " +
                    "Với op=delete BẮT BUỘC kèm confirm=true, nếu không sẽ bị từ chối và hỏi lại để xác nhận.",
                examples = listOf(
                    "xem quản gia đang lo những việc gì",
                    "tạm dừng việc tưới cây",
                    "xoá việc kiểm tra điện"
                ),
                parameters = listOf(
                    PluginParameter("op", "string", "list|toggle|delete", true),
                    PluginParameter("id", "string", "ID việc hoặc từ khóa mô tả việc khi op=toggle/delete", false),
                    PluginParameter("enabled", "boolean", "Bật/tắt khi op=toggle", false, "boolean"),
                    PluginParameter("confirm", "boolean", "Xác nhận thao tác xoá (bắt buộc=true)", false, "boolean", defaultValue = false)
                )
            ),
            PluginAction(
                name = "goal_report",
                description = "Báo cáo lại những việc quản gia đã TỰ LÀM hôm nay để người dùng kiểm tra lại",
                examples = listOf(
                    "quản gia đã làm gì hôm nay",
                    "báo cáo công việc tự động hôm nay"
                )
            )
        )
    )

    private val database by lazy { AppDatabase.getDatabase(context) }

    // ✅ ĐÃ THÊM: Plugin interface bắt buộc initialize()/shutdown() (giống mọi Plugin khác như
    // AppConfigSkill, NotificationSkill...) — trước đây không cần vì class chưa implement Plugin.
    override suspend fun initialize() {}
    override suspend fun shutdown() {}

    override suspend fun execute(action: String, params: Map<String, Any>): AgentKernel.PluginResult {
        logger.d("HousekeeperSkill", "execute: action=$action params=$params")
        return when (action) {
            "check_status" -> handleCheckStatus()
            "set_auto_mode" -> handleSetAutoMode(params)
            "manage_schedule" -> handleManageSchedule(params)
            "manage_config" -> handleManageConfig(params)
            "create_goal" -> handleCreateGoal(params)
            "manage_goal" -> handleManageGoal(params)
            "goal_report" -> handleGoalReport()
            else -> failure("Không tìm thấy hành động: $action")
        }
    }

    // ───────────────────────── check_status ─────────────────────────

    private suspend fun handleCheckStatus(): AgentKernel.PluginResult = withContext(Dispatchers.IO) {
        try {
            val cameras = database.cameraDao().getAllCameras()
            val onlineCameras = cameras.count { it.isOnline == 1 }

            val tuyaDevices = database.tuyaDeviceDao().getAllDevices()
            val onlineDevices = tuyaDevices.count { it.online }

            val unreadAlerts = database.alertDao().getUnreadCountFlow().first()

            val schedules = database.scheduleDao().getAllSchedules()
            val activeSchedules = schedules.count { it.enabled == 1 }

            val autoMode = database.appConfigDao().getConfig(CONFIG_KEY)?.value?.toBoolean() ?: false

            val report = buildString {
                append("📋 Báo cáo quản gia:\n")
                append("• Chế độ tự động: ${if (autoMode) "ĐANG BẬT" else "ĐANG TẮT"}\n")
                append("• Camera online: $onlineCameras/${cameras.size}\n")
                append("• Thiết bị Tuya online: $onlineDevices/${tuyaDevices.size}\n")
                append("• Cảnh báo chưa đọc: $unreadAlerts\n")
                append("• Lịch trình đang hoạt động: $activeSchedules/${schedules.size}")
            }

            success(
                message = report,
                data = mapOf(
                    "onlineCameras" to onlineCameras,
                    "totalCameras" to cameras.size,
                    "onlineDevices" to onlineDevices,
                    "totalDevices" to tuyaDevices.size,
                    "unreadAlerts" to unreadAlerts,
                    "activeSchedules" to activeSchedules,
                    "totalSchedules" to schedules.size,
                    "autoMode" to autoMode
                )
            )
        } catch (e: Exception) {
            failure("Không đọc được trạng thái hệ thống: ${e.message}")
        }
    }

    // ───────────────────────── set_auto_mode ─────────────────────────

    private suspend fun handleSetAutoMode(params: Map<String, Any>): AgentKernel.PluginResult {
        val enabled = params["enabled"] as? Boolean
            ?: return failure("Thiếu tham số enabled (true/false)")

        withContext(Dispatchers.IO) {
            database.appConfigDao().upsert(
                AppConfigEntity(
                    key = CONFIG_KEY,
                    value = enabled.toString(),
                    type = "boolean",
                    pluginId = id,
                    label = "Chế độ quản gia tự động",
                    description = "Bật/tắt tự động hóa của HousekeeperSkill"
                )
            )
        }

        return success("✅ Đã chuyển chế độ quản gia tự động thành: ${if (enabled) "KÍCH HOẠT" else "TẮT"}.")
    }

    // ───────────────────────── manage_schedule ─────────────────────────

    private suspend fun handleManageSchedule(params: Map<String, Any>): AgentKernel.PluginResult {
        val op = params["op"] as? String ?: return failure("Thiếu tham số op (add|list|delete|toggle)")
        val confirmed = params["confirm"] as? Boolean ?: false

        val isDelete = op in DESTRUCTIVE_SCHEDULE_OPS
        val isDisableToggle = op == "toggle" && isFalseValue(params["enabled"])

        if ((isDelete || isDisableToggle) && !confirmed) {
            val targetId = params["id"] as? String ?: "?"
            val warnMsg = if (isDelete) {
                "⚠️ Bạn có chắc muốn XÓA lịch trình \"$targetId\" không? Thao tác này không thể hoàn tác. Trả lời \"có\" để xác nhận."
            } else {
                "⚠️ Bạn có chắc muốn TẮT lịch trình \"$targetId\" không? Nếu đây là lịch quét camera/cảnh báo an ninh, tắt đi nghĩa là hệ thống sẽ ngừng theo dõi âm thầm. Trả lời \"có\" để xác nhận."
            }
            return needMoreInfo(listOf("confirm"), warnMsg)
        }

        logger.i("HousekeeperSkill", "manage_schedule op=$op confirmed=$confirmed params=$params")
        return scheduleSkill.execute(op, params)
    }

    // ───────────────────────── manage_config ─────────────────────────

    private suspend fun handleManageConfig(params: Map<String, Any>): AgentKernel.PluginResult {
        val op = params["op"] as? String ?: return failure("Thiếu tham số op (get|set|list|reset)")
        val confirmed = params["confirm"] as? Boolean ?: false

        if (op in DESTRUCTIVE_CONFIG_OPS && !confirmed) {
            val key = params["key"] as? String ?: "?"
            return needMoreInfo(
                listOf("confirm"),
                "⚠️ Bạn có chắc muốn RESET cấu hình \"$key\" về mặc định không? Trả lời \"có\" để xác nhận."
            )
        }

        logger.i("HousekeeperSkill", "manage_config op=$op confirmed=$confirmed params=$params")
        return appConfigSkill.execute(op, params)
    }

    // ───────────────────────── create_goal ─────────────────────────

    private suspend fun handleCreateGoal(params: Map<String, Any>): AgentKernel.PluginResult {
        val goalText = (params["goalText"] as? String)?.trim()
            ?: return failure("Thiếu tham số goalText (nội dung việc muốn giao)")
        if (goalText.isBlank()) return failure("goalText không được để trống")

        return when (val plan = goalPlanner.plan(goalText)) {
            is GoalPlanResult.NeedsInfo -> needMoreInfo(listOf("goalText"), plan.question)
            is GoalPlanResult.Failed -> failure("Không thể nhận việc này: ${plan.reason}")
            is GoalPlanResult.Ready -> {
                withContext(Dispatchers.IO) { database.goalRuleDao().insertRule(plan.rule) }
                success(
                    message = "✅ Đã nhận việc: \"$goalText\".\n${describeRule(plan.rule)}",
                    data = mapOf("goalId" to plan.rule.id)
                )
            }
        }
    }

    // ───────────────────────── manage_goal ─────────────────────────

    private suspend fun handleManageGoal(params: Map<String, Any>): AgentKernel.PluginResult = withContext(Dispatchers.IO) {
        val op = params["op"] as? String ?: return@withContext failure("Thiếu tham số op (list|toggle|delete)")

        when (op) {
            "list" -> {
                val rules = database.goalRuleDao().getAllRules()
                if (rules.isEmpty()) return@withContext success("Quản gia hiện chưa được giao việc gì.")
                val listing = rules.joinToString("\n\n") { r ->
                    "• [${if (r.enabled == 1) "ĐANG BẬT" else "ĐÃ TẠM DỪNG"}] \"${r.rawGoalText}\" (id: ${r.id})\n  ${describeRule(r)}"
                }
                success("📋 Các việc đã giao cho quản gia:\n\n$listing")
            }
            "toggle" -> {
                val idOrKeyword = params["id"] as? String ?: return@withContext failure("Thiếu tham số id")
                val enabled = params["enabled"] as? Boolean ?: return@withContext failure("Thiếu tham số enabled")
                val rule = resolveGoalRule(idOrKeyword)
                    ?: return@withContext failure("Quản gia không tìm thấy việc nào khớp với \"$idOrKeyword\". Gõ \"xem quản gia đang lo việc gì\" để kiểm tra danh sách.")
                database.goalRuleDao().toggleRule(rule.id, if (enabled) 1 else 0)
                success("✅ Đã ${if (enabled) "BẬT LẠI" else "TẠM DỪNG"} việc: \"${rule.rawGoalText}\".")
            }
            "delete" -> {
                val idOrKeyword = params["id"] as? String ?: return@withContext failure("Thiếu tham số id")
                val confirmed = params["confirm"] as? Boolean ?: false
                val rule = resolveGoalRule(idOrKeyword)
                    ?: return@withContext failure("Quản gia không tìm thấy việc nào khớp với \"$idOrKeyword\". Gõ \"xem quản gia đang lo việc gì\" để kiểm tra danh sách.")
                if (!confirmed) {
                    return@withContext needMoreInfo(
                        listOf("confirm"),
                        "⚠️ Bạn có chắc muốn XOÁ hẳn việc \"${rule.rawGoalText}\" không? Trả lời \"có\" để xác nhận."
                    )
                }
                database.goalRuleDao().deleteRule(rule.id)
                success("🗑️ Đã xoá việc: \"${rule.rawGoalText}\".")
            }
            else -> failure("op không hợp lệ, cần là list|toggle|delete")
        }
    }

    // ───────────────────────── goal_report ─────────────────────────

    private suspend fun handleGoalReport(): AgentKernel.PluginResult = withContext(Dispatchers.IO) {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val logs = database.goalRunLogDao().getLogsSince(midnight)
        if (logs.isEmpty()) {
            return@withContext success("📋 Hôm nay quản gia chưa tự làm việc gì (chưa tới hạn, hoặc chưa giao việc nào).")
        }

        val actionsTaken = logs.count { it.conditionMet == 1 }
        val report = buildString {
            append("📋 Báo cáo việc quản gia đã tự làm hôm nay (${logs.size} lượt kiểm tra, $actionsTaken lần thực sự hành động):\n\n")
            logs.take(20).forEach { log ->
                val icon = if (log.success == 1) "✅" else "❌"
                append("$icon ${log.summary}\n")
            }
        }
        success(report, data = mapOf("totalChecks" to logs.size, "actionsTaken" to actionsTaken))
    }

    /**
     * Phân giải "id" người dùng nói/gõ sang đúng rule trong DB.
     * Ưu tiên khớp UUID chính xác (id thật); nếu không thấy, coi tham số như 1 TỪ KHOÁ
     * và tìm rule có rawGoalText chứa từ khoá đó — vì người dùng bình thường không biết
     * và không nên phải nhớ id dạng "goal_3ca9...".
     */
    private suspend fun resolveGoalRule(idOrKeyword: String): GoalRuleEntity? {
        database.goalRuleDao().getRuleById(idOrKeyword)?.let { return it }
        val keyword = idOrKeyword.trim().lowercase()
        if (keyword.isBlank()) return null
        return database.goalRuleDao().getAllRules().find { it.rawGoalText.lowercase().contains(keyword) }
    }

    /** Mô tả ngắn gọn 1 rule bằng tiếng Việt dễ hiểu, dùng khi tạo mới hoặc liệt kê. */
    private fun describeRule(rule: GoalRuleEntity): String {
        val schedulePart = when {
            rule.triggerType == "EVENT" -> "Khi có sự kiện: ${rule.eventName}"
            rule.intervalMinutes > 0 -> "Lặp lại mỗi ${rule.intervalMinutes} phút"
            rule.cron.isNotBlank() -> "Theo lịch: ${rule.cron}"
            else -> "Không xác định lịch"
        }
        val checkPart = if (rule.checkPluginId.isNotBlank()) " → kiểm tra ${rule.checkPluginId}.${rule.checkAction}" else ""

        val thenSteps = mutableListOf<String>()
        try {
            val arr = JSONArray(rule.thenActions)
            for (i in 0 until arr.length()) {
                val act = arr.optJSONObject(i) ?: continue
                val pId = act.optString("pluginId")
                val aName = act.optString("action")
                thenSteps.add(if (pId == "__system__") "tự trả lời" else "$pId.$aName")
            }
        } catch (e: Exception) {
            thenSteps.add("(chuỗi hành động lỗi định dạng)")
        }
        val thenPart = if (thenSteps.isEmpty()) "" else " → " + thenSteps.joinToString(" → ")

        return "$schedulePart$checkPart$thenPart"
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * Chuẩn hóa việc đọc "enabled == false" bất kể nó tới dưới dạng Boolean thật (từ code nội bộ)
     * hay String (do LLM ở Tier 5 trả JSON dạng chuỗi cho mọi giá trị).
     */
    private fun isFalseValue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> !value
            is String -> value.trim().lowercase() in setOf("false", "0", "tat", "tắt", "no", "off")
            else -> false
        }
    }
}