package com.vipin25.bubbletranslate.data

/** App 支持的界面语言 / 母语。 */
enum class UiLanguage(val tag: String, val nativeName: String) {
    ZH("zh", "中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    KO("ko", "한국어");

    companion object {
        fun fromTag(tag: String): UiLanguage = entries.firstOrNull { it.tag == tag } ?: ZH
    }
}
