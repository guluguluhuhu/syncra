package com.vipin25.bubbletranslate.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vipin25.bubbletranslate.DependencyProvider
import com.vipin25.bubbletranslate.data.LanguageDetector
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 屏幕上读到的一段文字及其位置。 */
data class ScreenText(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

class TranslationAccessibilityService : AccessibilityService() {

    private var lastPackage: String? = null

    /** 输入框里最新的文字（来自 TYPE_VIEW_TEXT_CHANGED，Compose 输入框 findFocus 经常拿不到）。 */
    @Volatile
    private var lastInputText: String? = null

    /** 打字时事件里带的输入框节点，写回时优先用它（比 findFocus 可靠）。 */
    @Volatile
    private var lastInputNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        // 文本变化事件在 X 这类 Compose 输入框上不可靠，改用轮询兜底
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, 1200)

        // 跟随设置里的「输入后延迟」
        val prefs = com.vipin25.bubbletranslate.data.PrefsDataStore(applicationContext)
        serviceScope.launch {
            prefs.inputDelaySec.collect { sec -> inputDelaySec = sec }
        }
    }

    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPolledText = ""
    private var lastPollAt = 0L

    /** 触发过一轮翻译后的冷却期，避免轮询每 1 秒打断上一次翻译。 */
    private var pollCooldownUntil = 0L

    /**
     * 轮询输入框：每 600ms 读一次，内容变化就重置计时；
     * 1 秒没有新输入就直接走「手动翻译」那套逻辑。
     * 不依赖 TYPE_VIEW_TEXT_CHANGED（Compose 输入框常常只发一次或不发）。
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                val vm = DependencyProvider.viewModel(applicationContext)
                if (vm.isAutoTranslateEnabled()) {
                    val text = readFocusedInputText()?.trim().orEmpty()
                    val now = System.currentTimeMillis()
                    if (text.isNotEmpty() && text != lastPolledText) {
                        lastPolledText = text
                        lastPollAt = now
                    } else if (text.isNotEmpty() &&
                        text == lastPolledText &&
                        // 停顿时间跟着设置走（1~5 秒，默认 2）
                        now - lastPollAt >= inputDelaySec * 1000L &&
                        now - lastWriteAt > 1500 &&
                        now >= pollCooldownUntil &&
                        !vm.isInputTranslating()
                    ) {
                        lastPollAt = now
                        pollCooldownUntil = now + 6000
                        vm.translateInput(silent = true)
                    }
                }
            } catch (_: Throwable) {
                // 轮询失败不影响主流程，下一次再试
            }
            pollHandler.postDelayed(this, 600)
        }
    }

    private var lastRefreshAt = 0L

    /** 输入框自动翻译的防抖（保留给手动/兜底用）。 */
    private val inputHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 键盘当前是否可见 —— 只在「键盘收起」时才自动翻译输入框。 */
    @Volatile
    private var keyboardVisible = false

    /** 输入框停顿多少秒后自动翻译（设置里可调 1~5，默认 2）。 */
    @Volatile
    private var inputDelaySec = 2

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    /** 上次把译文写回输入框的时间；写回后 1.5 秒内不再自动触发，避免自己触发自己。 */
    @Volatile
    private var lastWriteAt = 0L

    /**
     * 这一段输入是否还允许「自动翻译」。
     * 翻过一次就置 false，避免译文写回后又触发一次、把你编辑中的内容反复覆盖。
     * 输入框清空 / 切换 App 时重新置 true。
     */
    @Volatile
    private var autoInputArmed = true

    private val autoInputRunnable = Runnable {
        val vm = DependencyProvider.viewModel(applicationContext)
        if (vm.isAutoTranslateEnabled()) {
            autoInputArmed = false
            vm.translateInput(silent = true)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType

        // 切换 App 时清掉贴合层，避免译文留在别的界面上
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != lastPackage) {
                lastPackage = pkg
                lastInputText = null
                lastInputNode = null
                autoInputArmed = true
                DependencyProvider.viewModel(applicationContext).clearScreenTranslation()
            }
        }

        // 输入框文字变化：缓存最新内容（Compose 输入框靠 findFocus 经常读不到）
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val src = event.source
            runCatching { src?.refresh() }
            // 事件自带的 text 经常是空的/旧的（Compose 输入框），从刷新后的节点兜底读
            val newText = event.text.firstOrNull()?.toString()
                ?: src?.text?.toString()
                ?: ""

            lastInputText = newText
            if (src != null) lastInputNode = src

            // 不在这里判断语言：事件文本不可靠。
            // 统一排队，由 translateInput 读最新节点内容后自己判断（中文才翻，外语译文跳过）。
            val vm = DependencyProvider.viewModel(applicationContext)
            val cooledDown = System.currentTimeMillis() - lastWriteAt > 1500
            if (vm.isAutoTranslateEnabled() && cooledDown) {
                inputHandler.removeCallbacks(autoInputRunnable)
                inputHandler.postDelayed(autoInputRunnable, 2000)
            }
        }

        // 键盘收起 = 用户打完了。这时才自动翻译输入框，避免打到一半被覆盖。
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val imeVisible = runCatching {
                windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }.getOrDefault(false)

            if (keyboardVisible && !imeVisible) {
                val vm = DependencyProvider.viewModel(applicationContext)
                if (vm.isAutoTranslateEnabled()) {
                    inputHandler.removeCallbacks(autoInputRunnable)
                    inputHandler.postDelayed(autoInputRunnable, 300)
                }
            }
            keyboardVisible = imeVisible
            keyboardVisibleFlow.value = imeVisible
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val node = event.source
            // 事件里的 text 经常是空的（WebView / Compose 内容），优先从节点取全文和选区
            val fullText = event.text.firstOrNull()?.toString()
                ?: node?.text?.toString()
                ?: ""

            val from = node?.textSelectionStart?.takeIf { it >= 0 } ?: event.fromIndex
            val to = node?.textSelectionEnd?.takeIf { it >= 0 } ?: event.toIndex

            if (fullText.isNotEmpty() && from >= 0 && to > from) {
                val end = to.coerceAtMost(fullText.length)
                val selectedText = fullText.substring(from, end)
                if (selectedText.isNotBlank()) {
                    Log.d(TAG, "Selected string: $selectedText")
                    DependencyProvider.viewModel(applicationContext).onTextSelected(selectedText)
                }
            }
        }

        // 滚动 / 键盘弹出 / 界面重排：重算译文坐标，让贴合层跟着动
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            scheduleBubbleRefresh()
        }
    }

    /** 节流重算：界面变化很频繁，500ms 内只刷一次。自动翻译关闭时直接不响应。 */
    private fun scheduleBubbleRefresh() {
        val vm = DependencyProvider.viewModel(applicationContext)
        if (!vm.isAutoTranslateEnabled()) return

        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < 500) return
        lastRefreshAt = now

        // 自动刷新永远静默：失败或没读到内容都不弹错误卡片，
        // 否则每次界面变化都会重弹一次，卡片还会占住悬浮球没法关。
        vm.translateScreen(silent = true)
    }

    // ── 输入框读写（发送侧） ───────────────────────────

    /** 当前聚焦的输入框节点。先找焦点，再遍历，最后在所有窗口里找（微信这类多窗口 App）。 */
    private fun focusedInputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                val cls = node.className?.toString().orEmpty()
                if (node.isEditable || cls.contains("EditText", ignoreCase = true)) {
                    return node
                }
            }
            findEditable(root)?.let { return it }
        }

        // 兜底：遍历所有窗口
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                findEditable(r)?.let { return it }
            }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString().orEmpty()
        if (node.isEditable || cls.contains("EditText", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** 读取输入框里现有的文字：优先读节点实时内容（先 refresh），读不到再用事件缓存。 */
    fun readFocusedInputText(): String? {
        val node = focusedInputNode()
        if (node != null) {
            // 节点可能缓存了旧文本，先刷新一次
            runCatching { node.refresh() }
            val live = node.text?.toString()?.trim().orEmpty()
            if (live.isNotEmpty()) return live
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) return desc
        }
        return lastInputText?.takeIf { it.isNotBlank() }
    }

    /**
     * 主动抓一次当前焦点输入框的内容并缓存。
     * 面板打开后焦点可能就丢了（微信这类原生输入框尤其明显），所以在打开面板之前先抓。
     */
    fun captureFocusedInput() {
        val node = focusedInputNode() ?: return
        val text = node.text?.toString().orEmpty()
        if (text.isNotBlank()) {
            lastInputText = text
            lastInputNode = node
        }
    }

    /**
     * 猜当前窗口里「对方说的是什么语言」：
     * 扫描屏幕文本，统计非母语的语言出现次数，取最多的那个。
     * 用于发送时自动选目标语言（比如屏幕上都是俄语，就把你的中文翻成俄语）。
     */
    fun detectOtherLanguage(nativeLang: String): String? {
        val texts = collectScreenTexts()
            .map { it.text }
            .filter { it.length >= 5 }
            .filter { !it.matches(Regex("^[\\d\\s:./\\-]+$")) }
        if (texts.isEmpty()) return null

        val counts = HashMap<String, Int>()
        for (t in texts) {
            val lang = LanguageDetector.detect(t)
            if (lang != nativeLang) {
                counts[lang] = (counts[lang] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /**
     * 把译文写回输入框。
     * 只尝试 ACTION_SET_TEXT，不碰剪贴板（避免污染用户剪贴板）。
     * 不支持时返回 false，由调用方提示用户。
     */
    fun replaceFocusedInputText(text: String): Boolean {
        val node = lastInputNode ?: focusedInputNode() ?: return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
        if (ok) {
            // 写回成功后立刻更新缓存，后面的读屏就不会再把这段译文当成「消息」翻回去
            lastInputText = text
            autoInputArmed = false
            lastWriteAt = System.currentTimeMillis()

            // 把光标放到末尾，这样用户继续输入时是接在后面，而不是插到开头
            runCatching {
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
        }
        return ok
    }

    // ── 读屏（接收侧，不依赖选中） ─────────────────────

    /**
     * 遍历当前窗口的节点树，收集所有带文字的节点及其屏幕坐标。
     * X 这类长按弹菜单的 App 不会触发选区事件，但节点树里的文本照样能读。
     */
    fun collectScreenTexts(): List<ScreenText> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<ScreenText>(64)
        walk(root, 0, out)
        return out.distinctBy { "${it.text}|${it.top}|${it.left}" }
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<ScreenText>) {
        if (node == null || depth > 40) return

        // 有些界面（尤其是 X 这种自绘/Compose）把文字放在 contentDescription 里，text 是空的
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val content = when {
            !text.isNullOrBlank() -> text
            !desc.isNullOrBlank() -> desc
            else -> null
        }

        if (!content.isNullOrBlank() && content.length >= 2) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                out.add(ScreenText(content, r.left, r.top, r.right, r.bottom))
            }
        }

        for (i in 0 until node.childCount) {
            walk(node.getChild(i), depth + 1, out)
        }
    }

    fun screenHeight(): Int = resources.displayMetrics.heightPixels

    /** 手动翻译一次后调用：这一段内容不再自动触发，避免紧接着又被自动翻一遍。 */
    fun disarmAutoInput() {
        autoInputArmed = false
    }

    /** 输入框在屏幕上的矩形；拿不到时返回 null。 */
    fun focusedInputBounds(): Rect? {
        return runCatching {
            val node = lastInputNode ?: focusedInputNode()
            if (node == null) return@runCatching null
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) r else null
        }.getOrNull()
    }

    /** 从无障碍服务弹一个 Toast（ViewModel 没有 Context，借这里提示）。 */
    fun showToast(text: String, minIntervalMs: Long = 0) {
        if (minIntervalMs > 0) {
            val now = System.currentTimeMillis()
            if (now - lastToastAt < minIntervalMs) return
            lastToastAt = now
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private var lastToastAt = 0L

    /** 当前活动窗口属于哪个 App —— 用来判断用户有没有切走。 */
    fun currentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    override fun onInterrupt() {}

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        inputHandler.removeCallbacks(autoInputRunnable)
        serviceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"

        /** 供悬浮球调用；无障碍服务在系统里是单例。 */
        @Volatile
        var instance: TranslationAccessibilityService? = null
            private set

        /** 键盘是否可见：译文层用它决定要不要给输入框让位。 */
        val keyboardVisibleFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}
