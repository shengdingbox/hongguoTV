// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import java.io.File
import java.security.MessageDigest

/** 公共封面磁盘缓存，最多 64 MiB / 400 张，7 天未访问过期；读写都在图片工作线程执行。 */
class ArtworkCache(private val directory: File) {
    private fun file(url: String)=File(directory,MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    @Synchronized fun get(url: String): ByteArray? = runCatching {
        val f=file(url)
        val now=System.currentTimeMillis()
        val age=now-f.lastModified()
        if(!f.isFile || f.length() !in 1..4*1024*1024 || age !in 0..7*24*60*60*1000L) null
        else f.readBytes().also { if(age>=60_000L) f.setLastModified(now) }
    }.getOrNull()
    @Synchronized fun put(url: String,bytes: ByteArray) {
        if(bytes.size>4*1024*1024) return
        runCatching {
            directory.mkdirs(); val dest=file(url); val temp=File(directory,"${dest.name}.tmp")
            temp.writeBytes(bytes); if(!temp.renameTo(dest)) temp.delete()
            val files=directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
            var size=0L
            files.forEachIndexed { index,f -> size+=f.length(); if(size>64*1024*1024 || index>=400) f.delete() }
        }
    }
}
