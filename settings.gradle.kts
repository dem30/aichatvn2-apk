pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Toàn bộ các kho lưu trữ Maven của Tuya/Thingclips đã được gỡ bỏ sạch sẽ tại đây
    }
}
rootProject.name = "AIChatVN2"
include(":app")