package com.vipin25.bubbletranslate.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 翻译缓存（持久化到 filesDir/translation_cache.json）。
 * key = 原文，value = 译文。同一句话只翻一次，二次打开直接命中。
 */
object TranslationCache {

    private val map = ConcurrentHashMap<String, String>()
    private var file: File? = null
    private var loaded = false
    private var dirty = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        file = File(context.applicationContext.filesDir, "translation_cache.json")
        runCatching {
            val f = file ?: return
            if (f.exists()) {
                val obj = JSONObject(f.readText())
                obj.keys().forEach { k -> map[k] = obj.getString(k) }
            }
        }
        loaded = true
    }

    /** key 里带目标语言：切母语后不会命中旧语言的译文。 */
    private fun key(text: String, lang: String): String = "$lang|${text.trim()}"

    fun get(text: String, lang: String): String? = map[key(text, lang)]

    fun put(text: String, lang: String, translation: String) {
        val k = key(text, lang)
        if (translation.isBlank()) return
        map[k] = translation
        dirty = true
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 800)
    }

    fun size(): Int = map.size

    /** 清空全部翻译缓存（内存 + 磁盘），防止聊天记录留在本地。 */
    @Synchronized
    fun clear() {
        map.clear()
        dirty = true
        saveNow()
    }

    @Synchronized
    private fun saveNow() {
        if (!dirty) return
        val f = file ?: return
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            f.writeText(obj.toString())
            dirty = false
        }
    }
}
