# JNI 通过固定名称查找解码入口；原生代码只访问 Android 系统 Bitmap API。
-keep,includedescriptorclasses class com.huluwa.heif.HeifDecoder {
    native <methods>;
}
