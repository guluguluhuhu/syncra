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
import com.vipin25.bubbletranslate.service.TranslationAccessibilityService

@OptIn(FlowPreview::class)
class TranslationViewModel(
    private val repository: TranslationRepository,
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

    /** 输入框翻译是否正在进行中（轮询用它避免重复触发）。 */
    fun isInputTranslating(): Boolean = inputJob?.isActive == true

    /** 自动翻译总开关：关掉后不自动读屏/不自动翻译，只响应手动点击。 */
    @Volatile
    private var autoTranslateEnabled = false

    /** 面板里锁定的目标语言；空串 = 自动判断方向。选中翻译也用它。 */
    @Volatile
    private var lockedTargetLang = ""

    fun isAutoTranslateEnabled(): Boolean = autoTranslateEnabled

    /** 串行化「设置语言 + 翻译」，避免读屏翻译和输入框翻译互相覆盖目标语言。 */
    private val translateMutex = Mutex()

    /** 当前正在跑的读屏翻译任务；新的一次会取消旧的，避免自动模式下任务堆积。 */
    private var screenJob: kotlinx.coroutines.Job? = null

    /** 正在跑的输入框翻译任务；避免自动模式下并发多个、互相抢锁。 */
    private var inputJob: kotlinx.coroutines.Job? = null

    /**
     * 输入框里「已经翻过的汉字段」。
     * 再次出现同样的中文时直接复用译文，不重复请求；已有的译文也不会被再次处理。
     */
    private val translatedInputSegments = HashMap<String, String>()

    /**
     * 上一次写回输入框的完整内容。
     * 输入框里常常是「日文/英文译文 + 新中文」，用它可以只处理新增部分，
     * 避免整段被假名/字母污染而误判成外语、直接跳过。
     */
    @Volatile
    private var lastWrittenInput = ""

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

        viewModelScope.launch {
            prefsDataStore?.lockedTargetLang?.collectLatest { lang ->
                lockedTargetLang = lang
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
        
        println("Routing to translation")
        lastText = text
        val source = LanguageDetector.detect(text)
        // 面板里锁定了目标语言就用它；选「自动」才按源语言判断方向
        val target = lockedTargetLang.ifBlank {
            if (source == nativeLang) otherPartyLang else nativeLang
        }
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
    fun translateInput(silent: Boolean = false, targetOverride: String? = null) {
        val service = TranslationAccessibilityService.instance ?: return
        val source = service.readFocusedInputText()?.trim().orEmpty()
        if (source.isEmpty()) {
            if (!silent) service.showToast("输入框里没有内容（或者没读到输入框）")
            return
        }

        // 自动模式不打断进行中的翻译（轮询每 1 秒一次，打断就永远跑不完）；手动点击则强制重来
        if (inputJob?.isActive == true) {
            if (silent) return
            inputJob?.cancel()
        }
        inputJob = viewModelScope.launch {
            if (!silent) _uiState.value = TranslationUiState.Loading(source.take(40))
            if (!silent) service.showToast("读到输入框：${source.take(30)}")
            try {
                // 中文母语：只处理「上次写回内容」之后的新增部分。
                // 输入框里常常是「日文译文 + 新中文」，整段判断会因为含假名被误判成外语而直接跳过。
                val prefix = if (lastWrittenInput.isNotEmpty() && source.startsWith(lastWrittenInput)) {
                    lastWrittenInput
                } else {
                    ""
                }
                val baseText = source.substring(prefix.length)
                val hasHan = baseText.any { it in '\u4E00'..'\u9FFF' }
                if (nativeLang == LanguageDetector.ZH && hasHan) {
                    // 目标语言：面板锁定 -> 屏幕上对方用的语言 -> 英文
                    val target = targetOverride
                        ?: lockedTargetLang.ifBlank {
                            service.detectOtherLanguage(nativeLang) ?: LanguageDetector.EN
                        }

                    // 手动点「翻译输入框」：整段一次性翻译，不按句子切，保证完整不失真
                    if (!silent) {
                        val whole = baseText.trim()
                        if (whole.isEmpty()) {
                            _uiState.value = TranslationUiState.Idle
                            return@launch
                        }
                        val result = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            translateMutex.withLock {
                                repository.setSourceLanguage(nativeLang)
                                repository.setTargetLanguage(target)
                                repository.translate(whole)
                            }
                        }
                        if (result == null) {
                            service.showToast("翻译超时", 30_000)
                            _uiState.value = TranslationUiState.Idle
                            return@launch
                        }
                        if (result.isFailure) {
                            _uiState.value = TranslationUiState.Error(
                                "翻译失败：${result.exceptionOrNull()?.message ?: "未知"}"
                            )
                            return@launch
                        }
                        val merged = prefix + result.getOrThrow()
                        if (service.replaceFocusedInputText(merged)) {
                            lastWrittenInput = merged
                            _uiState.value = TranslationUiState.Idle
                        } else {
                            _uiState.value = TranslationUiState.Error(
                                "这个输入框不允许外部写入。\n\n$merged"
                            )
                        }
                        return@launch
                    }

                    // 自动模式：按句子切（。！？.!? 和换行），整句翻译，避免一句话里夹英文被切碎导致语义割裂
                    val sentenceRegex = Regex("[^。！？.!?\\n]+[。！？.!?\\n]?")
                    val sentences = sentenceRegex.findAll(baseText)
                        .map { it.value }
                        .filter { it.isNotBlank() && it.any { c -> c in '\u4E00'..'\u9FFF' } }
                        .distinct()
                        .toList()
                    if (sentences.isEmpty()) {
                        if (!silent) _uiState.value = TranslationUiState.Idle
                        return@launch
                    }

                    // 只翻「还没翻过的句子」，已有的直接复用，不再请求、不再改写
                    val untranslated = sentences.filter { translatedInputSegments[it.trim()] == null }
                    if (untranslated.isNotEmpty()) {
                        val result = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            translateMutex.withLock {
                                repository.setSourceLanguage(nativeLang)
                                repository.setTargetLanguage(target)
                                repository.translateBatch(untranslated)
                            }
                        }
                        if (result == null) {
                            service.showToast("翻译超时", 30_000)
                            if (!silent) _uiState.value = TranslationUiState.Idle
                            return@launch
                        }
                        if (result.isFailure) {
                            if (silent) {
                                service.showToast("翻译失败：${result.exceptionOrNull()?.message ?: "未知"}", 30_000)
                            } else {
                                _uiState.value = TranslationUiState.Error(
                                    "翻译失败：${result.exceptionOrNull()?.message ?: "未知"}"
                                )
                            }
                            return@launch
                        }
                        untranslated.zip(result.getOrThrow()).forEach { (src, dst) ->
                            translatedInputSegments[src.trim()] = dst
                        }
                    }

                    // 把新增部分的句子替换成译文，已有的外语内容原样保留 —— 阶段式拼接
                    val merged = prefix + sentenceRegex.replace(baseText) { match ->
                        translatedInputSegments[match.value.trim()] ?: match.value
                    }
                    if (merged == source) {
                        // 没有新增内容，不用写回
                        if (!silent) _uiState.value = TranslationUiState.Idle
                        return@launch
                    }

                    // 翻译期间用户又继续输入了 -> 放弃这次写回，等下一轮防抖再翻整段，
                    // 避免把刚打进去的新内容覆盖掉。
                    val current = service.readFocusedInputText()?.trim().orEmpty()
                    if (current != source) {
                        if (!silent) _uiState.value = TranslationUiState.Idle
                        return@launch
                    }
                    // 再确认一次：如果读到的内容和发起时不同（节点刷新后变了），同样放弃
                    val currentAgain = service.readFocusedInputText()?.trim().orEmpty()
                    if (currentAgain != source) {
                        if (!silent) _uiState.value = TranslationUiState.Idle
                        return@launch
                    }

                    val written = service.replaceFocusedInputText(merged)
                    if (written) {
                        lastWrittenInput = merged
                        if (!silent) _uiState.value = TranslationUiState.Idle
                    } else {
                        if (silent) {
                            service.showToast("输入框写不进去，试试收起键盘后再点一次", 30_000)
                        } else {
                            _uiState.value = TranslationUiState.Error(
                                "这个输入框不允许外部写入（试试收起键盘后再点一次）。\n\n$merged"
                            )
                        }
                    }
                    return@launch
                }

                // 其他母语：整段翻译（原有逻辑）
                val sourceLang = LanguageDetector.detect(source)
                // 自动模式下只翻「母语 -> 外语」，避免译文又被翻回来形成循环
                if (silent && sourceLang != nativeLang) return@launch
                val target = targetOverride ?: lockedTargetLang.ifBlank {
                    if (sourceLang == nativeLang) {
                        // 自动识别当前窗口对方在用什么语言；识别不到默认英文
                        service.detectOtherLanguage(nativeLang) ?: LanguageDetector.EN
                    } else {
                        nativeLang
                    }
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
                            service.showToast("输入框写不进去，试试收起键盘后再点一次", 30_000)
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
                    service.showToast("输入框翻译失败：${e.message ?: "未知"}", 30_000)
                } else {
                    _uiState.value = TranslationUiState.Error(e.message ?: "Unknown error")
                }
            } finally {
                // 保险：任务结束（成功 / 失败 / 超时 / 取消）立刻释放，
                // 后续任务看到的一定是干净的，不会挤在一起。
                if (inputJob === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                    inputJob = null
                }
            }
        }
    }

    /**
     * 读屏翻译（接收侧，不依赖「选中」）：
     * 遍历当前窗口的节点树，取出所有外文消息，逐条翻译并带上屏幕坐标，
     * 由悬浮层把译文画在每条原文下面。已翻译过的内容走本地缓存，不重复调 API。
     */
    fun translateScreen(silent: Boolean = false) {
        // 自动刷新不要覆盖用户刚手动触发的「选中翻译」结果卡片
        if (silent && _uiState.value is TranslationUiState.Success) return

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

        // 已有任务在跑就不再启动新的。
        // 之前是「取消旧的」，但界面持续变化时每次刷新都会打断上一次翻译，导致永远翻不完、一直停在 Loading。
        if (screenJob?.isActive == true) return
        screenJob = viewModelScope.launch {
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
                    val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                        translateMutex.withLock {
                            repository.setTargetLanguage(lang)
                            repository.translateBatch(missing)
                        }
                    }
                    if (result == null) {
                        // 超时兜底：别让状态永远停在 Loading
                        if (silent) {
                            if (_uiState.value is TranslationUiState.Loading) {
                                _uiState.value = TranslationUiState.Idle
                            }
                        } else {
                            _uiState.value = TranslationUiState.Error("翻译超时（25 秒）")
                        }
                        return@launch
                    }
                    if (result.isFailure) {
                        if (silent) {
                            // 自动模式失败：把 Loading 收掉，别让它一直转圈
                            if (_uiState.value is TranslationUiState.Loading) {
                                _uiState.value = TranslationUiState.Idle
                            }
                        } else {
                            _uiState.value = TranslationUiState.Error(
                                "翻译失败：${result.exceptionOrNull()?.message ?: "未知"}\n（检查 API Key、模型、网络/代理）"
                            )
                        }
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
                if (silent) {
                    if (_uiState.value is TranslationUiState.Loading) {
                        _uiState.value = TranslationUiState.Idle
                    }
                } else {
                    _uiState.value = TranslationUiState.Error(e.message ?: "读屏翻译失败")
                }
            }
        }
    }
    /** 界面滚动/键盘弹出时静默重算坐标（不闪 Loading、不弹错误）。 */
    fun refreshScreenBubbles() {
        translateScreen(silent = true)
    }

    /** 切换 App / 退出界面时清掉所有覆盖层状态（含错误卡片，避免“没读到文字”一直挂着）。 */
    fun clearScreenTranslation() {
        // 先取消正在跑的读屏翻译，否则它跑完会把译文层又画回来（关了自动翻译也照样常驻）
        screenJob?.cancel()
        screenJob = null
        val s = _uiState.value
        if (s is TranslationUiState.ScreenBubbles ||
            s is TranslationUiState.ScreenTranslation ||
            s is TranslationUiState.Loading ||
            s is TranslationUiState.Error
        ) {
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
