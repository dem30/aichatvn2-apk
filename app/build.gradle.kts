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
    
    // ✅ THÊM: Loại bỏ module annotation xử lý riêng này để tránh xảy ra lỗi xung đột biên dịch.
    exclude(group = "com.thingclips.smart", module = "thingsmart-modularCampAnno")
}

android {
    namespace = "com.aichatvn.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aichatvn.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GROQ_API_KEY", "\"${project.findProperty("GROQ_API_KEY") ?: ""}\"")
        buildConfigField("String", "RESEND_API_KEY", "\"${System.getenv("RESEND_API_KEY") ?: ""}\"")
        buildConfigField("String", "RESEND_SENDER", "\"${System.getenv("RESEND_SENDER") ?: ""}\"")

        manifestPlaceholders["MAPS_API_KEY"] = project.findProperty("MAPS_API_KEY") ?: ""
        
        // ✅ THÊM: Inject khóa kích hoạt SDK của ứng dụng từ Gradle/Local Properties vào Manifest Placeholders.
        manifestPlaceholders["THING_SMART_APPKEY"] = project.findProperty("THING_SMART_APPKEY") ?: ""
        manifestPlaceholders["THING_SMART_SECRET"] = project.findProperty("THING_SMART_SECRET") ?: ""

        // ✅ Đã bỏ giới hạn abiFilters arm64-v8a — không còn native lib (llama.cpp) nào nữa
        // sau khi chuyển routing sang Groq (xem AgentKernel/GroqClientTool). App giờ build
        // và chạy được trên mọi ABI, kể cả emulator x86_64.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        
        // ✅ THÊM: Tránh lỗi trùng lặp thư viện gốc .so khi tải nhiều AAR con của Tuya
        jniLibs {
            pickFirsts += setOf(
                "lib/*/libc++_shared.so",
                "lib/*/libv8wrapper.so",
                "lib/*/libv8android.so"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
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

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

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

    // ===== ✅ BỔ SUNG: Phiên bản SDK ổn định và khả dụng phổ thông mới nhất =====
    implementation("com.thingclips.smart:thingsmart:7.5.6")
}

kapt {
    correctErrorTypes = true
}