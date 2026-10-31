package com.vipin25.bubbletranslate.domain.model

sealed class TranslationUiState {
    object Idle : TranslationUiState()
    data class Loading(val preview: String = "") : TranslationUiState()
    data class Success(val translation: String, val originalText: String) : TranslationUiState()
    data class Error(val message: String) : TranslationUiState()
    object ModelDownloading : TranslationUiState()

    /** 读屏翻译（旧版列表形态，保留兼容）。 */
    data class ScreenTranslation(val items: List<Pair<String, String>>) : TranslationUiState()

    /** 逐条贴合：每条消息的原文、译文和屏幕坐标（译文画在原文下方）。 */
    data class ScreenBubbles(
        val items: List<ScreenBubble>,
        val packageName: String? = null
    ) : TranslationUiState()

    // ── LinkShield additions ────────────────────────
    object LinkScanning : TranslationUiState()

    data class LinkSafe(
        val url: String,
        val reasons: List<String>
    ) : TranslationUiState()

    data class LinkSuspicious(
        val url: String,
        val signals: List<RiskSignal>
    ) : TranslationUiState()

    data class LinkDangerous(
        val url: String,
        val signals: List<RiskSignal>
    ) : TranslationUiState()
}

/** 一条消息在屏幕上的位置与译文。 */
data class ScreenBubble(
    val original: String,
    val translation: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
