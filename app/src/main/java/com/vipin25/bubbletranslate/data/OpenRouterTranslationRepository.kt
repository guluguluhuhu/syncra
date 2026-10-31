package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.TranslationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端翻译实现：OpenRouter Chat Completions。
 *
 * - 不在 App 内打包任何模型，安装包最小。
 * - API Key 由用户自己填（设置页），代码里绝不内置。
 * - 语种不限：模型自带上百种语言识别，源语言交给模型自己判断。
 * - 只用 HttpURLConnection + org.json（Android 自带），不引入额外网络库。
 * - 所有网络请求都切到 Dispatchers.IO，绝不能跑在主线程。
 */
class OpenRouterTranslationRepository(
    private val prefs: PrefsDataStore
) : TranslationRepository {

    private var targetLang = LanguageDetector.ZH

    override fun setTargetLanguage(tag: String) {
        targetLang = tag
    }

    override fun setSourceLanguage(tag: String) {
        // 源语言交给模型自动判断，这里不需要
    }

    override suspend fun isModelDownloaded(): Boolean = true

    override suspend fun downloadModel() {
        // 云端翻译不需要下载模型
    }

    override suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = prefs.apiKey.first()
            if (key.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("还没填 OpenRouter API Key，请先在 App 首页填好并保存")
                )
            }
            val model = prefs.model.first().ifBlank { PrefsDataStore.DEFAULT_MODEL }
            val body = buildRequestBody(text, targetLang, model)
            Result.success(extractContent(post(ENDPOINT, body, key)))
        } catch (e: Exception) {
            Result.failure(wrap(e))
        }
    }

    /**
     * 批量翻译：把所有文本用分隔符拼成一次请求。
     * 读屏时一次翻 4 段，只发 1 个请求，避开免费模型的并发限流。
     */
    override suspend fun translateBatch(texts: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext Result.success(emptyList())
            try {
                val key = prefs.apiKey.first()
                if (key.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("还没填 OpenRouter API Key，请先在 App 首页填好并保存")
                    )
                }
                val model = prefs.model.first().ifBlank { PrefsDataStore.DEFAULT_MODEL }
                val joined = texts.joinToString(SEP)

                val systemPrompt = """
                    You translate several segments separated by the exact marker <<<SEP>>>.
                    Translate EVERY segment into ${languageName(targetLang)}.
                    Output ONLY the translations, separated by the same marker, in the same order.
                    Never include the original text, numbering, explanations or extra text.
                """.trimIndent()

                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", joined))
                }
                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.2)
                    put("messages", messages)
                }.toString()

                val content = extractContent(post(ENDPOINT, body, key))
                val parts = content.split(SEP)
                if (parts.size == texts.size) {
                    Result.success(parts.map { it.trim() })
                } else {
                    // 模型没按分隔符返回 -> 退回逐条翻译
                    val out = ArrayList<String>(texts.size)
                    for (t in texts) {
                        val r = translate(t)
                        if (r.isFailure) {
                            return@withContext Result.failure(
                                r.exceptionOrNull() ?: Exception("翻译失败")
                            )
                        }
                        out.add(r.getOrThrow())
                    }
                    Result.success(out)
                }
            } catch (e: Exception) {
                Result.failure(wrap(e))
            }
        }

    private fun buildRequestBody(text: String, target: String, model: String): String {
        val targetName = languageName(target)
        val systemPrompt = """
            You are a translation engine. Translate the user's text into $targetName.
            Output ONLY the translated text.
            Never repeat, quote or include the original text.
            No explanations, no romanization, no notes, no extra lines.
            Preserve the original tone, names, numbers and formatting.
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", text))
        }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", messages)
        }.toString()
    }

    private fun languageName(tag: String): String = LanguageDetector.displayName(tag)

    private fun post(url: String, body: String, key: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/vipin25/bubble-translate")
            conn.setRequestProperty("X-Title", "BubbleTranslate")

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code：${raw.take(300)}")
            }
            return raw
        } finally {
            conn.disconnect()
        }
    }

    private fun extractContent(raw: String): String {
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices")
            ?: throw IOException("接口返回里没有 choices：${raw.take(200)}")
        if (choices.length() == 0) throw IOException("接口返回空结果")
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        if (content.isBlank()) throw IOException("译文为空")
        return content
    }

    /** 异常 message 为空时补一句能看懂的信息，避免界面只显示「未知」。 */
    private fun wrap(e: Exception): Exception {
        if (!e.message.isNullOrBlank()) return e
        val cause = e.cause?.message
        val detail = if (!cause.isNullOrBlank()) "：$cause" else ""
        return Exception(e.javaClass.simpleName + detail, e)
    }

    companion object {
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val SEP = "\n<<<SEP>>>\n"
    }
}
