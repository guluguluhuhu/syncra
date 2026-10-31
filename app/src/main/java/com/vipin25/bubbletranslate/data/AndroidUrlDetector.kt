package com.vipin25.bubbletranslate.data

import android.util.Patterns
import com.vipin25.bubbletranslate.domain.UrlDetector

class AndroidUrlDetector : UrlDetector {
    override fun isWebUrl(text: String): Boolean {
        return Patterns.WEB_URL.matcher(text.trim()).matches()
    }
}
