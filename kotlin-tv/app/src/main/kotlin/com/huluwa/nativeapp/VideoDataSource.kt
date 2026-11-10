// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var opened=false
    private var uri: Uri?=null
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec); uri=spec.uri; position=spec.position
        if(position>video.total) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) video.total-position else minOf(spec.length,video.total-position)
        opened=true; transferStarted(spec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        // 按提取器的读取量解密，避免只要几 KB 却同步等待并解密整块 2 MiB。
        val bytes=video.read(position,minOf(length.toLong(),remaining,64*1024L).toInt())
        if(bytes.isEmpty()) throw java.io.EOFException("视频分段意外结束")
        bytes.copyInto(target,targetOffset)
        position+=bytes.size; remaining-=bytes.size; bytesTransferred(bytes.size); return bytes.size
    }
    override fun getUri()=uri
    override fun close() { uri=null; if(opened) { opened=false; transferEnded() } }
}
