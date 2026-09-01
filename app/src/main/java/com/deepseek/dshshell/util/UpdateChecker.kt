package com.deepseek.dshshell.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * dsh 更新检测：远程查询 @deepseek-ai/dsh 最新版本，与内置 runtime 版本比对。
 *
 * 网络优化：官方 registry 不可达（如部分网络环境）时自动回退到国内镜像，
 * 超时/失败统一返回 error 而不是抛异常，UI 展示友好提示。
 */
object UpdateChecker {

    data class CheckResult(
        val latest: String?,
        val error: String?,
        val usedRegistry: String?,
    )

    /** 依次尝试的 registry（官方 → 国内镜像），"git 无法访问"时的兜底 */
    private val REGISTRIES = listOf(
        "https://registry.npmjs.org" to "官方源",
        "https://registry.npmmirror.com" to "国内镜像",
    )

    /** 解析 runtime.version（形如 dsh-0.1.1-rc.2_node-24.1.0_r1）里的 dsh 版本 */
    fun embeddedDshVersion(builtinVersion: String?): String? {
        if (builtinVersion.isNullOrBlank()) return null
        return Regex("dsh-([^_]+)").find(builtinVersion)?.groupValues?.get(1)
    }

    /**
     * 远程查询最新 dsh 版本。网络失败返回 error（不抛异常）。
     * @param timeoutMs 每次请求超时
     */
    suspend fun checkLatest(timeoutMs: Int = 8000): CheckResult = withContext(Dispatchers.IO) {
        for ((base, name) in REGISTRIES) {
            try {
                val url = "$base/@deepseek-ai/dsh/latest"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    continue // 换下一个源
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val version = JSONObject(body).optString("version").ifEmpty { null }
                return@withContext CheckResult(version, null, name)
            } catch (_: Exception) {
                // 该源不可达，尝试下一个
            } finally {
            }
        }
        CheckResult(null, "无法连接更新源（官方源与国内镜像均失败）", null)
    }

    /** 简单的语义版本比较（dsh 版本形如 0.1.1 / 0.1.1-rc.2） */
    fun isNewer(latest: String?, embedded: String?): Boolean {
        if (latest.isNullOrBlank() || embedded.isNullOrBlank()) return false
        val a = latest.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val b = embedded.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // 主版本相同：非 rc/预发布 视为更新
        val latestPre = latest.contains('-')
        val embeddedPre = embedded.contains('-')
        return !latestPre && embeddedPre
    }
}
