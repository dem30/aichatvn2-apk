# AIChatVN2 — Android Native

Ứng dụng giám sát camera AI độc lập trên Android, chuyển đổi từ FastAPI/NiceGUI sang Kotlin native.

## Cấu trúc kiến trúc

```
Layer 1: Android UI (Jetpack Compose)
Layer 2: Agent Core (AgentRouter, CommandDispatcher, EventBus)
Layer 3: Skills & Tools (ChatSkill, CameraSkill, TrainingSkill...)
Data Layer: Room Database
Workers: WorkManager (background scan)
```

## Cài đặt & Build

### 1. Yêu cầu
- Android Studio Hedgehog hoặc mới hơn
- Android SDK 34
- JDK 17

### 2. Cấu hình API Keys
Sao chép `gradle.properties` và điền vào:
```
GROQ_API_KEY=sk-...
GMAIL_CLIENT_ID=...
GMAIL_CLIENT_SECRET=...
GMAIL_REFRESH_TOKEN=...
GMAIL_SENDER=your@gmail.com
MAPS_API_KEY=AIza...
```

### 3. Build
```bash
./gradlew assembleDebug
```

### 4. Deploy lên IDX (Google Project IDX)
1. Mở IDX → New Project → Android
2. Upload zip này hoặc clone repo
3. Chạy `./gradlew assembleDebug` trong terminal IDX

## Tính năng chính
- Chat AI với Groq API (llama-3.3-70b + llama-4 vision)
- Giám sát IP Camera với pHash learning algorithm
- Gửi email cảnh báo qua Gmail API
- Quản lý Q&A huấn luyện
- WorkManager background scan (15 phút / hàng ngày)
- Room Database offline-first

## Lưu ý
- `ImageHashTool`: dùng thuần Kotlin DCT pHash (không cần thư viện ngoài)
- Bỏ `com.github.kilianB:ImageHash` — đã thay thế hoàn toàn
