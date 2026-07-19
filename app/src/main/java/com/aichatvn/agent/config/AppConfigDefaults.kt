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
    
    // ✅ MỚI (Tuần 2 & 3 - Phase 3): Ngưỡng thời gian gộp/nén các sự kiện liên tiếp
    const val CAMERA_ALERT_MERGE_WINDOW_MS    = "camera.alert_merge_window_ms"

    // ───────────────────────── EMAIL ────────────────────────
    const val EMAIL_SUBJECT_PREFIX = "email.subject_prefix"
    const val EMAIL_MAX_ATTACHMENTS = "email.max_attachments"

    // ───────────────────────── SCHEDULE ─────────────────────
    const val SCHEDULE_CAMERA_SCAN_INTERVAL_MIN = "schedule.camera_scan_interval_min"

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

    // ───────────────────────── CLOUD GATEWAY ────────────────
    const val GLOBAL_GATEWAY_URL            = "global.gateway_url"
    const val GLOBAL_GATEWAY_TOKEN          = "global.gateway_token"

    // ───────────────────────── ĐA KÊNH (OMNICHANNEL) ────────
    const val FACEBOOK_PAGE_ACCESS_TOKEN    = "facebook.page_access_token"
    const val TELEGRAM_BOT_TOKEN            = "telegram.bot_token"
    const val WEBSITE_ALLOWED_ORIGINS       = "website.allowed_origins"
    const val WEBSITE_WIDGET_KEY            = "website.widget_key"

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
            value = "200",
            type = "int",
            pluginId = "groq",
            label = "Max tokens – vision",
            description = "Số token tối đa model vision trả về khi phân tích ảnh camera."
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
        AppConfigEntity(
            key = SCHEDULE_CAMERA_SCAN_INTERVAL_MIN,
            value = "15",
            type = "int",
            pluginId = "schedule",
            label = "Chu kỳ quét camera (phút)",
            description = "Khoảng thời gian giữa 2 lần TaskScheduler tự động quét toàn bộ camera. Mặc định 15 phút."
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
            value = "false",
            type = "boolean",
            pluginId = "global",
            label = "Chặn điều khiển thiết bị từ kênh ngoài",
            description = "Khi BẬT: khách chat từ Facebook/Telegram/Website chỉ được trả lời QA/AI thông thường, KHÔNG được kích hoạt bất kỳ lệnh điều khiển thiết bị nào (kể cả khóa điều khiển riêng). Không ảnh hưởng đến chat nội bộ (default_user)."
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
        )
    )
}