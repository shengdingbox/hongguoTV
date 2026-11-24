import org.gradle.api.tasks.PathSensitivity

plugins { id("com.android.library") }

val nativeNdkVersion = "27.1.12297006"
val nativeAbis = listOf("armeabi-v7a", "arm64-v8a")

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = nativeNdkVersion
    defaultConfig {
        minSdk = 21
        ndk { abiFilters += nativeAbis }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.srcDir("build/native-libs")
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("androidx.media3:media3-decoder:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    compileOnly("org.checkerframework:checker-qual:3.49.3")
}

// 只有兼容版依赖本模块；从固定源码构建，避免带入完整 FFmpeg 的体积。
val buildNativeAudio by tasks.registering(Exec::class) {
    val ndkDirectory = android.sdkDirectory.resolve("ndk/$nativeNdkVersion")

    // 使用相对源码路径，避免每次打包的临时工作目录改变缓存键。
    inputs.file("tools/build_native.py").withPropertyName("buildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/jni").withPropertyName("jniSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // 工具链按安装元数据识别，SDK 的绝对安装路径不参与缓存键。
    inputs.file(ndkDirectory.resolve("source.properties")).withPropertyName("ndkPackage")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.property("ndkVersion", nativeNdkVersion)
    inputs.property("androidApi", 21)
    inputs.property("abis", nativeAbis)
    inputs.property("hostOs", System.getProperty("os.name"))
    inputs.property("hostArch", System.getProperty("os.arch"))
    listOf("CFLAGS", "CPPFLAGS", "CXXFLAGS", "LDFLAGS", "SOURCE_DATE_EPOCH").forEach { name ->
        inputs.property("environment.$name", providers.environmentVariable(name).orElse(""))
    }

    // 普通 Exec 默认不缓存；只缓存可跨工作目录恢复的成品库和指纹。
    outputs.dir(layout.buildDirectory.dir("native-libs")).withPropertyName("nativeLibraries")
    outputs.cacheIf("固定源码和工具链构建的原生库可以复用") { true }
    commandLine("python3", "tools/build_native.py", "--ndk", ndkDirectory.absolutePath)
}
tasks.named("preBuild") { dependsOn(buildNativeAudio) }
