import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); kotlin("kapt") }
val signingProperties = Properties().apply { val config = rootProject.file("keystore.properties"); if (config.exists()) config.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? = System.getenv(env) ?: signingProperties.getProperty(key)

// 默认只启用标准版，原有打包入口无需 NDK；显式开启后可以一次生成双包。
val buildAndroid5 = providers.gradleProperty("HONGGUOTV_BUILD_ANDROID5")
    .map { it.toBooleanStrict() }.getOrElse(false)
val standardMinSdk = providers.gradleProperty("HONGGUOTV_MIN_SDK")
    .map { it.toInt() }.getOrElse(24)
require(standardMinSdk >= 24) { "标准版最低支持 API 24；Android 5 请启用 HONGGUOTV_BUILD_ANDROID5" }

android {
    namespace = "com.huluwa.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.huluwa.nativeapp"
        minSdk = standardMinSdk
        targetSdk = 36
        // 界面为中文；保留中英文库资源，其余语言沿用默认文案。
        resourceConfigurations += listOf("zh", "en")
        versionCode = System.getenv("HONGGUOTV_VERSION_CODE")?.toInt() ?: 12
        versionName = System.getenv("HONGGUOTV_VERSION_NAME") ?: "0.30.10"
    }
    flavorDimensions += "compatibility"
    productFlavors {
        create("standard") {
            dimension = "compatibility"
            minSdk = standardMinSdk
            buildConfigField("boolean", "ANDROID5_COMPAT", "false")
            isDefault = true
        }
        create("android5") {
            dimension = "compatibility"
            minSdk = 21
            buildConfigField("boolean", "ANDROID5_COMPAT", "true")
            // 电视和盒子使用双 ARM 架构；标准版仍保持无原生库、全架构。
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
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
    // 两种安装包都通过静态脱糖兼容 Java 8+ 库调用。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
androidComponents {
    beforeVariants(selector().withFlavor("compatibility" to "android5")) {
        it.enable = buildAndroid5
    }
    onVariants(selector().withFlavor("compatibility" to "android5")) { variant ->
        // Android 5 不支持 APK v2 签名，兼容版始终保留 v1 签名。
        variant.signingConfig?.enableV1Signing?.set(true)
    }
    onVariants(selector().withFlavor("compatibility" to "standard")) { variant ->
        val capitalizedName = variant.name.replaceFirstChar { it.uppercaseChar() }
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val legacyDirectory = layout.buildDirectory.dir("outputs/apk/${variant.buildType}")
        // 兼容已有打包机读取的 app-release.apk 路径及对应元数据。
        val apkName = "app-${variant.buildType}.apk"
        val exportApk = tasks.register<Copy>("export${capitalizedName}Apk") {
            from(apkDirectory) {
                include("*.apk")
                rename { apkName }
            }
            into(legacyDirectory)
            inputs.file(apkDirectory.map { it.file("output-metadata.json") })
            doLast {
                val source = apkDirectory.get().asFile
                val output = legacyDirectory.get().asFile
                @Suppress("UNCHECKED_CAST")
                val metadata = JsonSlurper().parse(source.resolve("output-metadata.json")) as MutableMap<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val elements = metadata["elements"] as List<MutableMap<String, Any?>>
                elements.single()["outputFile"] = apkName
                output.resolve("output-metadata.json").writeText(JsonOutput.toJson(metadata))
            }
        }
        tasks.matching { it.name == "assemble$capitalizedName" }.configureEach {
            finalizedBy(exportApk)
        }
    }
}

// 保留已有打包机和维护命令使用的入口；开启双包时同时检查两个变体。
listOf("Debug", "Release").forEach { buildType ->
    tasks.register("lint$buildType") {
        group = "verification"
        description = "检查当前启用的安装包变体"
        dependsOn("lintStandard$buildType")
        if (buildAndroid5) dependsOn("lintAndroid5$buildType")
    }
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
    "android5Implementation"(project(":decoder-ffmpeg"))
    "android5Implementation"(project(":decoder-heif"))
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}
