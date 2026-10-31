package com.vipin25.bubbletranslate.domain

import com.vipin25.bubbletranslate.domain.model.LinkCheckResult

interface LinkCheckRepository {
    suspend fun checkUrl(url: String): LinkCheckResult
}
