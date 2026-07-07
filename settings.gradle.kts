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
        // ✅ THÊM: Repository chính thức chứa các gói thư viện/AAR của Tuya / Thing Smart App SDK
        maven { url = uri("https://maven-other.tuya.com/repository/maven-releases/") }
    }
}
rootProject.name = "AIChatVN2"
include(":app")