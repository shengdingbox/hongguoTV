// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View

/** 手机使用触屏操作条；电视仍使用遥控器与鼠标操作。 */
object TouchControls {
    fun isHandheld(context: Context): Boolean {
        val features=context.packageManager
        val television=(context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)==Configuration.UI_MODE_TYPE_TELEVISION
        return !television && !features.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            features.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** 提前获取触摸焦点，避免原生 View 在抬手时只获取焦点、吞掉首次点击。点击与取消仍交给原生处理。 */
    @SuppressLint("ClickableViewAccessibility")
    fun focusOnTouch(view: View) {
        view.setOnTouchListener { target,event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN && target.isEnabled && target.isFocusableInTouchMode && !target.isFocused) {
                target.requestFocus()
            }
            false
        }
    }
}
