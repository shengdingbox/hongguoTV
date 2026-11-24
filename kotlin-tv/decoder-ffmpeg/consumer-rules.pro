# Media3 通过类名加载音频渲染器，JNI 依赖原始类名和方法名。
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { public <init>(...); }
-keepclasseswithmembernames,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** { native <methods>; }

# 原生解码器扩容输出缓冲时回调此方法，连同参数类型一起保留。
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
