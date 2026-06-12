# Add project specific ProGuard rules here.
-keep class com.aichatvn.agent.data.model.** { *; }
-keep class com.aichatvn.agent.core.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Google API Client
-dontwarn com.google.api.**
-keep class com.google.api.** { *; }
