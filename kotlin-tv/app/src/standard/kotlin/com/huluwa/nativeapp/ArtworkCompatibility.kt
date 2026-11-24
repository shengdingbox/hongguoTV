// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.bumptech.glide.Glide
import com.bumptech.glide.Registry

/** 标准版保留系统图片解码配置。 */
internal object ArtworkCompatibility {
    fun register(glide: Glide, registry: Registry) = Unit
}
