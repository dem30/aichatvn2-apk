plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ✅ FIX BUILD: một dependency transitive nào đó đang kéo androidx.core:core(-ktx)
// lên 1.17.0, bản này yêu cầu compileSdk 36 + AGP 8.9.1+ trong khi project đang dùng
// compileSdk 34 + AGP 8.2.0 -> lỗi "checkDebugAarMetadata". Ép cứng về bản tương thích
// (1.13.1 - bản cuối còn hỗ trợ compileSdk 34) cho MỌI configuration để tránh việc
// Gradle tự chọn bản cao nhất trong dependency graph.
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
    }
}

android {
    namespace = "com.aichatvn.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aichatvn.agent"
        minSdk = 26
        targetSdk = 34
        // ⚠️ LƯU Ý: tăng versionCode mỗi khi phát hành bản mới cho khách đã cài (vd 1 → 2 → 3).
        // Cùng chữ ký (đã cố định ở signingConfigs.debug bên dưới) thì Android vẫn cho phép cài
        // đè dù versionCode không đổi, nhưng tăng đều để dễ theo dõi bản nào khách đang chạy.
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ❌ ĐÃ GỠ: GROQ_API_KEY / RESEND_API_KEY / RESEND_SENDER buildConfigField — các key này
        // giờ nhập tay qua màn Settings (SettingsViewModel.saveGroqApiKey/...) và lưu trong DB
        // qua AppConfigProvider, không còn cần inject lúc build/CI nữa.
        // ❌ ĐÃ GỠ: manifestPlaceholders["MAPS_API_KEY"] — app chưa từng có key Maps thật, chỉ
        // dùng play-services-location để lấy vị trí điện thoại, không hiển thị bản đồ.
    }

    // ✅ MỚI (FIX): trước đây KHÔNG khai báo signingConfigs.debug — Gradle tự dùng
    // debug.keystore mặc định. Trên GitHub Actions, mỗi lần build là 1 máy ảo MỚI hoàn toàn,
    // không có sẵn ~/.android/debug.keystore từ lần trước → Gradle tự SINH RA 1 keystore debug
    // MỚI, NGẪU NHIÊN mỗi lần chạy. Android từ chối cài đè APK có chữ ký khác bản đã cài trên
    // máy → bắt buộc gỡ cài mới cài lại được. Khai báo keystore CỐ ĐỊNH (lấy từ file bí mật
    // debug.keystore đã checked-in dạng base64 trong CI, xem README/workflow) để mọi lần build
    // dùng chung 1 chữ ký → cài đè cập nhật bình thường, không cần gỡ cài nữa.
    signingConfigs {
        getByName("debug") {
            storeFile = file(project.findProperty("DEBUG_KEYSTORE_PATH") as? String ?: "debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.md",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:125.6422.07")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    ksp("androidx.room:room-compiler:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.58")
    kapt("com.google.dagger:hilt-compiler:2.58")

    // Retrofit + OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // Image processing
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Location (lấy vị trí điện thoại — không dùng Google Maps SDK nên không cần key)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

    // ✅ MỚI (MQTT_SYMMETRIC_BROKER_PLAN.md mục 4 Implementation Order — "Tích hợp Moquette,
    // TÁI DÙNG nguyên Khối A"): EmbeddedMqttBrokerService.kt load lớp này qua reflection nên
    // KHÔNG chặn build nếu thiếu — nhưng thiếu thì broker không bao giờ thực sự start (chỉ log
    // "❌ Moquette chưa có trong classpath"), toàn bộ vai trò HOSTING_EMBEDDED trở thành vô
    // nghĩa dù Election vẫn chạy đúng. Thêm để tính năng thực sự hoạt động, không chỉ scaffold.
    implementation("io.moquette:moquette-broker:0.17")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Hilt + Compose Navigation integration (cho hiltViewModel())
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt WorkManager integration
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // ===== Ktor Server Webhook Facebook =====
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-netty:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")
      implementation("com.jcraft:jsch:0.1.55")

    // ⚠️ FIX: CameraLiveViewScreen.kt dùng ExoPlayer, MediaItem, Player trực tiếp nhưng project
    // chỉ khai báo media3-exoplayer-rtsp (chỉ có RtspMediaSource) + media3-ui -> Gradle không tự
    // lộ API transitive ra compile classpath -> "Unresolved reference 'ExoPlayer'/'MediaItem'".
    // Thêm 2 module lõi chứa các class đó, khai báo tường minh.
    implementation("androidx.media3:media3-exoplayer:1.4.1")   // chứa ExoPlayer, Player
    implementation("androidx.media3:media3-common:1.4.1")      // chứa MediaItem
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
// ⚠️ FIX: com.arthenica:ffmpeg-kit-min đã bị RETIRE, gỡ khỏi mọi Maven repo từ 4/2025
// (repo gốc archive từ 6/2025) -> "Could not find ...ffmpeg-kit-min:6.0-2". Dùng bản rebuild
// cộng đồng (maitrungduc1410/ffmpeg-kit, được arthenica liệt kê chính thức là 1 fork thay thế),
// publish trên Maven Central qua CI riêng, giữ nguyên package com.arthenica.ffmpegkit.*.
// LƯU Ý: đây là dự án cá nhân nhỏ (không phải hãng lớn) — artifact đã publish thì tồn tại vĩnh
// viễn trên Maven Central (không bị xóa), nhưng nếu tác giả ngừng bảo trì sẽ không có bản vá mới.
// Ghim cứng version 8.1.2 (bản mới nhất, dựa trên FFmpeg 8.1) thay vì dùng version range.
implementation("io.github.maitrungduc1410:ffmpeg-kit-min:8.1.2")   // bản "min" — chỉ remux (-c copy), không cần codec nặng, đỡ tăng size APK
}
dependencies {
    // ✅ SỬA: đổi sang bản "unbundled" — model KHÔNG đóng gói trong APK, tải qua Google Play
    // Services lúc runtime (chỉ 1 lần, vài giây, cần internet lần đầu). Giảm đáng kể dung lượng
    // APK vì bản bundled cũ (đặc biệt face-detection) chứa native .so cho nhiều ABI, có thể
    // chiếm 40-60MB nếu build không tách theo ABI. Độ chính xác/API KHÔNG đổi — cùng model,
    // chỉ khác nơi lưu trữ. Yêu cầu thiết bị có Google Play Services (hầu hết máy Android đều có).
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
}

kapt {
    correctErrorTypes = true
}