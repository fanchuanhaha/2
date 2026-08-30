package com.widgetflow.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.widgetflow.app.model.KeyValue
import com.widgetflow.app.model.WidgetConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class ApiResult {
    data class Success(val status: Int, val body: String, val ms: Long, val json: Any?) : ApiResult()
    data class Failure(val reason: String, val detail: String) : ApiResult()
}

/** 基于 HttpURLConnection 的请求客户端，无第三方依赖 */
object ApiClient {

    private val pool: ExecutorService = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 异步执行；retries 为失败后的自动重试次数（PRD：重试 2 次，间隔 1s / 4s） */
    fun executeAsync(config: WidgetConfig, retries: Int = 0, onDone: (ApiResult) -> Unit) {
        pool.execute {
            var result = execute(config)
            var attempt = 0
            val delays = longArrayOf(1000L, 4000L)
            while (result is ApiResult.Failure && attempt < retries) {
                try {
                    Thread.sleep(delays[attempt.coerceAtMost(1)])
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                attempt++
                result = execute(config)
            }
            val r = result
            main.post { onDone(r) }
        }
    }

    fun execute(config: WidgetConfig): ApiResult {
        val started = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val full = buildUrl(config.url, config.params)
            conn = (URL(full).openConnection() as HttpURLConnection).apply {
                requestMethod = config.method
                connectTimeout = config.timeoutSec * 1000
                readTimeout = config.timeoutSec * 1000
                config.headers.forEach { h ->
                    if (h.key.isNotBlank()) setRequestProperty(h.key, h.value)
                }
                if (config.method == "POST" && config.body.isNotBlank()) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(config.body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val ms = SystemClock.elapsedRealtime() - started

            if (code !in 200..299) {
                return ApiResult.Failure("HTTP $code", body.take(200))
            }
            // 优先解析 JSON；纯文本等非 JSON 响应同样算成功，json 置空，
            // 供「正则 / 整段文本」抽取使用
            val parsed = try {
                parseJson(body)
            } catch (e: Exception) {
                null
            }
            return ApiResult.Success(code, body, ms, parsed)
        } catch (e: SocketTimeoutException) {
            return ApiResult.Failure("超时", "请求超过 ${config.timeoutSec} 秒无响应")
        } catch (e: Exception) {
            return ApiResult.Failure("网络错误", e.message ?: "无法连接")
        } finally {
            conn?.disconnect()
        }
    }

    fun buildUrl(base: String, params: List<KeyValue>): String {
        val sb = StringBuilder(base.trim())
        val qs = params.filter { it.key.isNotBlank() }
        if (qs.isEmpty()) return sb.toString()
        sb.append(if (sb.contains('?')) '&' else '?')
        qs.forEachIndexed { i, kv ->
            if (i > 0) sb.append('&')
            sb.append(URLEncoder.encode(kv.key, "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(kv.value, "UTF-8"))
        }
        return sb.toString()
    }

    private fun parseJson(body: String): Any? {
        val t = body.trim()
        if (t.isEmpty()) throw IllegalArgumentException("空响应")
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }
}
