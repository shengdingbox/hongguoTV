import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

android {
    namespace = "com.huluwa.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.huluwa.nativeapp"
        // Android 7 专用分支：最低 API 24，保证可直接安装到 Android 7.0 设备；
        // 仍保留属性覆盖，便于发布流程按需生成其他最低版本。
        minSdk = findProperty("HONGGUOTV_MIN_SDK")?.toString()?.toInt() ?: 24
        targetSdk = 36
        // 界面为中文；保留中英文库资源，其余语言沿用默认文案。
        resourceConfigurations += listOf("zh", "en")
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 12
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.30.10"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("delivery") {
            storeFile = signingValue("HONGGUOTV_KEYSTORE", "storeFile")?.let { file(it) }
            storePassword = signingValue("HONGGUOTV_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("HONGGUOTV_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("HONGGUOTV_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        getByName("debug") {
            // Android 7 没有运行时脱糖；Debug 也走 D8/B8 静态脱糖，便于真机排查。
            isMinifyEnabled = false
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("delivery")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // API 24 需要把 Java 8+ 库调用静态改写为 Android 7 可用的等价实现。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    implementation("com.github.bumptech.glide:recyclerview-integration:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
