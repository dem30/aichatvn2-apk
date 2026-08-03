package com.aichatvn.agent.config

import com.aichatvn.agent.data.model.AppConfigEntity

/**
 * AppConfigDefaults
 *
 * Catalog toàn bộ biến cấu hình của tất cả plugin.
 * - Seed vào DB lần đầu (INSERT OR IGNORE) khi AppConfigProvider khởi tạo.
 * - Thêm biến mới: chỉ cần thêm 1 dòng vào danh sách, chạy lại app -> tự seed.
 * - Không xoá biến cũ khỏi DB khi upgrade — chỉ IGNORE nếu đã tồn tại.
 */
object AppConfigDefaults {

    // ✅ MỚI: Hàm tra cứu giá trị mặc định DÙNG CHUNG cho toàn bộ codebase — thay vì mỗi
    // file (GroqClientTool, AgentKernel, CameraSkill, PendingIntentResolver...) tự chép lại
    // 1 bản literal riêng rồi dễ lệch nhau (đã xảy ra thật với model vision và max_tokens_router).
    // Dùng: configProvider.getFloat(KEY, AppConfigDefaults.defaultOf(KEY).toFloat())
    private val defaultsByKey: Map<String, String> by lazy { all().associate { it.key to it.value } }
    fun defaultOf(key: String): String = defaultsByKey[key]
        ?: error("Thiếu giá trị mặc định cho key '$key' — thêm vào AppConfigDefaults.all() trước.")

    // ───────────────────────── GROQ ─────────────────────────
    const val GROQ_MODEL_TEXT    = "groq.model_text"
    const val GROQ_MODEL_VISION  = "groq.model_vision"
    const val GROQ_MODEL_ROUTER  = "groq.model_router"
    const val GROQ_MAX_TOKENS_CHAT   = "groq.max_tokens_chat"
    const val GROQ_MAX_TOKENS_VISION = "groq.max_tokens_vision"
    const val GROQ_MAX_TOKENS_ROUTER = "groq.max_tokens_router"

    // ───────────────────────── CAMERA ───────────────────────
    const val CAMERA_DEFAULT_AI_PROMPT       = "camera.default_ai_prompt"
    const val CAMERA_DEFAULT_POSITIVE_KW     = "camera.default_positive_keywords"
    const val CAMERA_DEFAULT_NEGATIVE_KW     = "camera.default_negative_keywords"
    const val CAMERA_COOLDOWN_MS             = "camera.cooldown_ms"
    const val CAMERA_MAX_DAILY_EVENTS        = "camera.max_daily_events"
    const val CAMERA_CIRCUIT_BREAKER_THRESHOLD = "camera.circuit_breaker_threshold"
    const val CAMERA_CIRCUIT_BREAKER_RESET_MS  = "camera.circuit_breaker_reset_ms"
    const val CAMERA_DAILY_REPORT_HOUR       = "camera.daily_report_hour"

    // ✅ MỚI: Tách ngưỡng học báo giả (deltaTrigger/absDiffTrigger/baselineWindow) thành 2 bộ
    // ĐỘC LẬP theo "ban ngày"/"ban đêm" — trước đây dùng chung 1 cặp ngưỡng nên cú sốc chuyển
    // sáng/tối (IR bật/tắt) bị học chung với nhiễu ban ngày, kéo ngưỡng lên cao và làm mất khả
    // năng bắt người thật ban ngày (ngưỡng ban ngày bị "mượn" độ cao từ ban đêm). Nếu
    // dayStartHour >= nightStartHour thì coi cấu hình là không hợp lệ và fallback về mặc định 6/18.
    const val CAMERA_DAY_START_HOUR          = "camera.day_start_hour"
    const val CAMERA_NIGHT_START_HOUR        = "camera.night_start_hour"

    // ✅ MỚI: Tách riêng retention cho alerts (ảnh JPEG, tốn ổ đĩa) và event_logs (chỉ text, rất
    // nhẹ) — trước đây cả 2 dùng chung 1 hằng số cứng 30 ngày trong CameraSkill, khiến máy có
    // nhiều bộ nhớ (TB) không tận dụng được để giữ lịch sử lâu hơn cho Chat trả lời chính xác.
    const val CAMERA_ALERT_RETENTION_DAYS     = "camera.alert_retention_days"
    const val CAMERA_EVENT_LOG_RETENTION_DAYS = "camera.event_log_retention_days"
    
    // ✅ MỚI (Tuần 2 & 3 - Phase 3): Ngưỡng thời gian gộp/nén các sự kiện liên tiếp
    const val CAMERA_ALERT_MERGE_WINDOW_MS    = "camera.alert_merge_window_ms"

    // ───────────────────────── EMAIL ────────────────────────
    const val EMAIL_SUBJECT_PREFIX = "email.subject_prefix"
    const val EMAIL_MAX_ATTACHMENTS = "email.max_attachments"

    // ⚠️ ĐÃ XOÁ: SCHEDULE_CAMERA_SCAN_INTERVAL_MIN ("schedule.camera_scan_interval_min") — rác
    // từ kiến trúc TaskScheduler cũ ("tự quét toàn bộ camera" định kỳ cứng). Đã xác nhận không
    // còn nơi nào đọc key này: startScheduleLoop() trong WebhookGatewayService.kt dùng
    // delay(60_000L) hardcode + đọc lịch từ ScheduleSkill (generic, do người dùng tự tạo), còn
    // báo cáo camera hàng ngày dùng CAMERA_DAILY_REPORT_HOUR (scheduleDailyReport() trong
    // CameraSkill.kt) — không phải key này. Không xoá dòng đã có sẵn trong DB thật (nếu có máy
    // đã seed từ bản cũ) để tránh phải viết Migration, chỉ ngừng khai báo/seed từ đây trở đi.
    // ⚠️ ĐÃ XÓA: HOUSE_MANAGER_PROTECT_LIGHT/SIREN/CAMERAS/ACTIONS — cấu hình rời rạc riêng cho
    // nút Panic độc lập cũ (đèn/còi/camera dự phòng + kịch bản 5-bước fallback). Nút Panic đã
    // được thay bằng "Chạy thủ công" ngay trên từng Nhóm kịch bản — MỌI kích hoạt (tự động lẫn
    // thủ công) giờ đọc chung 1 nguồn duy nhất: HOUSE_MANAGER_WORKFLOWS bên dưới.
    const val HOUSE_MANAGER_WORKFLOWS         = "house_manager.workflows"
    // ✅ MỚI: Khung giờ "Đang ngủ / Ban đêm" do chủ nhà tự chỉnh trên HouseManagerScreen —
    // thay cho hardcode cứng "hour >= 22 || hour < 6" trong isNightTime() trước đây.
    // Nếu start > end nghĩa là khung giờ vắt qua nửa đêm (vd 22 -> 6 sáng hôm sau).
    const val HOUSE_MANAGER_SLEEP_START_HOUR  = "house_manager.sleep_start_hour"
    const val HOUSE_MANAGER_SLEEP_END_HOUR    = "house_manager.sleep_end_hour"

