package com.vipin25.bubbletranslate.domain

interface UrlDetector {
    fun isWebUrl(text: String): Boolean
}
