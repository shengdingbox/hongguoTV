// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <android/bitmap.h>
#include <libheif/heif.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace {
constexpr uint64_t kMaxPixels = 8 * 1024 * 1024;
constexpr uint64_t kMaxMemory = 96 * 1024 * 1024;
constexpr int kMaxOutputSide = 2048;
std::once_flag initialized;

void check(heif_error error) {
    if (error.code != heif_error_Ok) {
        throw std::runtime_error(error.message ? error.message : "HEIC 图片解码失败");
    }
}

// 保持输入数据存活到原生上下文释放，避免无复制读取后访问悬空内存。
class InputBytes {
public:
    InputBytes(JNIEnv* env, jbyteArray array) : env_(env), array_(array), data_(env->GetByteArrayElements(array, nullptr)) {
        if (!data_) throw std::bad_alloc();
    }
    ~InputBytes() { env_->ReleaseByteArrayElements(array_, data_, JNI_ABORT); }
    const void* data() const { return data_; }
private:
    JNIEnv* env_;
    jbyteArray array_;
    jbyte* data_;
};

jobject decode(JNIEnv* env, jbyteArray bytes, int targetWidth, int targetHeight) {
    std::call_once(initialized, [] { check(heif_init(nullptr)); });
    InputBytes input(env, bytes);
    std::unique_ptr<heif_context, decltype(&heif_context_free)> context(heif_context_alloc(), heif_context_free);
    if (!context) throw std::bad_alloc();
    auto* limits = heif_context_get_security_limits(context.get());
    limits->max_image_size_pixels = kMaxPixels;
    limits->max_memory_block_size = 48 * 1024 * 1024;
    limits->max_total_memory = kMaxMemory;
    limits->max_number_of_tiles = 256;
    limits->max_items = 512;
    limits->max_color_profile_size = 1024 * 1024;
    heif_context_set_max_decoding_threads(context.get(), 1);
    check(heif_context_read_from_memory_without_copy(context.get(), input.data(), env->GetArrayLength(bytes), nullptr));

    heif_image_handle* rawHandle = nullptr;
    check(heif_context_get_primary_image_handle(context.get(), &rawHandle));
    std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)> handle(rawHandle, heif_image_handle_release);
    int width = heif_image_handle_get_width(handle.get());
    int height = heif_image_handle_get_height(handle.get());
    if (width <= 0 || height <= 0 || static_cast<uint64_t>(width) * height > kMaxPixels) {
        throw std::runtime_error("HEIC 图片尺寸超过兼容模式限制");
    }

    std::unique_ptr<heif_decoding_options, decltype(&heif_decoding_options_free)> options(
        heif_decoding_options_alloc(), heif_decoding_options_free);
    if (!options) throw std::bad_alloc();
    options->convert_hdr_to_8bit = 1;
    options->decoder_id = "libde265";
    heif_image* rawImage = nullptr;
    check(heif_decode_image(handle.get(), &rawImage, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, options.get()));
    std::unique_ptr<heif_image, decltype(&heif_image_release)> image(rawImage, heif_image_release);

    // 使用解码后的尺寸，旋转、裁剪等 HEIF 元数据可能已经改变宽高。
    width = heif_image_get_width(image.get(), heif_channel_interleaved);
    height = heif_image_get_height(image.get(), heif_channel_interleaved);
    if (width <= 0 || height <= 0) throw std::runtime_error("HEIC 图片解码尺寸无效");
    int requestedWidth = targetWidth > 0 ? std::min(targetWidth, kMaxOutputSide) : kMaxOutputSide;
    int requestedHeight = targetHeight > 0 ? std::min(targetHeight, kMaxOutputSide) : kMaxOutputSide;
    double scale = std::min({1.0, static_cast<double>(requestedWidth) / width,
                           static_cast<double>(requestedHeight) / height});
    int outputWidth = std::max(1, static_cast<int>(std::floor(width * scale)));
    int outputHeight = std::max(1, static_cast<int>(std::floor(height * scale)));
    if (outputWidth != width || outputHeight != height) {
        heif_image* scaled = nullptr;
        check(heif_image_scale_image(image.get(), &scaled, outputWidth, outputHeight, nullptr));
        image.reset(scaled);
    }
    int stride = 0;
    const uint8_t* pixels = heif_image_get_plane_readonly(image.get(), heif_channel_interleaved, &stride);
    if (!pixels || stride < outputWidth * 4) throw std::runtime_error("HEIC 图片像素缓冲无效");

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapClass || !configClass) return nullptr;
    jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!configField || !create) return nullptr;
    jobject config = env->GetStaticObjectField(configClass, configField);
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, outputWidth, outputHeight, config);
    if (env->ExceptionCheck() || !bitmap) return nullptr;
    AndroidBitmapInfo info;
    void* destination = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.stride < static_cast<uint32_t>(outputWidth * 4) ||
        AndroidBitmap_lockPixels(env, bitmap, &destination) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw std::runtime_error("无法创建 HEIC 图片显示缓冲");
    }
    bool hasAlpha = heif_image_handle_has_alpha_channel(handle.get()) != 0;
    bool premultiply = hasAlpha && !heif_image_is_premultiplied_alpha(image.get());
    for (int row = 0; row < outputHeight; ++row) {
        auto* target = static_cast<uint8_t*>(destination) + row * info.stride;
        const auto* source = pixels + row * stride;
        if (!premultiply) {
            std::memcpy(target, source, static_cast<size_t>(outputWidth) * 4);
        } else {
            // Android 的 ARGB_8888 默认要求预乘透明度，防止透明边缘出现色边。
            for (int column = 0; column < outputWidth; ++column) {
                int offset = column * 4;
                int alpha = source[offset + 3];
                for (int channel = 0; channel < 3; ++channel) {
                    target[offset + channel] = static_cast<uint8_t>((source[offset + channel] * alpha + 127) / 255);
                }
                target[offset + 3] = static_cast<uint8_t>(alpha);
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    // 标记真实透明度，便于 Glide 选择合适的资源缓存编码格式。
    jmethodID setHasAlpha = env->GetMethodID(bitmapClass, "setHasAlpha", "(Z)V");
    if (setHasAlpha) env->CallVoidMethod(bitmap, setHasAlpha, static_cast<jboolean>(hasAlpha));
    return env->ExceptionCheck() ? nullptr : bitmap;
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_huluwa_heif_HeifDecoder_nativeDecode(JNIEnv* env, jclass, jbyteArray bytes, jint width, jint height) {
    try {
        if (!bytes || env->GetArrayLength(bytes) <= 0 || env->GetArrayLength(bytes) > 8 * 1024 * 1024) {
            throw std::runtime_error("HEIC 图片文件大小无效");
        }
        return decode(env, bytes, width, height);
    } catch (const std::exception& error) {
        // 原生错误转换为可被 Glide 捕获的异常，避免错误图片终止整个应用。
        if (!env->ExceptionCheck()) {
            jclass exception = env->FindClass("java/io/IOException");
            if (exception) env->ThrowNew(exception, error.what());
        }
        return nullptr;
    }
}
