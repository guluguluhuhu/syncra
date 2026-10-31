package com.vipin25.bubbletranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouter 模型条目。
 * 价格统一换算成「每百万 token 的美元价」，与 AIRP 的 `_provPerM` 口径一致。
 */
data class OpenRouterModel(
    val id: String,
    val name: String,
    val promptPerM: Double,
    val completionPerM: Double,
    val contextLength: Int
) {
    val isFree: Boolean get() = promptPerM <= 0.0 && completionPerM <= 0.0

    fun priceLabel(): String = if (isFree) {
        "免费"
    } else {
        "入 $${fmt(promptPerM)}/M · 出 $${fmt(completionPerM)}/M"
    }

    fun ctxLabel(): String =
        if (contextLength >= 1000) "${contextLength / 1000}K 上下文" else "$contextLength 上下文"

    private fun fmt(v: Double): String = when {
        v <= 0 -> "0"
        v < 0.01 -> String.format(java.util.Locale.US, "%.4f", v)
        v < 1 -> String.format(java.util.Locale.US, "%.3f", v)
        else -> String.format(java.util.Locale.US, "%.2f", v)
    }
}

/**
 * 拉取 OpenRouter 的模型列表（免鉴权）。
 * 逻辑照搬 AIRP：GET {base}/models，读 data[].id / pricing.prompt / pricing.completion / context_length，
 * 排序口径是「免费优先 -> 输入价从低到高 -> 模型名」。
 */
object OpenRouterModelsRepository {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/models"
    private const val CACHE_MS = 10 * 60 * 1000L

    @Volatile
    private var cache: List<OpenRouterModel>? = null

    @Volatile
    private var cacheAt = 0L

    suspend fun fetch(force: Boolean = false): Result<List<OpenRouterModel>> =
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val hit = cache
                if (!force && hit != null && now - cacheAt < CACHE_MS) {
                    return@withContext Result.success(hit)
                }
                val list = parse(get(ENDPOINT))
                if (list.isEmpty()) throw IOException("模型列表为空")
                cache = list
                cacheAt = now
                Result.success(list)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code：${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(raw: String): List<OpenRouterModel> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("data") ?: throw IOException("返回里没有 data 数组")
        val out = ArrayList<OpenRouterModel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            val pricing = o.optJSONObject("pricing")
            val prompt = pricing?.optDouble("prompt", 0.0) ?: 0.0
            val completion = pricing?.optDouble("completion", 0.0) ?: 0.0
            if (prompt < 0 || completion < 0) continue
            val ctx = o.optInt("context_length", 0)
            out.add(
                OpenRouterModel(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    promptPerM = prompt * 1e6,
                    completionPerM = completion * 1e6,
                    contextLength = ctx
                )
            )
        }
        return out.sortedWith(
            // OpenRouter 官方的免费路由排最前；其余免费在前、再按输入价、最后按模型名
            compareBy<OpenRouterModel> { it.id != FREE_ROUTER_ID }
                .thenBy { !it.isFree }
                .thenBy { it.promptPerM }
                .thenBy { it.id }
        )
    }

    /** OpenRouter 官方的「免费模型路由」：自动在免费模型里分配，最省心的默认值。 */
    const val FREE_ROUTER_ID = "openrouter/free"
}
