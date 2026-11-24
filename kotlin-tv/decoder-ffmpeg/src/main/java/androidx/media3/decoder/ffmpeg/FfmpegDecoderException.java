// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2016 The Android Open Source Project
// 来源：AndroidX Media 1.8.0；完整授权见模块中的 LICENSE-AndroidX。
// 本项目仅构建 AAC 音频解码，并保留输出缓冲扩容时的 PCM 数据。
package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

@UnstableApi
public final class FfmpegDecoderException extends DecoderException {

   FfmpegDecoderException(String message) {
    super(message);
  }

   FfmpegDecoderException(String message, Throwable cause) {
    super(message, cause);
  }
}
