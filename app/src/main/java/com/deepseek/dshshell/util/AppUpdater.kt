package com.deepseek.dshshell.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 自身（APK）版本更新检测与下载。
 *
 * 与 [UpdateChecker]（检测内置 dsh npm 版本）不同：这里检测的是本 App 的新版 APK，
 * 数据源为 GitHub Releases。为避免国内网络无法直连 GitHub，内置多源回退：
 *  - 版本检测：GitHub API（releases/latest）→ jsDelivr CDN 读仓库 version.json
 *  - APK 下载：GitHub 直链 → ghproxy 系列镜像
 *
 * 只做可选更新：检测到新版由调用方弹可选对话框，不强制。
 */
object AppUpdater {

    private const val REPO = "gudegan/deepseek-harness-android"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val releaseUrl: String,
        val source: String,
    )

    data class CheckResult(
        val info: UpdateInfo?,
        val error: String?,
    )

    /** 版本检测源：GitHub API → jsDelivr（读仓库 version.json） */
    private val CHECK_SOURCES = listOf(
        "https://api.github.com/repos/$REPO/releases/latest" to "GitHub",
        "https://cdn.jsdelivr.net/gh/$REPO@main/version.json" to "jsDelivr",
    )

    /** APK 下载镜像前缀（依次尝试），空串表示 GitHub 直链 */
    private val DOWNLOAD_PROXIES = listOf(
        "",
        "https://ghproxy.com/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
    )

    /** 解析 "v0.5.24" → "0.5.24" */
    private fun stripV(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /** 简单的点分版本比较；a 更新则返回 true */
    fun isNewer(latest: String, current: String): Boolean {
        if (latest == current) return false
        val a = latest.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /**
     * 检测最新 APK 版本。依次尝试各源，成功返回 [CheckResult.info]；全部失败返回 error。
     */
    suspend fun check(timeoutMs: Int = 8000): CheckResult = withContext(Dispatchers.IO) {
        for ((url, name) in CHECK_SOURCES) {
            try {
                val body = httpGet(url, timeoutMs) ?: continue
                when (name) {
                    "GitHub" -> {
                        val json = JSONObject(body)
                        // release 的 assets 里找 .apk
                        val assets = json.optJSONArray("assets") ?: continue
                        var apkUrl: String? = null
                        for (i in 0 until assets.length()) {
                            val a = assets.optJSONObject(i) ?: continue
                            if (a.optString("name").endsWith(".apk", true)) {
                                apkUrl = a.optString("browser_download_url").ifEmpty { null }
                                break
                            }
                        }
                        val tag = stripV(json.optString("tag_name"))
                        if (tag.isEmpty()) continue
                        val info = UpdateInfo(
                            versionName = tag,
                            versionCode = -1, // GitHub 源拿不到 versionCode，用 versionName 比较
                            apkUrl = apkUrl ?: json.optString("html_url"),
                            releaseUrl = json.optString("html_url").ifEmpty {
                                "https://github.com/$REPO/releases"
                            },
                            source = name,
                        )
                        return@withContext CheckResult(info, null)
                    }
                    "jsDelivr" -> {
                        val json = JSONObject(body)
                        val vn = json.optString("versionName")
                        val apk = json.optString("releaseUrl")
                        if (vn.isEmpty()) continue
                        val info = UpdateInfo(
                            versionName = vn,
                            versionCode = json.optInt("versionCode", -1),
                            apkUrl = apk.ifEmpty { json.optString("releaseUrl") },
                            releaseUrl = json.optString("releaseUrl").ifEmpty {
                                "https://github.com/$REPO/releases"
                            },
                            source = name,
                        )
                        return@withContext CheckResult(info, null)
                    }
                }
            } catch (_: Exception) {
                // 换下一个源
            }
        }
        CheckResult(null, "无法连接更新源（GitHub / jsDelivr 均不可达）")
    }

    /**
     * 下载 APK 到缓存目录，带进度回调（0..100）。依次尝试直链与镜像；失败抛异常。
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "dsh-update.apk")
        var lastError: Exception? = null
        for (proxy in DOWNLOAD_PROXIES) {
            val full = if (proxy.isEmpty()) url else proxy + url
            try {
                val conn = URL(full).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.requestMethod = "GET"
                if (conn.responseCode != 200) {
                    lastError = IllegalStateException("HTTP ${conn.responseCode}")
                    continue
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (ins.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                onProgress((done * 100 / total).toInt())
                            }
                        }
                    }
                }
                return@withContext dest
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败（所有源均不可达）")
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }
}
