package com.vipin25.bubbletranslate.domain.model

enum class SignalType {
    TYPOSQUAT, HTTP_ONLY, IP_HOST, SUSPICIOUS_TLD,
    EXCESS_SUBDOMAINS, BRAND_KEYWORD, LONG_URL,
    URL_SHORTENER, PUNYCODE
}

enum class Weight { HIGH, MEDIUM, LOW }

enum class Verdict { SAFE, SUSPICIOUS, PHISHING }

data class RiskSignal(
    val type: SignalType,
    val detail: String,
    val weight: Weight
)

data class LinkCheckResult(
    val url: String,
    val verdict: Verdict,
    val signals: List<RiskSignal> = emptyList()
)
