pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内打包节点优先从镜像解析 AndroidX，避免 Google Maven TLS 连接失败。
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupByRegex("androidx\\..*") }
        }
        // ffmpeg-kit 已被作者从 Maven Central 下架，阿里云 central 镜像仍有缓存。
        maven("https://maven.aliyun.com/repository/central") {
            content { includeGroup("com.arthenica") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "HongguoTVNative"
include(":core", ":app")
