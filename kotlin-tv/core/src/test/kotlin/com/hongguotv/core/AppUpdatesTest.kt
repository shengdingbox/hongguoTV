package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun release() = JSONObject()
        .put("taskId", 5).put("versionCode", 10005).put("versionName", "1.0.5").put("minSdk", 26)
        .put("packageName", "com.huluwa.nativeapp").put("apkSize", bytes.size)
        .put("sha256", sha256(bytes).uppercase()).put("commitSubject", "feat: 测试更新")
    private fun envelope(available: Boolean = true) = JSONObject().put("code", 200).put("message", "ok")
        .put("data", JSONObject().put("available", available).apply { if (available) put("release", release()) })
    private fun update() = AppUpdate.parse(envelope().toString(), "com.huluwa.nativeapp")!!
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }
    @Test fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(10004, 26)); assertFalse(update.newerThan(10005, 36))
        assertFalse(update.newerThan(10006, 36)); assertFalse(update.newerThan(10004, 20))
    }
    @Test fun absentReleaseIsNotAnUpdateAndIsNotAnError() {
        // available=false 表示打包服务没有正式产物，既不是「已是最新」，也不该沿用旧包
        assertNull(AppUpdate.parse(envelope(false).toString(), "com.huluwa.nativeapp"))
    }
    @Test fun uppercaseDigestIsNormalized() {
        assertEquals(sha256(bytes), update().sha256)
    }
    @Test fun rejectOtherPackageAndInvalidEnvelope() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parse(envelope().toString(), "com.hongguotv.nativeapp")
        }
        val failed = JSONObject().put("code", 500).put("message", "服务异常")
        val error = assertThrows(IOException::class.java) { AppUpdate.parse(failed.toString(), "com.huluwa.nativeapp") }
        assertTrue(error.message!!.contains("服务异常"))
    }
    @Test fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            val body = envelope(); body.getJSONObject("data").getJSONObject("release").put("versionCode", code)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(body.toString(), "com.huluwa.nativeapp") }
        }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES + 1)) {
            val body = envelope(); body.getJSONObject("data").getJSONObject("release").put("apkSize", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(body.toString(), "com.huluwa.nativeapp") }
        }
        val body = envelope(); body.getJSONObject("data").getJSONObject("release").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(body.toString(), "com.huluwa.nativeapp") }
        val name = envelope(); name.getJSONObject("data").getJSONObject("release").put("versionName", "../../evil")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(name.toString(), "com.huluwa.nativeapp") }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateRepository("not a url", "com.huluwa.nativeapp") }
    }
    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path + ".part").exists())
    }
    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path + ".part").exists())
    }
    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes + byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }
    @Test fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf(); corrupt[0] = 0
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }
    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path + ".part").exists())
    }
    @Test fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size)); assertFalse(UpdateFiles.verify(file, update()))
    }
}
