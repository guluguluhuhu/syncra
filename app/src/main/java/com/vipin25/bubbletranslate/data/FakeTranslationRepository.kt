package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.ModelNotDownloadedException
import com.vipin25.bubbletranslate.domain.TranslationRepository
import kotlinx.coroutines.delay

class FakeTranslationRepository(
    private val shouldFail: Boolean = false,
    private var modelDownloaded: Boolean = false
) : TranslationRepository {

    override suspend fun translate(text: String): Result<String> {
        delay(500)
        if (shouldFail) {
            return Result.failure(Exception("Translation failed"))
        }
        if (!modelDownloaded) {
            return Result.failure(ModelNotDownloadedException())
        }
        return Result.success("Translated: $text")
    }

    override suspend fun isModelDownloaded(): Boolean {
        return modelDownloaded
    }

    override suspend fun downloadModel() {
        delay(100)
        modelDownloaded = true
    }
    
    override fun setTargetLanguage(tag: String) {
        // no-op for fake
    }

    override fun setSourceLanguage(tag: String) {
        // no-op for fake
    }
}
