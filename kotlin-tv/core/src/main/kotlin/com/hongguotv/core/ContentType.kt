// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String, val searchTab: Int, val rankContent: String) {
    COMIC("comic","漫剧",19,"comic_series_rank"),
    SHORT("short","短剧",11,"all");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
