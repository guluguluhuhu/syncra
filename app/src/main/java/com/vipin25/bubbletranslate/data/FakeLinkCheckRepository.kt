package com.vipin25.bubbletranslate.data

import com.vipin25.bubbletranslate.domain.LinkCheckRepository
import com.vipin25.bubbletranslate.domain.model.LinkCheckResult
import com.vipin25.bubbletranslate.domain.model.RiskSignal
import com.vipin25.bubbletranslate.domain.model.Verdict
import kotlinx.coroutines.delay

class FakeLinkCheckRepository(
    private val verdict: Verdict = Verdict.SAFE,
    private val signals: List<RiskSignal> = emptyList(),
    private val delayMs: Long = 300L
) : LinkCheckRepository {
    override suspend fun checkUrl(url: String): LinkCheckResult {
        delay(delayMs)
        return LinkCheckResult(url, verdict, signals)
    }
}
