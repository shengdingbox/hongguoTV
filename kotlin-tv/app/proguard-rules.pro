-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# 静态封面使用 ArtworkGlideModule 中显式注册的网络、位图和磁盘缓存组件。
# 配套移除默认注册调用，使 GIF、视频缩略图等组件失去引用并可被 R8 裁剪。
# 此签名对应固定依赖 Glide 4.16.0；升级 Glide 时必须同步调整注册实现及本规则。
-assumenosideeffects class com.bumptech.glide.RegistryFactory {
    private static void initializeDefaults(android.content.Context, com.bumptech.glide.Registry, com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool, com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool, com.bumptech.glide.GlideExperiments);
}