    // ───────────────────────── GLOBAL ───────────────────────
    const val GLOBAL_FUZZY_THRESHOLD        = "global.fuzzy_threshold"
    const val GLOBAL_ALIAS_THRESHOLD        = "global.alias_threshold"
    const val GLOBAL_CHAT_QA_THRESHOLD      = "global.chat_qa_threshold"
    const val GLOBAL_TIER2_HIGH_CONFIDENCE  = "global.tier2_high_confidence"
    const val GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL = "global.block_external_device_control"

    // ✅ MỚI: Giới hạn số dòng nhật ký sự kiện tối đa gửi lên AI (chống Token Bloat)
    const val GLOBAL_MAX_MEMORY_LOGS        = "global.max_memory_logs"

    // ✅ MỚI: Số câu tối đa AI được hướng dẫn trả lời mỗi lượt (chỉ là gợi ý trong prompt, không
    // cắt cứng như max_tokens). Cấu hình được thay vì code cứng, áp dụng CHUNG cho toàn hệ thống
    // — mọi kênh trò chuyện với AI (nội bộ, Facebook, Telegram, Website) đều dùng chung 1 giá trị này.
    const val GLOBAL_CHAT_MAX_SENTENCES     = "global.chat_max_sentences"

    // ✅ MỚI: Prompt hệ thống do ADMIN TỰ CẤU HÌNH (persona, giọng điệu, quy tắc trả lời...) — thay
    // thế hoàn toàn phần văn bản hardcode cứng trước đây ("Bạn là trợ lý tư vấn... Quy tắc 1-7...").
    // Code chỉ còn tự động thêm PHẦN KỸ THUẬT bắt buộc (chỉ thị gọi tool catalog_search đúng JSON
    // schema mà interceptAndExecuteToolCall() parse được) — không tự ý thêm persona/quy tắc nào khác.
    const val GLOBAL_CHAT_SYSTEM_PROMPT     = "global.chat_system_prompt"

    // ✅ MỚI: Luật diễn giải ý định người dùng thành JSON tool call (category/target/keyword/
    // state/call_status/timeframe/object/granularity) do ADMIN TỰ CHỈNH câu chữ — tách khỏi
    // buildToolCallingGuard() trong AgentKernel.kt. Code vẫn tự giữ cố định: marker nội bộ
    // (DB_SEARCH_GUARD_MARKER), dòng schema JSON {"tool":"db_search",...} (tên field bắt buộc
    // đúng để interceptAndExecuteToolCall() parse được — sửa sai tên field ở đây sẽ hỏng tool
    // call), và khối danh sách camera/thiết bị/danh bạ/kênh chat (luôn lấy động từ DB thật lúc
    // chạy, không đưa ra config vì sẽ mất khả năng tự cập nhật).
    const val GLOBAL_TOOL_GUARD_RULES       = "global.tool_guard_rules"

    // ✅ MỚI: Chỉ thị chèn vào khối <SYSTEM_MEMORY> ở LƯỢT 2 (Two-Pass Second Call trong
    // AgentKernel.kt) — lúc này db_search đã có dữ liệu thật, model không còn được gọi tool nữa
    // mà phải trả lời thẳng dựa trên dữ liệu. ADMIN TỰ CHỈNH câu chữ (giọng điệu, độ dài câu trả
    // lời...) ở đây. CHỈ áp dụng cho Pass 2 — Pass 1 (lượt hỏi model có cần gọi tool hay không)
    // không dùng key này.
    // ⚠️ LƯU Ý KỸ THUẬT: muốn giữ tính năng tự động đính kèm ảnh camera đúng dòng dữ liệu AI đã
    // nhắc tới, PHẢI giữ nguyên literal "[REF_TIMESTAMPS: ...]" trong câu chữ (không đổi tên, có
    // thể sửa phần mô tả xung quanh) — đây là marker cố định mà extractRefTimestamps() trong
    // AgentKernel.kt dò tìm bằng regex (không phân biệt hoa/thường) trong CÂU TRẢ LỜI của model.
    // Nếu bạn xoá hẳn yêu cầu này khỏi chỉ thị, model sẽ không tự khai timestamp nữa → hệ thống
    // vẫn hoạt động bình thường, chỉ là câu trả lời sẽ không có ảnh camera đính kèm.
    const val GLOBAL_PASS2_MEMORY_INSTRUCTION = "global.pass2_memory_instruction"

    // ✅ MỚI: Từ khoá xác nhận Có/Không khi hệ thống hỏi lại (vd: xác nhận khoá điều khiển thiết bị) —
    // thay cho 2 setOf hardcode cứng trước đây trong AgentKernel.parseYesNo().
    const val GLOBAL_CONFIRM_YES_KEYWORDS   = "global.confirm_yes_keywords"
    const val GLOBAL_CONFIRM_NO_KEYWORDS    = "global.confirm_no_keywords"

    // ✅ MỚI: Câu để thoát chế độ khoá điều khiển 1 plugin riêng biệt — thay cho setOf hardcode
    // cứng trước đây trong AgentKernel.isExitLockPhrase().
    const val GLOBAL_EXIT_LOCK_PHRASES      = "global.exit_lock_phrases"

    // ✅ MỚI: Từ đệm (filler words) bị lọc bỏ khỏi câu trước khi tách mệnh đề / nhận diện lệnh —
    // thay cho CLAUSE_FILLER_WORDS hardcode cứng trước đây trong RoutingPipeline.kt.
    // LƯU Ý: khác các key trên, danh sách này được so khớp trên văn bản GỐC CÒN DẤU (không qua
    // normalizeVietnamese), nên giá trị mặc định giữ nguyên dấu.
    const val GLOBAL_CLAUSE_FILLER_WORDS    = "global.clause_filler_words"

