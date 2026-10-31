package com.vipin25.bubbletranslate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PrefsDataStore(private val context: Context) {

    private val TARGET_LANG_KEY = stringPreferencesKey("target_language_tag")
    private val API_KEY = stringPreferencesKey("openrouter_api_key")
    private val MODEL_KEY = stringPreferencesKey("openrouter_model")
    private val ALLOW_PAID = booleanPreferencesKey("allow_paid_models")
    private val NATIVE_LANG_KEY = stringPreferencesKey("native_language_tag")
    private val AUTO_TRANSLATE = booleanPreferencesKey("auto_translate")
    private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val BALL_SCALE = floatPreferencesKey("ball_scale")

    /** 悬浮球/角色的大小倍率：0.8 小、1.0 中、1.2 大。 */
    val ballScale: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[BALL_SCALE] ?: 1f }

    suspend fun setBallScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[BALL_SCALE] = scale
        }
    }

    /**
     * 自动翻译总开关：
     * 开 = 自动识别屏幕、自动跟随、有新内容/新输入就翻；
     * 关 = 休眠，只响应点悬浮球手动触发。
     */
    val autoTranslate: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AUTO_TRANSLATE] ?: false }

    suspend fun setAutoTranslate(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_TRANSLATE] = enabled
        }
    }

    /** 是否已经看过首次启动引导。 */
    val onboardingDone: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_DONE] = done
        }
    }

    /** 母语：读屏翻译的目标语言，也是界面语言。 */
    val nativeLanguage: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[NATIVE_LANG_KEY] ?: "zh" }

    suspend fun setNativeLanguage(tag: String) {
        context.dataStore.edit { preferences ->
            preferences[NATIVE_LANG_KEY] = tag
        }
    }

    val targetLanguageTag: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[TARGET_LANG_KEY] ?: "en"
        }

    /** OpenRouter API Key，用户自己填，绝不内置。 */
    val apiKey: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[API_KEY] ?: "" }

    /** 翻译模型；空串表示「还没选」，由 App 自动挑一个免费模型。 */
    val model: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[MODEL_KEY] ?: "" }

    /** 是否允许使用付费模型；默认 false = 只在免费模型里选。 */
    val allowPaidModels: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ALLOW_PAID] ?: false }

    suspend fun setTargetLanguageTag(tag: String) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_LANG_KEY] = tag
        }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[API_KEY] = key.trim()
        }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_KEY] = model.trim()
        }
    }

    suspend fun setAllowPaidModels(allow: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ALLOW_PAID] = allow
        }
    }

    companion object {
        /** 拉不到模型列表时的兜底模型：OpenRouter 官方免费路由。 */
        const val DEFAULT_MODEL = "openrouter/free"
    }
}
