import org.gradle.api.tasks.PathSensitivity

plugins { id("com.android.library") }

val nativeNdkVersion = "27.1.12297006"
val nativeAbis = listOf("armeabi-v7a", "arm64-v8a")
val nativeCmakeVersion = "3.22.1"

android {
    namespace = "com.huluwa.heif"
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

// 只有兼容版依赖本模块；固定源码、工具链和双 ARM 架构保证可以重新构建。
val buildNativeHeif by tasks.registering(Exec::class) {
    val ndkDirectory = android.sdkDirectory.resolve("ndk/$nativeNdkVersion")
    val cmakeDirectory = android.sdkDirectory.resolve("cmake/$nativeCmakeVersion")

    // 使用相对源码路径，避免每次打包的临时工作目录改变缓存键。
    inputs.file("sources.json").withPropertyName("sourceManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("tools/build_native.py").withPropertyName("buildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/cpp").withPropertyName("jniSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // 按 NDK 安装元数据和 CMake 程序识别工具链，排除绝对安装路径。
    inputs.file(ndkDirectory.resolve("source.properties")).withPropertyName("ndkPackage")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(cmakeDirectory.resolve("bin/cmake")).withPropertyName("cmakeTool")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.property("ndkVersion", nativeNdkVersion)
    inputs.property("cmakeVersion", nativeCmakeVersion)
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
    commandLine("python3", "tools/build_native.py", "--ndk", ndkDirectory.absolutePath,
        "--cmake", cmakeDirectory.resolve("bin/cmake").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildNativeHeif) }
