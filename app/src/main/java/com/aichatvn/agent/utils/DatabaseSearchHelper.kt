package com.aichatvn.agent.utils

import com.aichatvn.agent.data.CallContactDao
import com.aichatvn.agent.data.CallLogDao
import com.aichatvn.agent.data.EventLogDao
import com.aichatvn.agent.data.model.CallDirection
import com.aichatvn.agent.data.model.CallLogStatus
import com.aichatvn.agent.data.model.EventLogEntity
import com.aichatvn.agent.data.model.SearchContract
import com.aichatvn.agent.data.model.QuestionType
import com.aichatvn.agent.data.model.AggregationType
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseSearchHelper @Inject constructor(
    private val eventLogDao: EventLogDao,
    private val timeRangeResolver: TimeRangeResolver,
    private val objectAliasResolver: ObjectAliasResolver,
    // ✅ MỚI: để nhánh isCallCategory hiển thị được TÊN người gọi (không chỉ deviceCode/summary
    // thô) — trước đây isCallCategory chỉ đọc EventLogEntity, bỏ hoàn toàn bảng call_contacts
    // dù bảng này đã có sẵn displayName cho từng deviceCode.
    private val callContactDao: CallContactDao,
    // ✅ MỚI: nguồn sự thật THẬT SỰ cho trạng thái từng cuộc gọi (ANSWERED/MISSED/REJECTED/
    // FAILED) — xem countMissedCalls() bên dưới để hiểu vì sao không còn suy luận từ event_logs.
    private val callLogDao: CallLogDao,
    private val logger: Logger
) {

    companion object {
        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
                .withZone(ZoneId.systemDefault())

        // ✅ MỚI: dùng cho granularity="summary" — "HH:mm-HH:mm ngày dd/MM" (1 dòng/cuộc gọi
        // ghép sẵn giờ bắt đầu-kết thúc từ call_logs, thay vì liệt kê 2-3 dòng event_logs thô).
        private val CALL_SUMMARY_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
        private val CALL_SUMMARY_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    /**
     * ✅ ĐÃ SỬA: trước đây hàm này suy luận "cuộc gọi nhỡ" từ event_logs bằng công thức
     * count(incoming_call) - count(call_answered) — công thức này đếm NHẦM cả cuộc gọi bị
     * TỪ CHỐI (rejectCall() vẫn log event "incoming_call" lúc đổ chuông nhưng không có
     * "call_answered", nên bị trừ ra thành "nhỡ" dù CallSkill đã ghi rõ status=REJECTED).
     * Nguồn sự thật ĐÚNG là bảng call_logs (CallLogEntity.status): CallSkill.finalizeCallLog()
     * chỉ gán MISSED khi direction=INCOMING và cuộc gọi chưa từng CONNECTED (không hề đi qua
     * đường REJECTED/ANSWERED/FAILED) — xem CallSkill.kt dòng 308-312. Đọc thẳng từ đây thay vì
     * suy luận lại, tự động đúng luôn cả trường hợp rejected/failed mà không cần thêm điều kiện.
     * Lưu ý: CallLogDao.observeRecent() giới hạn 200 dòng gần nhất — nếu trong sinceMs..untilMs
     * có nhiều hơn 200 cuộc gọi thì kết quả có thể thiếu; chấp nhận được vì đây vốn là giới hạn
     * đã có sẵn của bảng call_logs, không phải giới hạn mới do hàm này gây ra.
     */
    suspend fun countMissedCalls(sinceMs: Long, untilMs: Long): Int = withContext(Dispatchers.IO) {
        try {
            callLogDao.observeRecent().first().count {
                it.direction == CallDirection.INCOMING &&
                    it.status == CallLogStatus.MISSED &&
                    it.startedAt in sinceMs..untilMs
            }
        } catch (e: Exception) {
            logger.e("DatabaseSearchHelper", "countMissedCalls (call_logs) lỗi: ${e.message}")
            0
        }
    }

    /**
     * Tương thích ngược: Giữ nguyên chữ ký hàm cũ cho các lớp di sản gọi.
     * Chuyển đổi các tham số thô thành cấu trúc SearchContract để chạy qua bộ lọc tập trung mới.
     */
    suspend fun executeSearch(
        timeframe: String,
        objectLabel: String,
        allowedEventTypes: List<String>? = null,
        allowedSources: List<String>? = null,
        limit: Int = 20
    ): SearchResult {
        val timeRange = timeRangeResolver.resolve(timeframe)
        val contract = SearchContract(
            questionType = QuestionType.OTHER,
            sinceMs = timeRange.since,
            untilMs = timeRange.until,
            timeframeLabel = timeRange.label,
            sourceCategory = allowedSources?.firstOrNull(),
            targetObject = objectLabel,
            aggregation = AggregationType.NONE
        )
        return executeSearchContract(contract, limit)
    }

    /**
     * NÂNG CẤP: Trung tâm xử lý Hợp đồng tìm kiếm chung.
     */
    suspend fun executeSearchContract(
        contract: SearchContract,
        limit: Int = 20
    ): SearchResult = withContext(Dispatchers.IO) {
        val rawLogs = eventLogDao.getLogsInTimeframe(contract.sinceMs, contract.untilMs)

        val isChatCategory = contract.sourceCategory?.equals("chat", ignoreCase = true) == true
        val isSpecificChatPlatform = contract.sourceCategory in setOf("facebook", "telegram", "website")
        val isBrainCategory = contract.sourceCategory in setOf("system", "brain", "learning")
        // ✅ MỚI: nhánh riêng cho lịch sử cuộc gọi P2P — CallSkill đã tự ghi event_logs với
        // source="call" (outgoing_call/incoming_call/call_answered/call_rejected/call_ended),
        // filter generic ở dưới (contract.sourceCategory != null -> filter theo source) đã tự
        // hoạt động; nhánh này chỉ để hiển thị summaryText đẹp hơn thay vì rơi vào nhánh chung.
        val isCallCategory = contract.sourceCategory?.equals("call", ignoreCase = true) == true
        val isCameraCategory = contract.sourceCategory?.equals("camera", ignoreCase = true) == true

        var filtered = when {
            isChatCategory -> {
                rawLogs.filter { 
                    it.source.equals("facebook", ignoreCase = true) || 
                    it.source.equals("telegram", ignoreCase = true) || 
                    it.source.equals("website", ignoreCase = true) ||
                    it.source.equals("chat", ignoreCase = true)
                }
            }
            isBrainCategory -> {
                rawLogs.filter { it.source.equals("system", ignoreCase = true) || it.source.equals("brain", ignoreCase = true) }
            }
            contract.sourceCategory != null -> {
                rawLogs.filter { it.source.equals(contract.sourceCategory, ignoreCase = true) }
            }
            else -> {
                rawLogs
            }
        }

        if (!contract.sourceIdOrName.isNullOrBlank()) {
            val normHint = StringSimilarityUtil.normalizeVietnamese(contract.sourceIdOrName.lowercase())
            filtered = filtered.filter { log ->
                val normId = StringSimilarityUtil.normalizeVietnamese(log.sourceId.lowercase())
                val normSummary = StringSimilarityUtil.normalizeVietnamese(log.summary.lowercase())
                
                var matchedInJson = false
                // ✅ ĐÃ SỬA LỖI NHỎ (Bước 3): Đưa "pattern_ignored" vào danh sách bóc tách JSON an toàn
                if (log.eventType in setOf("incoming_message", "chat_session_state_change", "pattern_discovered", "pattern_approved", "pattern_ignored")) {
                    try {
                        val json = JSONObject(log.value)
                        val senderId = json.optString("senderId", "").lowercase()
                        val devName = json.optString("device_name", "").lowercase()
                        matchedInJson = senderId.contains(normHint) || devName.contains(normHint)
                    } catch (_: Exception) {}
                }
                
                normId.contains(normHint) || normSummary.contains(normHint) || matchedInJson
            }
        }

        if (contract.deviceState != null) {
            filtered = filtered.filter { log ->
                log.value.equals(contract.deviceState, ignoreCase = true) ||
                (contract.deviceState == "true" && log.summary.contains("bật", ignoreCase = true)) ||
                (contract.deviceState == "false" && log.summary.contains("tắt", ignoreCase = true))
            }
        }

        // ✅ SỬA (bug chuẩn hoá dấu): trước đây so khớp bằng .contains(keyword, ignoreCase=true)
        // thô — KHÔNG bỏ dấu tiếng Việt. Người dùng thường gõ/keyword hệ thống sinh ra không dấu
        // ("trom", "nguoi la") trong khi log.summary do Groq Vision trả về THƯỜNG CÓ DẤU ("phát
        // hiện người lạ trước cửa"), nên 2 bên gần như không bao giờ khớp được — cùng loại lỗi đã
        // được sửa cho sourceIdOrName ở filter phía trên bằng StringSimilarityUtil.normalizeVietnamese.
        val normalizedKeywords = contract.detailsKeywords.map { StringSimilarityUtil.normalizeVietnamese(it.lowercase()) }

        // ✅ MỚI (chấm điểm thay vì lọc cứng): trước đây targetObject và detailsKeywords được
        // áp lần lượt như 2 bộ lọc AND riêng biệt — log phải khớp CẢ HAI mới được giữ lại. Vấn đề
        // thực tế: Groq Vision mô tả cảnh bằng ngôn ngữ tự nhiên, không phải danh sách nhãn cố
        // định — vd người dùng hỏi "có trộm không" (targetObject=person, keyword="trộm") nhưng
        // Groq chỉ mô tả "một người đàn ông mặc áo đen đứng trước cửa" (không có chữ "trộm" nào)
        // → keyword filter loại bỏ log dù objectAliasResolver đã khớp đúng "person". Kết quả: câu
        // hỏi hợp lý nhưng trả về rỗng dù đúng là có ảnh phù hợp trong DB.
        //
        // Cách sửa: khi CẢ 2 tiêu chí cùng được chỉ định, không còn bắt buộc khớp cả 2 (AND) —
        // chỉ cần khớp ÍT NHẤT 1 tiêu chí là giữ lại (OR), nhưng chấm điểm số tiêu chí khớp được
        // để xếp hạng ở bước sort bên dưới: log khớp CẢ 2 tiêu chí luôn đứng đầu, log chỉ khớp 1
        // tiêu chí xếp sau, trước khi bị cắt bớt theo `limit`. Khi CHỈ 1 tiêu chí được chỉ định,
        // hành vi giữ nguyên như cũ (bắt buộc khớp — không nới lỏng, tránh trả về quá rộng).
        val applyObjectFilter = contract.targetObject != null &&
            contract.targetObject.lowercase() !in setOf("all", "none") &&
            !isChatCategory && !isSpecificChatPlatform && !isCallCategory && !isBrainCategory
        val hasKeywords = normalizedKeywords.isNotEmpty()
        val criteriaCount = (if (applyObjectFilter) 1 else 0) + (if (hasKeywords) 1 else 0)

        // matchScore: 0 = không khớp tiêu chí nào, 1 = khớp 1/2 tiêu chí (hoặc khớp đúng tiêu chí
        // duy nhất khi criteriaCount=1), 2 = khớp cả 2 tiêu chí cùng lúc.
        fun matchScore(log: EventLogEntity): Int {
            var score = 0
            if (applyObjectFilter && objectAliasResolver.matches(log.summary, contract.targetObject!!)) score++
            if (hasKeywords) {
                val normSummary = StringSimilarityUtil.normalizeVietnamese(log.summary.lowercase())
                val matchesSummary = normalizedKeywords.any { kw -> normSummary.contains(kw) }
                val matchesLastMessage = if (!matchesSummary && (isChatCategory || isSpecificChatPlatform)) {
                    try {
                        val lastMessage = JSONObject(log.value).optString("last_message", "")
                        val normLastMessage = StringSimilarityUtil.normalizeVietnamese(lastMessage.lowercase())
                        normLastMessage.isNotBlank() && normalizedKeywords.any { kw -> normLastMessage.contains(kw) }
                    } catch (_: Exception) { false }
                } else false
                if (matchesSummary || matchesLastMessage) score++
            }
            return score
        }

        // ✅ MỚI: cờ đánh dấu kết quả có phải "khớp một phần" hay không — dùng để chèn ghi chú
        // cho AI ở summaryText, tránh AI mặc định coi mọi dòng trả về đều khớp 100% ý người hỏi.
        var hasPartialMatchOnly = false

        if (criteriaCount > 0) {
            val scored = filtered.map { it to matchScore(it) }
            // criteriaCount == 1 -> minScore=1 (bắt buộc khớp, hành vi cũ, không đổi)
            // criteriaCount == 2 -> minScore=1 (chỉ cần khớp ÍT NHẤT 1/2, nới lỏng có kiểm soát)
            filtered = scored.filter { (_, score) -> score >= 1 }.map { it.first }
            hasPartialMatchOnly = criteriaCount == 2 && scored.none { (_, score) -> score == 2 } && filtered.isNotEmpty()
        }

        val totalCount = filtered.size
        val isTruncated = totalCount > limit
        // ✅ MỚI: khi phải cắt bớt (totalCount > limit), ưu tiên theo thứ tự:
        // 1. matchScore cao hơn trước (khớp cả 2 tiêu chí > khớp 1 tiêu chí) — chỉ có ý nghĩa khi
        //    criteriaCount > 0, ngược lại mọi log đều điểm 0 nên không đổi thứ tự.
        // 2. Log CÓ ẢNH (imagePath != null) trước — vì đây thường là lý do người dùng hỏi ("cho
        //    xem hình camera lúc..."). Trước đây chỉ sort theo timestamp — log camera có ảnh có
        //    thể bị log tuya/chat/call mới hơn (không ảnh) đẩy ra khỏi top N.
        // 3. Timestamp mới nhất trước, trong cùng nhóm ưu tiên ở trên.
        val sortedLogs = filtered.sortedWith(
            compareByDescending<EventLogEntity> { if (criteriaCount > 0) matchScore(it) else 0 }
                .thenByDescending { it.imagePath != null }
                .thenByDescending { it.timestamp }
        )
        val truncatedLogs = sortedLogs.take(limit).sortedBy { it.timestamp }

        val summaryText = buildString {
            val resolvedLabel = contract.timeframeLabel ?: "hôm nay"
            
            if (isChatCategory || isSpecificChatPlatform) {
                append("--- Thống kê hoạt động Kênh Chat [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    append("Không ghi nhận tin nhắn mới hay biến động trạng thái phiên chat nào.\n")
                } else {
                    // ✅ ĐÃ SỬA LỖI LỚN (Bước 3): Tính tổng tin nhắn chưa đọc bằng cách gom nhóm theo người gửi (sourceId) và lấy giá trị lớn nhất của nhóm đó
                    val unreadTotal = filtered
                        .filter { it.eventType in setOf("incoming_message", "chat_session_state_change") }
                        .groupBy { it.sourceId }
                        .values
                        .sumOf { logs ->
                            logs.maxByOrNull { it.timestamp }?.let {
                                try { JSONObject(it.value).optInt("unread_count", 0) } catch (e: Exception) { 0 }
                            } ?: 0
                        }

                    // ✅ ĐÃ SỬA LỖI NHỎ (Bước 3): Đếm số lượng khẩn cấp (urgentCount) độc lập theo từng khách hàng để báo cáo chính xác
                    val urgentSenders = mutableSetOf<String>()
                    val activeSenders = mutableSetOf<String>()
                    val intents = mutableMapOf<String, Int>()

                    filtered.forEach { log ->
                        if (log.eventType in setOf("incoming_message", "chat_session_state_change")) {
                            try {
                                val json = JSONObject(log.value)
                                val intent = json.optString("customer_intent", "general")
                                intents[intent] = (intents[intent] ?: 0) + 1
                                
                                val sender = json.optString("senderId")
                                if (sender.isNotEmpty()) {
                                    activeSenders.add(sender)
                                    if (json.optString("urgency") == "high") {
                                        urgentSenders.add(sender)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    append("💡 Chỉ số phiên chat hiện tại:\n")
                    append("• Tổng số khách liên hệ: ${activeSenders.size} khách hàng\n")
                    append("• Số tin chưa đọc đang chờ người trực: $unreadTotal tin nhắn\n")
                    append("• Số yêu cầu khẩn cấp (high urgency): ${urgentSenders.size} trường hợp\n")
                    if (intents.isNotEmpty()) {
                        append("• Ý định của khách: ")
                        append(intents.entries.joinToString(", ") { "${it.key} (${it.value} lần)" })
                        append("\n")
                    }
                    
                    append("\nChi tiết nhật ký hoạt động chat:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        // ✅ MỚI: log.summary chỉ có ý định/khẩn cấp/số chưa đọc, KHÔNG có nội
                        // dung tin nhắn thật — nội dung thật nằm trong log.value (JSON field
                        // "last_message", được HouseManagerSkillImpl.handleChatEventDecision()
                        // ghi kèm mỗi event). Parse ra để trả lời được câu "tin nhắn nói gì".
                        val lastMessage = try {
                            JSONObject(log.value).optString("last_message", "").takeIf { it.isNotBlank() }
                        } catch (e: Exception) { null }
                        val messageNote = lastMessage?.let { " — Nội dung: \"$it\"" } ?: ""
                        append("• [$timeStr] ${log.summary}$messageNote\n")
                    }
                }
            } else if (isCallCategory) {
                append("--- Nhật ký Cuộc gọi P2P [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    append("Không ghi nhận cuộc gọi nào trong khoảng thời gian này.\n")
                } else {
                    // ✅ ĐÃ SỬA: dùng chung countMissedCalls() (nay đọc call_logs.status, xem
                    // comment ở hàm đó) thay vì công thức "incoming_call - call_answered" cũ —
                    // công thức cũ đếm NHẦM cuộc gọi bị từ chối thành "nhỡ".
                    val missedCount = countMissedCalls(contract.sinceMs, contract.untilMs)
                    val outgoingCount = filtered.count { it.eventType == "outgoing_call" }
                    val incomingCount = filtered.count { it.eventType == "incoming_call" }

                    append("💡 Thống kê cuộc gọi:\n")
                    append("• Cuộc gọi đi: $outgoingCount lần\n")
                    append("• Cuộc gọi đến: $incomingCount lần\n")
                    if (missedCount > 0) {
                        append("• Cuộc gọi nhỡ (không nghe, KHÔNG tính cuộc gọi bị từ chối): $missedCount lần\n")
                    }

                    append("\nChi tiết nhật ký cuộc gọi:\n")
                    if (contract.granularity == "summary") {
                        // ✅ MỚI: granularity=summary — đọc thẳng call_logs (đã có sẵn
                        // startedAt/durationSec/status 1-dòng/cuộc gọi, do CallSkill.finalizeCallLog()
                        // ghi) thay vì liệt kê từng dòng event_logs thô (2-3 dòng/cuộc gọi:
                        // outgoing/answered/ended...). Giảm token đáng kể khi có nhiều cuộc gọi
                        // test liên tiếp trong thời gian ngắn — không cần tự ghép cặp bắt đầu/kết
                        // thúc từ text summary (dễ sai khi 2 cuộc gọi cách nhau vài giây).
                        val callLogRows = try {
                            callLogDao.observeRecent().first()
                                .filter { it.startedAt in contract.sinceMs..contract.untilMs }
                                .sortedBy { it.startedAt }
                        } catch (e: Exception) {
                            logger.e("DatabaseSearchHelper", "Không đọc được call_logs cho granularity=summary: ${e.message}")
                            emptyList()
                        }
                        if (callLogRows.isEmpty()) {
                            append("(Không có dữ liệu chi tiết trong khoảng này — xem thống kê ở trên)\n")
                        }
                        callLogRows.forEach { log ->
                            val startStr = CALL_SUMMARY_TIME_FORMATTER.format(Instant.ofEpochMilli(log.startedAt))
                            val endStr = CALL_SUMMARY_TIME_FORMATTER.format(Instant.ofEpochMilli(log.startedAt + log.durationSec * 1000L))
                            val dateStr = CALL_SUMMARY_DATE_FORMATTER.format(Instant.ofEpochMilli(log.startedAt))
                            val directionLabel = if (log.direction == CallDirection.INCOMING) "gọi đến từ" else "gọi đi tới"
                            val peerLabel = log.peerName?.takeIf { it.isNotBlank() }
                                ?: try {
                                    callContactDao.getByDeviceCode(log.peerDeviceCode)?.displayName?.takeIf { it.isNotBlank() }
                                } catch (e: Exception) { null }
                                ?: log.peerDeviceCode
                            val resultLabel = when (log.status) {
                                CallLogStatus.ANSWERED -> "đã kết nối (${log.durationSec}s)"
                                CallLogStatus.MISSED -> "nhỡ"
                                CallLogStatus.REJECTED -> "bị từ chối"
                                else -> "thất bại"
                            }
                            append("• $startStr-$endStr ngày $dateStr: $directionLabel $peerLabel, kết quả $resultLabel\n")
                        }
                    } else {
                        truncatedLogs.forEach { log ->
                            val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                            // ✅ MỚI: tra tên người gọi qua call_contacts (deviceCode = log.sourceId,
                            // theo đúng convention sourceId đang dùng cho camera/tuya trong file này).
                            // Nếu không có contact lưu sẵn (số lạ/chưa lưu danh bạ) thì vẫn hiển thị
                            // summary thô như trước, không để lỗi làm hỏng cả câu trả lời.
                            val contactName = try {
                                callContactDao.getByDeviceCode(log.sourceId)?.displayName?.takeIf { it.isNotBlank() }
                            } catch (e: Exception) {
                                logger.e("DatabaseSearchHelper", "Không tra được contact cho cuộc gọi ${log.sourceId}: ${e.message}")
                                null
                            }
                            val callerNote = contactName?.let { " (người gọi: $it)" } ?: ""
                            append("• [$timeStr] ${log.summary}$callerNote\n")
                        }
                    }
                }
            } else if (isBrainCategory) {
                append("--- Nhật ký Đề xuất & Học tập hệ thống [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    append("Hệ thống hoạt động bình thường, chưa ghi nhận thêm thói quen mới nào.\n")
                } else {
                    val approvedList = mutableListOf<String>()
                    val discoveredList = mutableListOf<String>()
                    val ignoredList = mutableListOf<String>()

                    filtered.forEach { log ->
                        try {
                            val json = JSONObject(log.value)
                            val devName = json.optString("device_name", "Thiết bị")
                            val hour = json.optInt("hour", -1)
                            when (log.eventType) {
                                "pattern_discovered" -> discoveredList.add("- Đề xuất bật/tắt $devName vào lúc ${hour}h")
                                "pattern_approved" -> approvedList.add("- Đã duyệt đặt lịch cho $devName lúc ${json.optInt("hour", 12)}h")
                                "pattern_ignored" -> ignoredList.add("- Đã bỏ qua đề xuất của $devName")
                            }
                        } catch (_: Exception) {}
                    }

                    append("💡 Thống kê quá trình tự học thói quen:\n")
                    append("• Số thói quen mới tự động phát hiện: ${discoveredList.size}\n")
                    append("• Số lịch trình được người dùng phê duyệt: ${approvedList.size}\n")
                    append("• Số đề xuất bị từ chối/bỏ qua: ${ignoredList.size}\n")
                    
                    append("\nChi tiết nhật ký học tập gần nhất:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                }
            } else if (isCameraCategory) {
                append("--- Nhật ký Camera [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    if (contract.questionType == QuestionType.YES_NO) {
                        append("💡 Câu trả lời: KHÔNG. Camera không ghi nhận sự kiện nào trùng khớp.\n")
                    } else {
                        append("Camera hoạt động bình thường, không ghi nhận sự kiện phù hợp.\n")
                    }
                } else {
                    // 🆕 THỐNG KÊ CAMERA (theo camera / theo ngày / cảnh báo / đối tượng): trước
                    // đây camera rơi vào nhánh `else` dùng chung với tuya (chỉ đếm on/off — vô
                    // nghĩa với log camera) hoặc block COMPARE (chỉ so camera-vs-tuya tổng quát).
                    // Đếm theo sourceId (convention định danh camera dùng xuyên suốt file này,
                    // xem comment ở nhánh isCallCategory) — tên hiển thị (vd "khach") đã có sẵn
                    // lồng trong summary dạng "Camera <tên> (<id>) phát hiện:...", nên lấy nhãn
                    // trực tiếp từ summary thay vì cần tra thêm bảng camera.
                    val byCamera = filtered.groupBy { it.sourceId }
                    if (byCamera.size > 1) {
                        append("💡 Thống kê theo camera:\n")
                        byCamera.entries
                            .sortedByDescending { it.value.size }
                            .forEach { (sourceId, logsForCam) ->
                                val label = logsForCam.first().summary
                                    .substringAfter("Camera ", missingDelimiter = "")
                                    .substringBefore(" phát hiện:")
                                    .substringBefore("):")
                                    .let { if (it.isNotBlank()) "$it)" else sourceId }
                                append("• $label: ${logsForCam.size} lần\n")
                            }
                    } else {
                        append("💡 Tổng số lần ghi nhận: $totalCount lần\n")
                    }

                    // 🆕 Theo ngày: nhóm theo dd/MM (tái dùng CALL_SUMMARY_DATE_FORMATTER thay vì
                    // tạo formatter riêng), sắp xếp theo thứ tự thời gian tăng dần cho dễ đọc.
                    val byDay = filtered.groupBy { CALL_SUMMARY_DATE_FORMATTER.format(Instant.ofEpochMilli(it.timestamp)) }
                    if (byDay.size > 1) {
                        append("💡 Thống kê theo ngày:\n")
                        byDay.entries
                            .sortedBy { it.value.minOf { log -> log.timestamp } }
                            .forEach { (day, logsForDay) -> append("• Ngày $day: ${logsForDay.size} lần\n") }
                    }

                    // 🆕 Cảnh báo: camera.value chỉ là chuỗi coarse "Bình thường"/"Phát hiện bất
                    // thường" (xem audit trong ghi chú AgentKernel — camera không có eventType
                    // riêng biệt cho cảnh báo, chỉ có field value này), nên đếm theo đó.
                    val alertCount = filtered.count { it.value.contains("bất thường", ignoreCase = true) }
                    if (alertCount > 0) {
                        append("💡 Số lần cảnh báo bất thường: $alertCount lần\n")
                    }

                    // 🆕 Theo đối tượng: parse hậu tố "[objects: a,b,c]" mà CameraSkill.kt luôn
                    // nối vào cuối summary (xem audit trong ghi chú AgentKernel) — KHÔNG parse
                    // JSON, chỉ tách chuỗi đơn giản vì đây là định dạng text cố định, không phải
                    // JSON thật. Đếm số LOG có chứa mỗi đối tượng (không đếm trùng lặp trong 1 log).
                    val objectCounts = mutableMapOf<String, Int>()
                    filtered.forEach { log ->
                        val objectsPart = log.summary.substringAfter("[objects: ", missingDelimiter = "")
                            .substringBefore("]")
                        if (objectsPart.isNotBlank()) {
                            objectsPart.split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet() // tránh đếm trùng nếu 1 log liệt kê lặp cùng 1 object
                                .forEach { obj -> objectCounts[obj] = (objectCounts[obj] ?: 0) + 1 }
                        }
                    }
                    if (objectCounts.isNotEmpty()) {
                        append("💡 Thống kê theo đối tượng phát hiện (số lần xuất hiện trong log):\n")
                        objectCounts.entries
                            .sortedByDescending { it.value }
                            .take(10) // tránh làm loãng context nếu có quá nhiều loại đối tượng lạ
                            .forEach { (obj, count) -> append("• $obj: $count lần\n") }
                    }

                    append("\n")
                    if (hasPartialMatchOnly) {
                        append("⚠️ Lưu ý: không có sự kiện nào khớp ĐỦ CẢ hai tiêu chí (vật thể + từ khoá) trong câu hỏi — các dòng dưới đây chỉ khớp MỘT PHẦN, hãy diễn đạt với mức độ chắc chắn phù hợp (vd \"có thể là...\"), không khẳng định tuyệt đối.\n")
                    }
                    append("\nChi tiết nhật ký hoạt động:\n")
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                    if (isTruncated) {
                        append("*(Đã ẩn bớt ${totalCount - limit} sự kiện cũ để tối ưu)*\n")
                    }
                }
            } else {
                append("--- Nhật ký tìm kiếm tự động [${resolvedLabel.uppercase()}] ---\n")
                if (filtered.isEmpty()) {
                    if (contract.questionType == QuestionType.YES_NO) {
                        append("💡 Câu trả lời: KHÔNG. Hệ thống không ghi nhận bất kỳ sự kiện nào trùng khớp.\n")
                    } else {
                        append("Hệ thống hoạt động bình thường, không ghi nhận sự kiện phù hợp.\n")
                    }
                } else {
                    when (contract.aggregation) {
                        AggregationType.COUNT -> {
                            append("💡 Thống kê tần suất: Ghi nhận tổng cộng $totalCount lần diễn ra sự kiện.\n")
                            val onCount = filtered.count { it.summary.contains("bật", ignoreCase = true) || it.value == "true" }
                            val offCount = filtered.count { it.summary.contains("tắt", ignoreCase = true) || it.value == "false" }
                            if (onCount > 0 || offCount > 0) {
                                append("-> Trong đó có $onCount lần bật thiết bị và $offCount lần tắt thiết bị.\n")
                            }
                        }
                        AggregationType.COMPARE -> {
                            val cameraCount = filtered.count { it.source == "camera" }
                            val deviceCount = filtered.count { it.source == "tuya" }
                            append("💡 Phân tích so sánh: Camera ghi nhận $cameraCount lần an ninh, Thiết bị có $deviceCount lần thay đổi trạng thái.\n")
                        }
                        AggregationType.NONE -> {
                            if (contract.questionType == QuestionType.YES_NO) {
                                append("💡 Câu trả lời: CÓ. Hệ thống xác nhận ghi nhận sự kiện trùng khớp yêu cầu.\n")
                            }
                        }
                    }

                    append("\nChi tiết nhật ký hoạt động:\n")
                    // ✅ MỚI: khi kết quả chỉ đến từ chấm điểm mềm (không log nào khớp CẢ 2 tiêu
                    // chí targetObject + detailsKeywords cùng lúc), báo rõ cho AI Pass 2 biết đây
                    // là kết quả GẦN ĐÚNG — để AI diễn đạt đúng mức độ chắc chắn ("có thể là...",
                    // "gần giống mô tả...") thay vì khẳng định chắc nịch như khớp hoàn toàn.
                    if (hasPartialMatchOnly) {
                        append("⚠️ Lưu ý: không có sự kiện nào khớp ĐỦ CẢ hai tiêu chí (vật thể + từ khoá) trong câu hỏi — các dòng dưới đây chỉ khớp MỘT PHẦN, hãy diễn đạt với mức độ chắc chắn phù hợp (vd \"có thể là...\"), không khẳng định tuyệt đối.\n")
                    }
                    truncatedLogs.forEach { log ->
                        val timeStr = DATETIME_FORMATTER.format(Instant.ofEpochMilli(log.timestamp))
                        append("• [$timeStr] ${log.summary}\n")
                    }
                    if (isTruncated) {
                        append("*(Đã ẩn bớt ${totalCount - limit} sự kiện cũ để tối ưu)*\n")
                    }
                }
            }
        }

        SearchResult(
            logs = truncatedLogs,
            summaryText = summaryText,
            totalCount = totalCount,
            isTruncated = isTruncated
        )
    }
}