package com.vipin25.bubbletranslate.data

/**
 * 轻量语言判定：不引入额外模型，只按字符区段猜语言。
 * 覆盖：中 / 日 / 韩 / 西里尔(俄等) / 阿拉伯 / 泰 / 希伯来 / 希腊 / 天城文(印地) / 拉丁(默认英语)。
 *
 * 判定用「占比」而不是「有没有」，避免一句话里夹几个英文单词就被判成英语。
 */
object LanguageDetector {

    const val ZH = "zh"
    const val EN = "en"
    const val JA = "ja"
    const val KO = "ko"
    const val RU = "ru"
    const val AR = "ar"
    const val TH = "th"
    const val HE = "he"
    const val EL = "el"
    const val HI = "hi"

    fun detect(text: String): String {
        if (text.isBlank()) return EN

        val total = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val kana = text.count { it in '\u3040'..'\u30FF' }        // 平假名 + 片假名
        val hangul = text.count { it in '\uAC00'..'\uD7AF' }      // 谚文
        val han = text.count { it in '\u4E00'..'\u9FFF' }         // 汉字
        val cyrillic = text.count { it in '\u0400'..'\u04FF' }    // 西里尔（俄语等）
        val arabic = text.count { it in '\u0600'..'\u06FF' }      // 阿拉伯
        val thai = text.count { it in '\u0E00'..'\u0E7F' }        // 泰文
        val hebrew = text.count { it in '\u0590'..'\u05FF' }      // 希伯来
        val greek = text.count { it in '\u0370'..'\u03FF' }       // 希腊
        val devanagari = text.count { it in '\u0900'..'\u097F' }  // 天城文（印地）

        return when {
            hangul * 10 >= total * 2 -> KO
            kana * 10 >= total * 1 -> JA
            han * 10 >= total * 3 -> ZH
            cyrillic * 10 >= total * 2 -> RU
            arabic * 10 >= total * 2 -> AR
            thai * 10 >= total * 2 -> TH
            hebrew * 10 >= total * 2 -> HE
            greek * 10 >= total * 2 -> EL
            devanagari * 10 >= total * 2 -> HI
            else -> EN
        }
    }

    fun isChinese(text: String): Boolean = detect(text) == ZH

    /**
     * 双向策略：
     *  - 选中母语  -> 翻成「对方语言」
     *  - 选中外文  -> 翻成母语
     */
    fun resolveTarget(sourceLang: String, otherPartyLang: String): String =
        if (sourceLang == ZH) otherPartyLang else ZH

    /** 语言代码 -> 给模型看的语言名。 */
    fun displayName(tag: String): String = when (tag) {
        ZH -> "Simplified Chinese"
        EN -> "English"
        JA -> "Japanese"
        KO -> "Korean"
        RU -> "Russian"
        AR -> "Arabic"
        TH -> "Thai"
        HE -> "Hebrew"
        EL -> "Greek"
        HI -> "Hindi"
        else -> tag
    }
}
