package com.vipin25.bubbletranslate.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin25.bubbletranslate.data.PrefsDataStore
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.vipin25.bubbletranslate.data.LanguageDetector
import com.vipin25.bubbletranslate.data.TranslationCache
import com.vipin25.bubbletranslate.data.NoOpLinkCheckRepository
import com.vipin25.bubbletranslate.service.TranslationAccessibilityService

@OptIn(FlowPreview::class)
class TranslationViewModel(
    private val repository: TranslationRepository,
    private val linkCheckRepo: LinkCheckRepository = NoOpLinkCheckRepository(),
    private val urlDetector: UrlDetector = object : UrlDetector { override fun isWebUrl(text: String) = false },
    private val prefsDataStore: PrefsDataStore? = null // Nullable for tests
) : ViewModel() {

    private val _uiState = MutableStateFlow<TranslationUiState>(TranslationUiState.Idle)
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    private val textInputFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private var lastText = ""

    /** 「对方语言」：选中母语时翻成它，选中外文时翻成母语。默认英文。 */
    private var otherPartyLang = LanguageDetector.EN

    /** 母语：读屏翻译的目标语言，也是界面语言。默认中文。 */
    private var nativeLang = LanguageDetector.ZH

    /** 自动翻译总开关：关掉后不自动读屏/不自动翻译，只响应手动点击。 */
    @Volatile
    private var autoTranslateEnabled = false

    fun isAutoTranslateEnabled(): Boolean = autoTranslateEnabled

    /** 串行化「设置语言 + 翻译」，避免读屏翻译和输入框翻译互相覆盖目标语言。 */
    private val translateMutex = Mutex()

    init {
        // 记住用户选择的「对方语言」；不再直接写死目标语言，方向由每次翻译时判定
        viewModelScope.launch {
            prefsDataStore?.targetLanguageTag?.collectLatest { tag ->
                otherPartyLang = tag
                if (lastText.isNotBlank()) {
                    textInputFlow.tryEmit(lastText)
                }
            }
        }

        viewModelScope.launch {
            prefsDataStore?.nativeLanguage?.collectLatest { tag ->
                nativeLang = tag
            }
        }

        viewModelScope.launch {
            prefsDataStore?.autoTranslate?.collectLatest { enabled ->
                autoTranslateEnabled = enabled
            }
        }

        // Collect text selection
        viewModelScope.launch {
            textInputFlow
                .debounce(300L)
                .collectLatest { text ->
                    translateInternal(text)
                }
        }
    }

    private suspend fun translateInternal(text: String) {
        if (text.isBlank()) {
            _uiState.value = TranslationUiState.Idle
            return
        }
        
        if (isUrl(text)) {
            println("Routing to link check")
            checkLink(text)
            return
        }
        
        println("Routing to translation")
        lastText = text
        val source = LanguageDetector.detect(text)
        val target = if (source == nativeLang) otherPartyLang else nativeLang
        _uiState.value = TranslationUiState.Loading(text.take(40))
        try {
            if (!repository.isModelDownloaded()) {
                _uiState.value = TranslationUiState.ModelDownloading
                repository.downloadModel()
            }
            val result = translateMutex.withLock {
                repository.setSourceLanguage(source)
                repository.setTargetLanguage(target)
                repository.translate(text)
            }
            if (result.isSuccess) {
                _uiState.value = TranslationUiState.Success(
                    translation = result.getOrNull() ?: "",
                    originalText = text
                )
            } else {
                val exception = result.exceptionOrNull()
                if (exception is kotlinx.coroutines.CancellationException) throw exception
                _uiState.value = TranslationUiState.Error(exception?.message ?: "Unknown error")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _uiState.value = TranslationUiState.Error(e.message ?: "Unknown error")
        }
    }

    fun onTextSelected(text: String) {
        textInputFlow.tryEmit(text)
    }

    fun translate(text: String) {
        onTextSelected(text)
    }

    /** 按文本内容自动决定源/目标语言：中文→对方语言，外文→中文。 */

    /**
     * 输入框翻译（发送侧）：
     * 读取当前聚焦输入框里的文字 -> 翻译 -> 写回输入框。
     * 失败时把译文显示在气泡卡片里，至少让用户能手动复制。
     */
    fun translateInput(silent: Boolean = false) {
        val service = TranslationAccessibilityService.instance ?: return
        val source = service.readFocusedInputText()?.trim() ?: return
        if (source.isEmpty()) return

        viewModelScope.launch {
            if (!silent) _uiState.value = TranslationUiState.Loading(source.take(40))
            try {
                val sourceLang = LanguageDetector.detect(source)
                // 自动模式下只翻「母语 -> 外语」，避免译文又被翻回来形成循环
                if (silent && sourceLang != nativeLang) return@launch
                val target = if (sourceLang == nativeLang) {
                    // 自动识别当前窗口对方在用什么语言；识别不到再退回手动设置
                    service.detectOtherLanguage(nativeLang) ?: otherPartyLang
                } else {
                    nativeLang
                }

                if (!repository.isModelDownloaded()) {
                    _uiState.value = TranslationUiState.ModelDownloading
                    repository.downloadModel()
                }
                val result = translateMutex.withLock {
                    repository.setSourceLanguage(sourceLang)
                    repository.setTargetLanguage(target)
                    repository.translate(source)
                }
                if (result.isSuccess) {
                    val translated = result.getOrNull() ?: ""
                    val written = service.replaceFocusedInputText(translated)
                    if (written) {
                        if (!silent) _uiState.value = TranslationUiState.Idle
                    } else {
                        if (silent) {
                            service.showToast("输入框写不进去，试试收起键盘后再点一次")
                        } else {
                            _uiState.value = TranslationUiState.Error(
                                "这个输入框不允许外部写入（试试收起键盘后再点一次）。\n\n$translated"
                            )
                        }
                    }
                } else {
                    val exception = result.exceptionOrNull()
                    if (exception is kotlinx.coroutines.CancellationException) throw exception
                    _uiState.value = TranslationUiState.Error(exception?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (silent) {
                    service.showToast("输入框翻译失败：${e.message ?: "未知"}")
                } else {
                    _uiState.value = TranslationUiState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun checkLink(text: String) {
        viewModelScope.launch {
            _uiState.value = TranslationUiState.LinkScanning
            val result = linkCheckRepo.checkUrl(text)
            _uiState.value = when (result.verdict) {
                com.vipin25.bubbletranslate.domain.model.Verdict.SAFE -> TranslationUiState.LinkSafe(
                    result.url,
                    result.signals.map { it.detail }
                )
                com.vipin25.bubbletranslate.domain.model.Verdict.SUSPICIOUS -> TranslationUiState.LinkSuspicious(result.url, result.signals)
                com.vipin25.bubbletranslate.domain.model.Verdict.PHISHING -> TranslationUiState.LinkDangerous(result.url, result.signals)
            }
        }
    }

    private fun isUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 10) return false
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return urlDetector.isWebUrl(trimmed)
    }

    /**
     * 读屏翻译（接收侧，不依赖「选中」）：
     * 遍历当前窗口的节点树，取出所有外文消息，逐条翻译并带上屏幕坐标，
     * 由悬浮层把译文画在每条原文下面。已翻译过的内容走本地缓存，不重复调 API。
     */
    fun translateScreen(silent: Boolean = false) {
        val service = TranslationAccessibilityService.instance
        if (service == null) {
            if (!silent) _uiState.value = TranslationUiState.Error(
                "无障碍服务没连上。\n去「设置 → 无障碍 → BubbleTranslate」关掉再打开一次。"
            )
            return
        }

        val all = service.collectScreenTexts()
        if (all.isEmpty()) {
            if (silent) {
                // 新页面读不到字（比如返回列表页）就把旧译文层收掉
                if (_uiState.value is TranslationUiState.ScreenBubbles) {
                    _uiState.value = TranslationUiState.Idle
                }
            } else {
                _uiState.value = TranslationUiState.Error(
                    "没读到屏幕上的文字。可能是这个界面禁止无障碍读取，或者当前 App 用了自绘界面。"
                )
            }
            return
        }

        val screenH = service.screenHeight()
        val shownPackage = service.currentPackageName()

        // 输入框里的文字不是「对方消息」，读屏时要排除。
        // 优先按「输入框的屏幕矩形」排除（与文本内容无关，对方发同样的话也不会被误跳过）；
        // 拿不到矩形时才退回按文本比较。
        val inputText = service.readFocusedInputText()?.trim().orEmpty()
        val inputBounds = service.focusedInputBounds()

        val filtered = all
            .filter { it.text.length >= 3 }
            .filter {
                if (inputBounds != null) {
                    !inputBounds.contains(it.left, it.top, it.right, it.bottom)
                } else {
                    inputText.isEmpty() || it.text != inputText
                }
            }
            .filter { !it.text.matches(Regex("^[\\d\\s:./\\-]+$")) }
            .filter { !it.text.matches(Regex("^[\\p{Punct}\\s]+$")) }
            .filter { it.top > screenH * 0.05 && it.bottom < screenH * 0.95 }
            .filter { LanguageDetector.detect(it.text) != LanguageDetector.ZH }
            .sortedBy { it.top }
            .distinctBy { it.text }

        val lang = nativeLang

        if (filtered.isEmpty()) {
            if (silent) {
                // 当前页面没有外文（例如返回了列表页），清掉旧译文层
                if (_uiState.value is TranslationUiState.ScreenBubbles) {
                    _uiState.value = TranslationUiState.Idle
                }
            } else {
                _uiState.value = TranslationUiState.Error(
                    "屏幕上没读到外文（中文提示已跳过）。\n如果消息本身就是外文，说明这个 App 没把正文暴露给无障碍。"
                )
            }
            return
        }

        viewModelScope.launch {
            if (!silent) _uiState.value = TranslationUiState.Loading(
                filtered.firstOrNull()?.text?.take(40) ?: ""
            )
            try {
                // 1) 先查本地缓存
                val missing = filtered.map { it.text }
                    .filter { TranslationCache.get(it, lang) == null }
                    .distinct()

                // 2) 只翻没缓存的
                if (missing.isNotEmpty()) {
                    val result = translateMutex.withLock {
                        repository.setTargetLanguage(lang)
                        repository.translateBatch(missing)
                    }
                    if (result.isFailure) {
                        if (!silent) _uiState.value = TranslationUiState.Error(
                            "翻译失败：${result.exceptionOrNull()?.message ?: "未知"}\n（检查 API Key、模型、网络/代理）"
                        )
                        return@launch
                    }
                    // 3) 写回缓存（持久化）
                    val pairs = missing.zip(result.getOrThrow())
                    pairs.forEach { (src, dst) -> TranslationCache.put(src, lang, dst) }
                }

                // 翻译期间如果已经切走 App，就不要再把一个旧界面的译文层画上去
                val nowPkg = service.currentPackageName()
                if (nowPkg != null && nowPkg != shownPackage) return@launch

                val items = filtered.map { block ->
                    com.vipin25.bubbletranslate.domain.model.ScreenBubble(
                        original = block.text,
                        translation = TranslationCache.get(block.text, lang) ?: "（未翻译）",
                        left = block.left,
                        top = block.top,
                        right = block.right,
                        bottom = block.bottom
                    )
                }
                _uiState.value = TranslationUiState.ScreenBubbles(items, shownPackage)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!silent) _uiState.value = TranslationUiState.Error(e.message ?: "读屏翻译失败")
            }
        }
    }

    /** 界面滚动/键盘弹出时静默重算坐标（不闪 Loading、不弹错误）。 */
    fun refreshScreenBubbles() {
        translateScreen(silent = true)
    }

    /** 切换 App / 退出界面时清掉译文层（Loading 中的也清，避免旧界面结果画到新 App 上）。 */
    fun clearScreenTranslation() {
        val s = _uiState.value
        if (s is TranslationUiState.ScreenBubbles || s is TranslationUiState.Loading) {
            _uiState.value = TranslationUiState.Idle
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            prefsDataStore?.setTargetLanguageTag(lang)
        }
    }

    fun dismiss() {
        _uiState.value = TranslationUiState.Idle
    }
}
