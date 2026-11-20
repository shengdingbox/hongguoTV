// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdateInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 应用内 APK 下载、哈希校验和系统安装器拉起。 */
object AppUpdateInstaller {
    private val worker=Executors.newSingleThreadExecutor()
    private val http=OkHttpClient.Builder()
        .connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS)
        .callTimeout(10,TimeUnit.MINUTES)
        .build()

    fun download(
        activity: Activity,
        update: AppUpdateInfo,
        onProgress: (Int,Long,Long)->Unit,
        onDone: (File)->Unit,
        onError: (String)->Unit,
    ) {
        worker.execute {
            try {
                val directory=File(activity.getExternalFilesDir(null) ?: activity.filesDir,"updates")
                    .apply { mkdirs() }
                val target=File(directory,"hongguotv-${update.latestVersionCode}.apk")
                val temp=File(directory,"${target.name}.part")
                val call=Request.Builder().url(update.apkUrl).get().build()
                http.newCall(call).execute().use { response ->
                    if(!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                    val body=response.body ?: error("下载内容为空")
                    val total=body.contentLength().takeIf { it>0 } ?: update.apkSize
                    temp.delete()
                    body.byteStream().use { input ->
                        temp.outputStream().use { output ->
                            val buffer=ByteArray(64*1024)
                            var received=0L
                            var lastPercent=-1
                            while(true) {
                                val count=input.read(buffer)
                                if(count<0) break
                                output.write(buffer,0,count)
                                received+=count
                                val percent=if(total>0) (received*100/total).toInt().coerceIn(0,100) else 0
                                if(percent!=lastPercent) { lastPercent=percent; activity.runOnUiThread { onProgress(percent,received,total) } }
                            }
                        }
                    }
                    if(update.apkSize>0 && temp.length()!=update.apkSize) error("APK 大小校验失败")
                    if(update.apkSha256.isNotBlank()) {
                        val actual=sha256(temp)
                        if(!actual.equals(update.apkSha256,ignoreCase=true)) error("APK 哈希校验失败")
                    }
                    target.delete()
                    if(!temp.renameTo(target)) error("无法保存 APK")
                    activity.runOnUiThread { onDone(target) }
                }
            } catch (problem: Throwable) {
                activity.runOnUiThread { onError(problem.message ?: "下载失败") }
            }
        }
    }

    fun install(activity: Activity, file: File) {
        if(Build.VERSION.SDK_INT>=26 && !activity.packageManager.canRequestPackageInstalls()) {
            val intent=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            toastOrFallback(activity,intent,"请先允许红果安装未知应用，然后重新点击升级")
            return
        }
        val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.fileprovider",file)
        val intent=Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,"application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        toastOrFallback(activity,intent,"设备没有可用的系统安装器",uri,file)
    }

    /** 打开系统安装器；小米/部分电视可能只认旧安装 Intent，失败时再回退。 */
    private fun toastOrFallback(activity: Activity,intent: Intent,message: String,uri: Uri?=null,file: File?=null) {
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
            if(uri!=null && file!=null) {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        data=uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    return
                }
            }
            Toast.makeText(activity,message,Toast.LENGTH_LONG).show()
        }
    }

    private fun sha256(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(64*1024)
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                digest.update(buffer,0,count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
