package com.ht.hjtk.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** GitHub REST 전용 작은 HTTP(PATCH 포함). 토큰은 Keystore로 암호화된 비밀함에서만 읽는다 */
object GitHubHttp {
    suspend fun call(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            runCatching { c.requestMethod = method }.onFailure {
                // 일부 구현은 PATCH를 막는다 → 내부 필드로 지정
                runCatching { HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(c, method) }
            }
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "HJTK-Android")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = ((if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }).orEmpty()
            code to text
        } catch (e: Exception) { 0 to (e.message ?: "네트워크 오류") } finally { c.disconnect() }
    }
}
