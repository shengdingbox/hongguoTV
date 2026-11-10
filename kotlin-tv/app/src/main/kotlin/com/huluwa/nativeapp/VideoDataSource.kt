// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.EpisodeDiskCache
import com.hongguotv.core.MediaCrypto
import com.hongguotv.core.RemoteVideo

/** 播放取数：优先命中整集落盘缓存（含部分下载的已落盘区间），未命中回落在线分块读取。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo, private val disk: EpisodeDiskCache? = null): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var opened=false
    private var uri: Uri?=null
    // 磁盘命中时用的元数据快照：解密索引与明文头随条目固定，不随在线地址刷新
    private var meta: EpisodeDiskCache.Meta?=null
    private var diskUnavailable=false
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec); uri=spec.uri; position=spec.position
        if(position>video.total) throw java.io.IOException("读取位置超出视频长度")
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) video.total-position else minOf(spec.length,video.total-position)
        meta=disk?.meta(video.info.idHint,video.info.qualityNumber)
        opened=true; transferStarted(spec); return remaining
    }
    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        val want=minOf(length.toLong(),remaining,64*1024L).toInt()
        val snapshot=meta
        val cache=disk
        if(snapshot!=null && cache!=null && !diskUnavailable) {
            val count=cache.read(video.info.idHint,video.info.qualityNumber,position,target,targetOffset,want)
            if(count>0) {
                val decrypted=MediaCrypto.decrypt(target.copyOfRange(targetOffset,targetOffset+count),position,snapshot.key,snapshot.prepared)
                decrypted.copyInto(target,targetOffset)
                position+=count; remaining-=count; bytesTransferred(count); return count
            }
            // 落盘缓存 I/O 异常视为本集不可用，之后全部回落在线（返回 0 仅表示该区间还没下完）
            if(count<0) diskUnavailable=true
        }
        // 按提取器的读取量解密，避免只要几 KB 却同步等待并解密整块 2 MiB。
        val bytes=video.read(position,want)
        if(bytes.isEmpty()) throw java.io.EOFException("视频分段意外结束")
        bytes.copyInto(target,targetOffset)
        position+=bytes.size; remaining-=bytes.size; bytesTransferred(bytes.size); return bytes.size
    }
    override fun getUri()=uri
    override fun close() { uri=null; meta=null; if(opened) { opened=false; transferEnded() } }
}
