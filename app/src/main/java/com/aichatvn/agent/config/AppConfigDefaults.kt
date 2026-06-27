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

    // ───────────────────────── EMAIL ────────────────────────
    const val EMAIL_SUBJECT_PREFIX = "email.subject_prefix"
    const val EMAIL_MAX_ATTACHMENTS = "email.max_attachments"

    // ───────────────────────── SCHEDULE ─────────────────────
    const val SCHEDULE_CAMERA_SCAN_INTERVAL_MIN = "schedule.camera_scan_interval_min"

    // ───────────────────────── GLOBAL ───────────────────────
    const val GLOBAL_FUZZY_THRESHOLD        = "global.fuzzy_threshold"
    const val GLOBAL_TIER2_MIN_SCORE        = "global.tier2_min_score"
    const val GLOBAL_TIER2_HIGH_CONFIDENCE  = "global.tier2_high_confidence"
    const val GLOBAL_TIER2_5_MIN_SCORE      = "global.tier2_5_min_score"

    // ─────────────────────────────────────────────────────────
    //  Danh sách đầy đủ để seed vào DB
    // ─────────────────────────────────────────────────────────
    fun all(): List<AppConfigEntity> = listOf(

        // ── GROQ ──
        AppConfigEntity(
            key = GROQ_MODEL_TEXT,
            value = "openai/gpt-oss-120b",
            type = "string",
            pluginId = "groq",
            label = "Model chat chính",
            description = "Model Groq dùng cho hội thoại văn bản (chat, QA). Thay đổi nếu model bị deprecated."
        ),
        AppConfigEntity(
            key = GROQ_MODEL_VISION,
            value = "meta-llama/llama-4-scout-17b-16e-instruct",
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
            value = "${3 * 60 * 60 * 1000L}",   // 3 giờ
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
            value = "${30 * 60 * 1000L}",  // 30 phút
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
            type = "string",
            pluginId = "global",
            label = "Ngưỡng lọc lệnh cục bộ (Fuzzy Threshold)",
            description = "Giá trị từ 0.0 đến 1.0. Thấp sẽ nhạy hơn nhưng dễ nhầm lẫn, cao sẽ khắt khe hơn."
        ),
        AppConfigEntity(
            key = GLOBAL_TIER2_MIN_SCORE,
            value = "0.3",
            type = "string",
            pluginId = "global",
            label = "Ngưỡng điểm Tier 2 QA (Tier 2 Min Score)",
            description = "Giá trị từ 0.0 đến 1.0. Ngưỡng điểm số tối thiểu để kích hoạt Fuzzy QA Intent ở tầng 2 mà không cần LLM."
        ),
        AppConfigEntity(
            key = GLOBAL_TIER2_HIGH_CONFIDENCE,
            value = "0.80",
            type = "string",
            pluginId = "global",
            label = "Ngưỡng tin cậy cao Tier 2 (High Confidence)",
            description = "Giá trị từ 0.0 đến 1.0. Nếu Tier 2 đạt ngưỡng này thì bỏ qua Tier 2.5, không cần LLM. Mặc định 0.80."
        ),
        AppConfigEntity(
            key = GLOBAL_TIER2_5_MIN_SCORE,
            value = "0.80",
            type = "string",
            pluginId = "global",
            label = "Ngưỡng điểm Tier 2.5 Metadata (Tier 2.5 Min Score)",
            description = "Giá trị từ 0.0 đến 1.0. Ngưỡng điểm tối thiểu để Tier 2.5 (metadata matcher) nhận diện action mà không cần LLM. Mặc định 0.80."
        )
    )
}