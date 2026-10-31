package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.LinkCheckRepository
import com.vipin25.bubbletranslate.domain.model.LinkCheckResult

class NoOpLinkCheckRepository : LinkCheckRepository {
    // Real repository injection happens in Mission C
    override suspend fun checkUrl(url: String): LinkCheckResult {
        TODO("Not yet implemented")
    }
}