    // ✅ MỚI: Từ khoá kích hoạt tra cứu lịch sử (Memory Recall) trong ChatSkill.isPastMemoryQuery() —
    // thay cho PAST_QUERY_KEYWORDS hardcode cứng trước đây.
    const val GLOBAL_MEMORY_QUERY_KEYWORDS  = "global.memory_query_keywords"
    // ✅ MỚI: từ khoá riêng để nhận diện câu hỏi thuộc miền "cuộc gọi" — tách khỏi
    // GLOBAL_MEMORY_QUERY_KEYWORDS vì mục đích khác: không chỉ kích hoạt tra ký ức, mà còn
    // dùng để route sourceCategory="call" ở AgentKernel (mentionsAppDomain, sourceHint resolve)
    // và ChatSkill (buildMemoryContext), để user tự thêm từ khoá qua Settings mà không cần sửa code.
    const val GLOBAL_CALL_KEYWORDS          = "global.call_keywords"

    // ✅ MỚI: cùng khuôn với GLOBAL_CALL_KEYWORDS — tách riêng từ khoá nhận diện miền cho
    // camera/thiết bị/kênh chat, để mentionsAppDomain() ở AgentKernel không còn hardcode cứng
    // 2 danh sách (safeNormalizedKeywords/diacriticSensitiveKeywords), và user có thể tự thêm
    // từ khoá riêng qua Settings mà không cần sửa code/build lại app — nhất quán với call.
    const val GLOBAL_CAMERA_KEYWORDS        = "global.camera_keywords"
    const val GLOBAL_DEVICE_KEYWORDS        = "global.device_keywords"
    const val GLOBAL_CHAT_KEYWORDS          = "global.chat_keywords"

    // ───────────────────────── CLOUD GATEWAY ────────────────
    const val GLOBAL_GATEWAY_URL            = "global.gateway_url"
    const val GLOBAL_GATEWAY_TOKEN          = "global.gateway_token"

    // ───────────────────────── ĐA KÊNH (OMNICHANNEL) ────────
    const val FACEBOOK_PAGE_ACCESS_TOKEN    = "facebook.page_access_token"
    const val TELEGRAM_BOT_TOKEN            = "telegram.bot_token"
    const val WEBSITE_ALLOWED_ORIGINS       = "website.allowed_origins"
    const val WEBSITE_WIDGET_KEY            = "website.widget_key"

    // ───────────────────────── CUỘC GỌI P2P (CALL) ──────────
    // ✅ MỚI: Mã định danh thiết bị (8 ký tự), tự sinh khi service kết nối Gateway lần đầu,
    // dùng để nhận cuộc gọi thoại/video P2P (WebRTC) từ thiết bị khác qua Render Gateway.
    const val CALL_DEVICE_CODE              = "call.device_code"

    // ✅ MỚI: TURN server (Metered.ca) — CHỈ có STUN không đủ để 2 máy kết nối P2P qua
    // NAT symmetric (rất phổ biến với mạng 4G/LTE VN và nhiều router WiFi) — kết quả là
    // signaling (offer/answer) trao đổi thành công (nút Nghe/Từ chối hiện ra) nhưng
    // audio/video KHÔNG BAO GIỜ truyền được. TURN server relay media khi P2P trực tiếp
    // thất bại. Người dùng tự đăng ký tài khoản free tại metered.ca (500MB/tháng, không
    // cần thẻ), lấy 2 giá trị này từ Dashboard -> TURN Server -> credential đầu tiên.
    // Để trống = app chỉ dùng STUN công khai (Google) như trước — cuộc gọi có thể lúc
    // được lúc không tùy mạng.
    const val CALL_TURN_METERED_DOMAIN      = "call.turn_metered_domain"
    const val CALL_TURN_METERED_API_KEY     = "call.turn_metered_api_key"

