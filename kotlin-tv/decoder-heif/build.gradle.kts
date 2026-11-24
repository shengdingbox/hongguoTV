plugins { id("com.android.library") }

android {
    namespace = "com.huluwa.heif"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        minSdk = 21
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
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
    inputs.file("sources.json")
    inputs.file("tools/build_native.py")
    inputs.dir("src/main/cpp")
    outputs.dir(layout.buildDirectory.dir("native-libs"))
    commandLine("python3", "tools/build_native.py", "--ndk",
        android.sdkDirectory.resolve("ndk/27.1.12297006").absolutePath,
        "--cmake", android.sdkDirectory.resolve("cmake/3.22.1/bin/cmake").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildNativeHeif) }
