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

    /** 等待授权后安装的 APK；授权设置页返回后由 Activity onResume 续装。 */
    @Volatile
    private var pendingApk: File?=null

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
                // 同版本已完整下载并校验过：授权回来重试、取消安装后重试都直接复用，不重下。
                if(target.exists() && verified(target,update)) {
                    activity.runOnUiThread { onProgress(100,target.length(),target.length()) }
                    activity.runOnUiThread { onDone(target) }
                    return@execute
                }
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

    /** 本地文件与服务器声明的大小、哈希一致才算可复用。 */
    private fun verified(file: File, update: AppUpdateInfo): Boolean {
        if(update.apkSize>0 && file.length()!=update.apkSize) return false
        if(update.apkSha256.isBlank()) return file.length()>0
        return sha256(file).equals(update.apkSha256,ignoreCase=true)
    }

    fun install(activity: Activity, file: File) {
        if(Build.VERSION.SDK_INT>=26 && !activity.packageManager.canRequestPackageInstalls()) {
            // 记下待安装文件：从授权设置页返回后由 resumePendingInstall() 自动续装，
            // 不让弹框一直停在「正在安装…」。
            pendingApk=file
            val intent=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            toastOrFallback(activity,intent,"请先允许红果安装未知应用，返回后自动继续安装")
            return
        }
        launchInstaller(activity,file)
    }

    /**
     * 授权未知应用安装后回到本应用时续装。返回 true 表示已拉起系统安装器
     * （pending 已消费）；未授权、无 pending 或拉起失败返回 false，pending 保留。
     */
    fun resumePendingInstall(activity: Activity): Boolean {
        val file=pendingApk ?: return false
        if(Build.VERSION.SDK_INT>=26 && !activity.packageManager.canRequestPackageInstalls()) return false
        if(!file.exists()) { pendingApk=null; return false }
        pendingApk=null
        return runCatching { launchInstaller(activity,file) }.isSuccess
    }

    private fun launchInstaller(activity: Activity, file: File) {
        val uri=FileProvider.getUriForFile(activity,"${activity.packageName}.fileprovider",file)
        val intent=Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,"application/vnd.android.package-archive")
            // 覆盖安装会结束当前 App 进程：安装器用独立任务栈，
            // 装完点「打开」才能拉起新进程，而不是退回桌面（参考葫芦娃 102ccef）。
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
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
                        data=uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
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