    // ─────────────────────────────────────────────────────────
    //  Danh sách đầy đủ để seed vào DB
    // ─────────────────────────────────────────────────────────
    fun all(): List<AppConfigEntity> = listOf(

        // ── CLOUD GATEWAY ──
        AppConfigEntity(
            key = GLOBAL_GATEWAY_URL,
            value = "https://aichatvn2-apk-gateway.onrender.com",
            type = "string",
            pluginId = "global",
            label = "Cổng đám mây (Cloud Gateway URL)",
            description = "Địa chỉ máy chủ trung gian cố định trên Render.com để nhận Webhook."
        ),
        AppConfigEntity(
            key = GLOBAL_GATEWAY_TOKEN,
            value = "aichatvn_secret_token_123",
            type = "string",
            pluginId = "global",
            label = "Mã xác thực Gateway (Gateway Token)",
            description = "Mật khẩu bảo mật dùng chung để xác thực kết nối giữa điện thoại và Render Gateway."
        ),

        // ── CUỘC GỌI P2P (CALL) — TURN Server ──
        AppConfigEntity(
            key = CALL_TURN_METERED_DOMAIN,
            value = "",
            type = "string",
            pluginId = "call",
            label = "Metered Domain (TURN Server)",
            description = "Tên domain dạng \"xxxx.metered.live\" — lấy từ Dashboard metered.ca -> Developers. Để trống nếu chưa đăng ký, cuộc gọi sẽ chỉ dùng STUN công khai (có thể lúc được lúc không tùy mạng)."
        ),
        AppConfigEntity(
            key = CALL_TURN_METERED_API_KEY,
            value = "",
            type = "string",
            pluginId = "call",
            label = "Metered API Key (TURN Server)",
            description = "API Key hiển thị cùng credential trên Dashboard metered.ca -> TURN Server -> credential đầu tiên. Đây là API Key scoped-theo-credential, an toàn dùng trực tiếp trên máy (KHÔNG phải Secret Key)."
        ),

        // ── FACEBOOK MESSENGER ──
        AppConfigEntity(
            key = FACEBOOK_PAGE_ACCESS_TOKEN,
            value = "",
            type = "string",
            pluginId = "facebook",
            label = "Facebook Page Access Token",
            description = "Mã token kết nối của Fanpage Facebook dùng để gửi tin nhắn phản hồi cho người dùng."
        ),
        // ── TELEGRAM ──
        AppConfigEntity(
            key = TELEGRAM_BOT_TOKEN,
            value = "",
            type = "string",
            pluginId = "telegram",
            label = "Telegram Bot Token",
            description = "Mã token của Bot Telegram (do @BotFather cấp) dùng để kết nối nhận/gửi tin nhắn."
        ),

        // ── WEBSITE CHAT WIDGET ──
        AppConfigEntity(
            key = WEBSITE_ALLOWED_ORIGINS,
            value = "*",
            type = "string",
            pluginId = "website",
            label = "Allowed Origins (Tên miền được phép nhúng)",
            description = "Danh sách tên miền được phép nhúng khát chat (cách nhau bởi dấu phẩy, để dấu * là cho phép tất cả)."
        ),
        AppConfigEntity(
            key = WEBSITE_WIDGET_KEY,
            value = "",
            type = "string",
            pluginId = "website",
            label = "Widget Key (Mã nhúng công khai)",
            description = "Tự động sinh khi app kết nối Cloud Gateway lần đầu. An toàn để lộ công khai trên website — KHÁC với Gateway Token bí mật."
        ),

        // ── CUỘC GỌI P2P (CALL) ──
        AppConfigEntity(
            key = CALL_DEVICE_CODE,
            value = "",
            type = "string",
            pluginId = "call",
            label = "Mã thiết bị cuộc gọi (Device Code)",
            description = "Mã định danh duy nhất (8 ký tự) dùng để nhận cuộc gọi thoại/video P2P từ các thiết bị khác."
        ),

        // ── GROQ ──
        AppConfigEntity(
            key = GROQ_MODEL_TEXT,
            value = "openai/gpt-oss-120b",
            type = "string",
            pluginId = "groq",
            label = "Model chat chính",
            description = "Model Groq dùng cho hội thoại văn bản (chat, QA). Thay đổi nếu model bị khát."
        ),
        AppConfigEntity(
            key = GROQ_MODEL_VISION,
            value = "qwen/qwen3.6-27b", // ✅ SỬA: llama-4-scout đã bị Groq deprecate (17/06/2026), trả 404. Lưu ý qwen3.6-27b là preview model bên Groq, có thể đổi tiếp trong tương lai — kiểm tra console.groq.com/docs/deprecations định kỳ.
            type = "string",
            pluginId = "groq",
            label = "Model vision (phân tích ảnh)",
            description = "Model Groq dùng khi người dùng gửi ảnh hoặc camera gửi ảnh để phân tích."
        ),
        AppConfigEntity(
            key = GROQ_MODEL_ROUTER,
            value = "openai/gpt-oss-20b",
            type = "string",
            pluginId = "groq",
            label = "Model router (phân loại lệnh)",
            description = "Model nhỏ, nhanh, rẻ để phân loại intent (là lệnh thiết bị hay chat thường)."
        ),
        AppConfigEntity(
            key = GROQ_MAX_TOKENS_CHAT,
            value = "500",
            type = "int",
            pluginId = "groq",
            label = "Max tokens – chat",
            description = "Số token tối đa model text trả về mỗi lượt chat. Tăng nếu câu trả lời bị cắt."
        ),
        AppConfigEntity(
            key = GROQ_MAX_TOKENS_VISION,
            // ✅ ĐÃ SỬA: 350 được tính cho schema "objects" CŨ (chuỗi phẳng, 1 nhãn/vật thể).
            // Schema mới có 5 trường (type/name/details/location/relations) cho MỖI vật thể + các
            // trường khác — với ảnh có 2-3 vật thể trở lên, model dễ dùng hết 350 token trước khi
            // viết tới trường "description" (nằm cuối JSON) -> description rỗng -> hệ thống phải
            // fallback hiển thị nguyên JSON thô cho người dùng (đã thấy đúng hiện tượng này qua
            // thông báo đẩy). Tăng lên 800 để đủ chỗ cho vài vật thể + description đầy đủ.
            value = "800",
            type = "int",
            pluginId = "groq",
            label = "Max tokens – vision",
            description = "Số token tối đa model vision trả về khi phân tích ảnh camera. Schema objects càng nhiều vật thể/trường càng cần token lớn hơn để không bị cắt cụt trước khi tới phần mô tả."
        ),
        AppConfigEntity(
            key = GROQ_MAX_TOKENS_ROUTER,
            value = "500",
            type = "int",
            pluginId = "groq",
            label = "Max tokens – router",
            description = "Số token tối đa model router trả về khi phân loại lệnh."
        ),

        // ── CAMERA ──
        AppConfigEntity(
            key = CAMERA_DEFAULT_AI_PROMPT,
            value = "Camera giám sát thửa đất. Hãy xem có người/xe? hoặc xây dựng không. Nếu có ghi: cảnh báo và mô tả. Ngược lại ghi: Bình thường và mô tả.",
            type = "string",
            pluginId = "camera",
            label = "Prompt AI mặc định",
            description = "Prompt gửi đến Groq khi camera chưa được cấu hình prompt riêng."
        ),
        AppConfigEntity(
            key = CAMERA_DEFAULT_POSITIVE_KW,
            value = "cảnh báo",
            type = "string",
            pluginId = "camera",
            label = "Từ khoá cảnh báo mặc định",
            description = "Danh sách từ khoá (cách nhau bằng dấu phẩy) để nhận diện phản hồi AI là CẢNH BÁO. Dùng cho camera chưa cấu hình riêng."
        ),
        AppConfigEntity(
            key = CAMERA_DEFAULT_NEGATIVE_KW,
            value = "bình thường",
            type = "string",
            pluginId = "camera",
            label = "Từ khoá bình thường mặc định",
            description = "Từ khoá để nhận diện phản hồi AI là BÌNH THƯỜNG. Dùng cho camera chưa cấu hình riêng."
        ),
        AppConfigEntity(
            key = CAMERA_COOLDOWN_MS,
            value = "${3 * 60 * 60 * 1000L}",
            type = "long",
            pluginId = "camera",
            label = "Cooldown giữa 2 cảnh báo (ms)",
            description = "Thời gian chờ tối thiểu (milli-giây) giữa 2 lần gửi cảnh báo cho cùng 1 camera. Mặc định 3 giờ = 10800000 ms."
        ),
        AppConfigEntity(
            key = CAMERA_MAX_DAILY_EVENTS,
            value = "50",
            type = "int",
            pluginId = "camera",
            label = "Sự kiện tối đa / ngày / camera",
            description = "Giới hạn số sự kiện ghi nhận mỗi ngày trên 1 camera để tránh spam alert."
        ),
        AppConfigEntity(
            key = CAMERA_CIRCUIT_BREAKER_THRESHOLD,
            value = "3",
            type = "int",
            pluginId = "camera",
            label = "Circuit breaker – ngưỡng offline",
            description = "Số lần fetch liên tiếp thất bại trước khi camera bị đánh dấu offline (circuit breaker mở)."
        ),
        AppConfigEntity(
            key = CAMERA_CIRCUIT_BREAKER_RESET_MS,
            value = "${30 * 60 * 1000L}",
            type = "long",
            pluginId = "camera",
            label = "Circuit breaker – reset (ms)",
            description = "Thời gian (ms) sau khi circuit breaker mở thì thử kết nối lại. Mặc định 30 phút."
        ),
        AppConfigEntity(
            key = CAMERA_DAILY_REPORT_HOUR,
            value = "20",
            type = "int",
            pluginId = "camera",
            label = "Giờ gửi báo cáo ngày",
            description = "Giờ trong ngày (0–23) để gửi báo cáo tổng hợp. Mặc định 20 giờ (8 PM)."
        ),
        AppConfigEntity(
            key = CAMERA_DAY_START_HOUR,
            value = "6",
            type = "int",
            pluginId = "camera",
            label = "Giờ bắt đầu ban ngày (camera)",
            description = "Giờ trong ngày (0–23) camera bắt đầu tính là 'ban ngày' — dùng để tách riêng ngưỡng học báo giả (deltaTrigger/absDiffTrigger/baseline) giữa ban ngày và ban đêm, tránh cú sốc IR ban đêm làm ngưỡng ban ngày bị kẹt cao. Mặc định 6h sáng."
        ),
        AppConfigEntity(
            key = CAMERA_NIGHT_START_HOUR,
            value = "18",
            type = "int",
            pluginId = "camera",
            label = "Giờ bắt đầu ban đêm (camera)",
            description = "Giờ trong ngày (0–23) camera bắt đầu tính là 'ban đêm'. Từ giờ này đến trước giờ bắt đầu ban ngày, hệ thống dùng riêng bộ ngưỡng học báo giả cho ban đêm. Mặc định 18h (6 giờ tối)."
        ),
        AppConfigEntity(
            key = CAMERA_ALERT_RETENTION_DAYS,
            value = "30",
            type = "int",
            pluginId = "camera",
            label = "Lưu lịch sử cảnh báo (ngày)",
            description = "Số ngày giữ lại bản ghi cảnh báo + ảnh đính kèm trước khi tự xóa. Tăng lên nếu máy còn nhiều bộ nhớ."
        ),
        AppConfigEntity(
            key = CAMERA_EVENT_LOG_RETENTION_DAYS,
            value = "30",
            type = "int",
            pluginId = "camera",
            label = "Lưu nhật ký sự kiện (ngày)",
            description = "Số ngày giữ lại nhật ký sự kiện (event_logs) dùng cho Chat trả lời câu hỏi quá khứ. Chỉ là text nên có thể để dài hơn retention ảnh mà không tốn nhiều dung lượng."
        ),
        
        // ✅ ĐÃ THÊM: Seed cấu hình khoảng thời gian gộp/nén sự kiện (Tuần 2 & 3)
        AppConfigEntity(
            key = CAMERA_ALERT_MERGE_WINDOW_MS,
            value = "${5 * 60 * 1000L}",
            type = "long",
            pluginId = "camera",
            label = "Cửa sổ gộp cảnh báo liên tiếp (ms)",
            description = "Nếu 2 cảnh báo cùng camera, cùng trạng thái xảy ra cách nhau dưới thời gian này thì gộp thành 1 sự kiện kéo dài (start-end) thay vì tạo bản ghi mới. Mặc định 5 phút = 300000 ms."
        ),

        // ── EMAIL ──
        AppConfigEntity(
            key = EMAIL_SUBJECT_PREFIX,
            value = "🚨 CẢNH BÁO AN NINH",
            type = "string",
            pluginId = "email",
            label = "Tiền tố tiêu đề email cảnh báo",
            description = "Chuỗi đứng đầu subject của email cảnh báo camera."
        ),
        AppConfigEntity(
            key = EMAIL_MAX_ATTACHMENTS,
            value = "10",
            type = "int",
            pluginId = "email",
            label = "Số ảnh đính kèm tối đa",
            description = "Số ảnh bằng chứng đính kèm tối đa mỗi email cảnh báo."
        ),

        // ── SCHEDULE ──
        // ⚠️ ĐÃ XOÁ seed của SCHEDULE_CAMERA_SCAN_INTERVAL_MIN — cùng lý do đã xoá khai báo
        // const ở trên. Bảng "schedule" hiện chỉ còn ScheduleSkill tự quản lý lịch qua
        // ScheduleEntity (bảng schedules), không cần config chu kỳ quét cứng nào ở đây nữa.

        // ── HOUSE MANAGER ──
        // ⚠️ ĐÃ XÓA seed của HOUSE_MANAGER_PROTECT_LIGHT/SIREN/CAMERAS/ACTIONS — cùng lý do đã
        // xóa khai báo const ở trên. Nhóm "wf_security" seed sẵn bên dưới đóng vai trò kịch bản
        // bảo vệ mặc định (tương đương 5-bước cũ), luôn có sẵn nút "Chạy thủ công" trên UI.
        AppConfigEntity(
            key = HOUSE_MANAGER_WORKFLOWS,
            value = """
                [
                  {
                    "id": "wf_security",
                    "label": "Kịch bản Bảo vệ an ninh Sân trước",
                    "triggerSource": "camera.cam_01.state=suspicious",
                    "enabled": true,
                    "steps": [
                      {"pluginId":"smart_switch", "action":"set", "params":{"device":"đèn sân trước", "state":"true"}},
                      {"pluginId":"notification", "action":"send", "params":{"title":"🚨 PHÁT HIỆN NGHI VẤN SÂN TRƯỚC", "message":"Quản gia đã phát hiện bất thường và đang kích hoạt các kịch bản an toàn."}},
                      {"pluginId":"house_manager", "action":"delay", "params":{"delayMs":"30000"}},
                      {"pluginId":"camera", "action":"scan", "params":{"cameraId":"cam_01", "force":"true"}},
                      {"pluginId":"house_manager", "action":"delay", "params":{"delayMs":"5000"}},
                      {"pluginId":"house_manager", "action":"check_precondition", "params":{"source":"camera", "camera":"cam_01", "attribute":"state", "expected":"suspicious"}},
                      {"pluginId":"smart_switch", "action":"set", "params":{"device":"còi báo động", "state":"true"}},
                      {"pluginId":"house_manager", "action":"delay", "params":{"delayMs":"60000"}},
                      {"pluginId":"smart_switch", "action":"set", "params":{"device":"còi báo động", "state":"false"}}
                    ]
                  }
                ]
            """.trimIndent(),
            type = "string",
            pluginId = "house_manager",
            label = "Các nhóm kịch bản điều hành Quản gia",
            description = "JSON chứa danh sách các nhóm kịch bản liên hoàn được Quản gia tự học hoặc chủ nhà tự xây. Mẫu seed dùng camera 'cam_01' làm ví dụ — chỉnh lại triggerSource cho khớp camera/thiết bị thật của bạn."
        ),
        AppConfigEntity(
            key = HOUSE_MANAGER_SLEEP_START_HOUR,
            value = "22",
            type = "int",
            pluginId = "house_manager",
            label = "Giờ bắt đầu ngủ",
            description = "Giờ trong ngày (0-23) mà Quản gia bắt đầu coi là 'giờ ngủ' để chuyển Mood sang Đang ngủ/Ban đêm và áp dụng Chính sách ban đêm yên tĩnh. Mặc định 22h, giữ đúng hành vi cũ khi chưa cấu hình."
        ),
        AppConfigEntity(
            key = HOUSE_MANAGER_SLEEP_END_HOUR,
            value = "6",
            type = "int",
            pluginId = "house_manager",
            label = "Giờ thức dậy",
            description = "Giờ trong ngày (0-23) mà Quản gia coi là kết thúc 'giờ ngủ'. Mặc định 6h sáng, giữ đúng hành vi cũ khi chưa cấu hình."
        ),

        // ── GLOBAL ──
        AppConfigEntity(
            key = GLOBAL_FUZZY_THRESHOLD,
            value = "0.5",
            type = "float",
            pluginId = "global",
            label = "Ngưỡng fuzzy match (Fuzzy Threshold)",
            description = "full Threshold."
        ),
        AppConfigEntity(
            key = GLOBAL_ALIAS_THRESHOLD,
            value = "0.5",
            type = "float",
            pluginId = "global",
            label = "Ngưỡng alias gửi LLM (Alias Threshold)",
            description = "Alias."
        ),

        AppConfigEntity(
            key = GLOBAL_CHAT_QA_THRESHOLD,
            value = "0.8",
            type = "float",
            pluginId = "global",
            label = "Ngưỡng khớp câu hỏi Chat (Chat QA Threshold)",
            description = "Độ tương tự tối thiểu (0.0–1.0) để 1 câu trong catalogue Chat "  
        ),
      
        AppConfigEntity(
            key = GLOBAL_TIER2_HIGH_CONFIDENCE,
            value = "0.85",
            type = "float",
            pluginId = "global",
            label = "Ngưỡng tin cậy Tầng 2 (High Confidence)",
            description = "Điểm của Tầng 2 phải đạt tối thiểu từ mức này trở lên mới thực thi trực tiếp không qua LLM. 0.0–1.0. Thấp = dễ thực thi trực tiếp hơn. Cao = phải khớp rất sát. Mặc định 0.85."
        ),
        AppConfigEntity(
            key = GLOBAL_BLOCK_EXTERNAL_DEVICE_CONTROL,
            value = "true",
            type = "boolean",
            pluginId = "global",
            label = "Chặn điều khiển thiết bị từ kênh ngoài",
            description = "Mặc định BẬT (an toàn): khách chat từ Facebook/Telegram/Website chỉ được trả lời QA/AI thông thường, KHÔNG được kích hoạt bất kỳ lệnh điều khiển thiết bị nào. TẮT switch này để cấp cho khách ngoại kênh quyền điều khiển ngang với chat nội bộ. Không ảnh hưởng đến chat nội bộ (default_user), luôn có đầy đủ quyền."
        ),
        AppConfigEntity(
            key = GLOBAL_MAX_MEMORY_LOGS,
            value = "30",
            type = "int",
            pluginId = "global",
            label = "Số lượng nhật ký trí nhớ tối đa gửi lên AI",
            description = "Giới hạn số lượng dòng nhật ký sự kiện tối đa gửi làm ngữ cảnh cho AI để chống token bloat. Mặc định là 30 dòng."
        ),
        AppConfigEntity(
            key = GLOBAL_CHAT_MAX_SENTENCES,
            value = "4",
            type = "int",
            pluginId = "global",
            label = "Số câu tối đa mỗi câu trả lời AI",
            description = "Hướng dẫn AI trả lời tối đa bao nhiêu câu mỗi lượt chat (chỉ là gợi ý trong prompt, không cắt cứng như max_tokens). Áp dụng CHUNG cho mọi kênh trò chuyện với AI: nội bộ, Facebook, Telegram, Website. Mặc định 4 câu."
        ),
        AppConfigEntity(
            key = GLOBAL_CHAT_SYSTEM_PROMPT,
            value = "Bạn là trợ lý tư vấn. Chỉ trả lời dựa trên thông tin được cung cấp, không bịa. Trả lời ngắn gọn, tự nhiên, thân thiện.\n\n" +
                "⚠️ Khi khách muốn đặt hàng/hẹn lịch/thanh toán: hệ thống KHÔNG tự xử lý được — chỉ trả lời rằng tin nhắn đã được ghi nhận và nhân viên sẽ xem, phản hồi sớm nhất có thể. Không tự nhận đã xử lý/xác nhận đơn hàng/lịch hẹn, không hứa hẹn thời gian phản hồi cụ thể.",
            type = "string",
            pluginId = "global",
            label = "Prompt hệ thống cho AI chat (khi khách bị khoá điều khiển thiết bị)",
            description = "Toàn quyền tự viết persona, giọng điệu, quy tắc trả lời cho AI, bao gồm cả cảnh báo không tự nhận xử lý đơn hàng/hẹn lịch — hệ thống KHÔNG còn tự thêm quy tắc nào khác ngoài phần này. Xoá dòng cảnh báo ⚠️ nếu bạn chấp nhận rủi ro AI có thể tự nhận đã xử lý đơn hàng/lịch hẹn dù thực tế không làm được. Chỉ khi cần tra catalogue, hệ thống mới tự nối thêm chỉ thị kỹ thuật gọi tool (JSON schema cố định, không chỉnh được) vào cuối prompt này."
        ),
        AppConfigEntity(
            key = GLOBAL_TOOL_GUARD_RULES,
            value = "category: camera|tuya|call|facebook|telegram|website|chat|qa. Không rõ/chung/sản phẩm/giá/chính sách → qa.\n" +
                "target: tên/ID theo danh sách dưới. category=chat không rõ kênh → \"all\". Rõ kênh → đúng 1 tên kênh.\n" +
                "keyword: lấy từ khoá trong câu hỏi của người dùng. category=tuya hỏi chung chung bật/tắt/tình trạng → để trống, dùng state. category=call hỏi kết quả cuộc gọi → để trống, dùng call_status. category=camera hỏi về sự kiện , cảnh báo → \"cảnh báo\".\n" +
                "state: category=tuya, hỏi rõ bật/tắt → on|off. Không rõ → để trống.\n" +
                "call_status: category=call, hỏi kết quả cuộc gọi → missed|rejected|answered|failed. Không rõ → để trống.\n" +
                "timeframe: COPY NGUYÊN VĂN tiếng Việt cụm thời gian trong câu hỏi (không dịch/diễn giải sang ngôn ngữ khác, không tự đổi định dạng). Không có cụm thời gian rõ ràng → today|yesterday|last_3_days|last_7_days.\n" +
                "object: person|car|motorbike|dog|cat|package|all.\n" +
                "granularity: summary|detail.\n" +
                "Thiết bị không có trong danh sách → báo chưa lắp đặt, không gọi tool.\n" +
                "lưu ý: Chưa có data thật ở lượt này → không bịa nội dung/giờ/chi tiết...",
            type = "string",
            pluginId = "global",
            label = "Luật diễn giải ý định (Tool Guard Rules)",
            description = "Quy tắc hướng dẫn AI điền các field category/target/keyword/state/call_status/timeframe/object/granularity khi gọi db_search. Sửa câu chữ ở đây thoải mái. KHÔNG đụng vào tên field JSON ({\"tool\":\"db_search\",...}) — phần đó cố định trong code, sửa sai tên sẽ khiến hệ thống không đọc được kết quả AI trả về. Dòng \"📷 Camera:/🔌 Thiết bị:/...\" không nằm ở đây vì luôn tự lấy danh sách thật mới nhất từ dữ liệu app."
        ),
        AppConfigEntity(
            key = GLOBAL_PASS2_MEMORY_INSTRUCTION,
            value = "Dùng dữ liệu trên để trả lời. Không bịa đặt. Không trả JSON gọi tool nữa. Trả lời ngắn gọn, tự nhiên, đi thẳng vào trọng tâm câu hỏi.\n" +
                "🚨 BẮT BUỘC: Sau khi bạn trả lời cho người dùng xong, \n" +
                "Nếu có dữ liệu  camera, THÊM 1 DÒNG CUỐI CÙNG DUY NHẤT theo đúng định dạng: [REF_TIMESTAMPS: hh:mm:ss dd/MM/yyyy, hh:mm:ss dd/MM/yyyy, ...] — liệt kê CHÍNH XÁC để kiểm chứng dữ liệu cho câu trả lời của bạn.\n" +
                "[REF_TIMESTAMPS: ]. Dòng này LUÔN ở cuối cùng, không viết gì sau nó.",
            type = "string",
            pluginId = "global",
            label = "Chỉ thị SYSTEM_MEMORY ở Pass 2 (khi đã có dữ liệu thật)",
            description = "Câu chữ hướng dẫn AI sau khi đã tra được dữ liệu thật (camera/thiết bị/tin nhắn/cuộc gọi) ở lượt " +
                "gọi thứ 2 — vd đổi giọng điệu, độ dài câu trả lời mong muốn. CHỈ áp dụng Pass 2, không ảnh hưởng Pass 1 " +
                "(Luật diễn giải ý định ở trên). ⚠️ Muốn giữ tính năng tự động đính kèm ảnh camera đúng dòng dữ liệu đã " +
                "nhắc tới, PHẢI giữ nguyên literal \"[REF_TIMESTAMPS: ...]\" trong câu chữ — đây là marker cố định hệ " +
                "thống dò tìm bằng regex trong câu trả lời của AI, đổi tên sẽ làm mất khả năng đính kèm ảnh (không lỗi, " +
                "chỉ đơn giản là không có ảnh)."
        ),
        AppConfigEntity(
            key = GLOBAL_CONFIRM_YES_KEYWORDS,
            value = "co,dung,u,ok,dong y,chuan,phai,co nhe,co chu,dung roi",
            type = "string",
            pluginId = "global",
            label = "Từ khoá xác nhận \"Có\"",
            description = "Từ khoá (không dấu, cách nhau bằng dấu phẩy) được hiểu là ĐỒNG Ý khi hệ thống hỏi lại (vd: xác nhận khoá điều khiển 1 thiết bị riêng). Thêm từ khoá riêng vào cuối nếu bạn hay trả lời theo cách khác."
        ),
        AppConfigEntity(
            key = GLOBAL_CONFIRM_NO_KEYWORDS,
            value = "khong,khoi,thoi,huy,sai,khong dau,khong nhe,bo qua",
            type = "string",
            pluginId = "global",
            label = "Từ khoá xác nhận \"Không\"",
            description = "Từ khoá (không dấu, cách nhau bằng dấu phẩy) được hiểu là TỪ CHỐI khi hệ thống hỏi lại. Thêm từ khoá riêng vào cuối nếu bạn hay trả lời theo cách khác."
        ),
        AppConfigEntity(
            key = GLOBAL_EXIT_LOCK_PHRASES,
            value = "thoat dieu khien,ra khoi dieu khien,ket thuc dieu khien,thoat",
            type = "string",
            pluginId = "global",
            label = "Câu lệnh thoát chế độ điều khiển riêng",
            description = "Câu (không dấu, cách nhau bằng dấu phẩy) mà khi bạn gõ KHỚP TOÀN BỘ câu sẽ thoát chế độ khoá điều khiển riêng cho 1 thiết bị/plugin. Thêm câu riêng vào cuối nếu muốn dùng cách nói khác."
        ),
        AppConfigEntity(
            key = GLOBAL_CLAUSE_FILLER_WORDS,
            value = "giúp tôi,giúp mình,hộ tôi,hộ mình,dùm tôi,dùm mình,luôn,dùm,nha,nhé,nhá,thôi,nè,ạ",
            type = "string",
            pluginId = "global",
            label = "Từ đệm bị lọc bỏ khi phân tích câu lệnh",
            description = "Từ đệm/lịch sự (giữ nguyên dấu, cách nhau bằng dấu phẩy) sẽ bị loại khỏi câu trước khi tách mệnh đề và nhận diện lệnh điều khiển thiết bị. Thêm từ đệm bạn hay dùng vào cuối danh sách nếu hệ thống chưa nhận ra."
        ),
        AppConfigEntity(
            key = GLOBAL_MEMORY_QUERY_KEYWORDS,
            value = "mấy hôm trước,hôm qua,hôm nay,gần đây,vừa rồi,lúc nãy,lịch sử,nhật ký,hoạt động,đã làm gì,đã xảy ra,có ai gọi,có ai nhắn,tin nhắn gì,cuộc gọi,bỏ lỡ,chưa đọc,mấy ngày nay,tuần trước,tuần này,trước đó,sự kiện,biến cố,cảnh báo,báo cáo,camera,thiết bị",
            type = "string",
            pluginId = "global",
            label = "Từ khoá kích hoạt tra cứu lịch sử (Memory Recall)",
            description = "Danh sách từ khoá, cách nhau bằng dấu phẩy (,). Khi tin nhắn bạn gõ chứa 1 trong các từ này, hệ thống sẽ tự tra lại nhật ký (chat, cuộc gọi, camera, thiết bị) để trả lời chính xác thay vì để AI tự đoán/bịa. Thêm từ khoá riêng của bạn vào cuối danh sách (vd: 'nãy giờ', 'khách nào') nếu muốn hỏi theo cách khác mà hệ thống chưa nhận ra — không cần sửa code."
        ),
        AppConfigEntity(
            key = GLOBAL_CALL_KEYWORDS,
            value = "cuộc gọi,gọi nhỡ,gọi đến,gọi đi,ai gọi,ai gọi nhỡ,bỏ lỡ cuộc gọi,goi nho,cuoc goi",
            type = "string",
            pluginId = "global",
            label = "Từ khoá nhận diện câu hỏi về Cuộc gọi",
            description = "Danh sách từ khoá, cách nhau bằng dấu phẩy (,). Khi tin nhắn chứa 1 trong các từ này, hệ thống coi đây là câu hỏi thuộc miền 'cuộc gọi' — sẽ tra đúng nhật ký cuộc gọi (nguồn 'call') thay vì lẫn vào camera/thiết bị, và cho phép AI gọi tool tra cứu. Thêm từ khoá riêng vào cuối danh sách nếu muốn hỏi theo cách khác mà hệ thống chưa nhận ra — không cần sửa code."
        ),
        // ✅ MỚI: cùng khuôn với GLOBAL_CALL_KEYWORDS ở trên — trước đây 3 danh sách này bị
        // hardcode cứng trong AgentKernel.mentionsAppDomain() (safeNormalizedKeywords/
        // diacriticSensitiveKeywords), user không tự thêm từ khoá được. Giờ seed đúng giá trị cũ
        // đang hoạt động, để hành vi không đổi ngay sau khi migrate.
        AppConfigEntity(
            key = GLOBAL_CAMERA_KEYWORDS,
            value = "camera,cam,canh bao,phat hien,nguoi la,xam nhap",
            type = "string",
            pluginId = "global",
            label = "Từ khoá nhận diện câu hỏi về Camera",
            description = "Danh sách từ khoá (không dấu, cách nhau bằng dấu phẩy). Khi tin nhắn chứa 1 trong các từ này, hệ thống coi đây là câu hỏi thuộc miền camera và cho phép AI gọi tool tra cứu nhật ký. Thêm từ khoá riêng vào cuối danh sách nếu muốn hỏi theo cách khác mà hệ thống chưa nhận ra — không cần sửa code."
        ),
        AppConfigEntity(
            key = GLOBAL_DEVICE_KEYWORDS,
            value = "dieu hoa,thiet bi,đèn,cửa,quạt,bật,tắt,quay",
            type = "string",
            pluginId = "global",
            label = "Từ khoá nhận diện câu hỏi về Thiết bị",
            description = "Danh sách từ khoá, cách nhau bằng dấu phẩy. LƯU Ý: khác các danh sách khác, danh sách này được so khớp trên văn bản GỐC CÒN DẤU (không qua chuẩn hoá bỏ dấu), để tránh nhầm lẫn giữa các từ gần giống nhau (vd 'đèn'/'đến'). Khi tin nhắn chứa 1 trong các từ này, hệ thống coi đây là câu hỏi thuộc miền thiết bị Tuya và cho phép AI gọi tool tra cứu."
        ),
        AppConfigEntity(
            key = GLOBAL_CHAT_KEYWORDS,
            value = "tin nhan,nhan tin,facebook,telegram,website,chat",
            type = "string",
            pluginId = "global",
            label = "Từ khoá nhận diện câu hỏi về Kênh chat",
            description = "Danh sách từ khoá (không dấu, cách nhau bằng dấu phẩy). Khi tin nhắn chứa 1 trong các từ này, hệ thống coi đây là câu hỏi thuộc miền chat đa kênh (facebook/telegram/website) và cho phép AI gọi tool tra cứu. Thêm từ khoá riêng vào cuối danh sách nếu muốn hỏi theo cách khác mà hệ thống chưa nhận ra — không cần sửa code."
        )
    )
}