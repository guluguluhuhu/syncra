package com.vipin25.bubbletranslate.domain

class ModelNotDownloadedException(message: String = "Translation model not downloaded") : Exception(message)

interface TranslationRepository {
    suspend fun translate(text: String): Result<String>
    suspend fun isModelDownloaded(): Boolean
    suspend fun downloadModel()
    fun setTargetLanguage(tag: String)
    fun setSourceLanguage(tag: String)

    /**
     * 批量翻译。默认逐条串行；具体实现可以覆写成一次请求（更省请求次数、也不容易撞免费模型的限流）。
     */
    suspend fun translateBatch(texts: List<String>): Result<List<String>> {
        val out = ArrayList<String>(texts.size)
        for (text in texts) {
            val r = translate(text)
            out.add(r.getOrElse { return Result.failure(it) })
        }
        return Result.success(out)
    }
}
