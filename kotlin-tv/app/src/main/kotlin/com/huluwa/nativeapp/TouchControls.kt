// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View

/** 沿用原有界面，只为手机补充触摸点击处理。 */
object TouchControls {
    fun isHandheld(context: Context): Boolean {
        val features=context.packageManager
        val television=(context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
        return !television && !features.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !features.hasSystemFeature(PackageManager.FEATURE_TELEVISION) &&
            features.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** 仅在手机提前获取触摸焦点，避免吞掉首次点击；电视不安装触摸监听，保留原有焦点行为。 */
    @SuppressLint("ClickableViewAccessibility")
    fun focusOnTouch(view: View,enabled: Boolean=isHandheld(view.context)) {
        if(!enabled) return
        view.setOnTouchListener { target,event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN && target.isEnabled && target.isFocusableInTouchMode && !target.isFocused) {
                target.requestFocus()
            }
            false
        }
    }
}
