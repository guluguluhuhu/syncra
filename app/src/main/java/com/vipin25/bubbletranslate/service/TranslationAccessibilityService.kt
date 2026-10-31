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
    }

    private var lastRefreshAt = 0L

    /** 输入框自动翻译的防抖（打字停下 1.5 秒后才翻，避免打断输入）。 */
    private val inputHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
            val cls = src?.className?.toString().orEmpty()
            val looksLikeInput = src?.isEditable == true ||
                cls.contains("EditText", ignoreCase = true) ||
                cls.contains("TextInput", ignoreCase = true)
            if (looksLikeInput) {
                val newText = event.text.firstOrNull()?.toString().orEmpty()
                lastInputText = newText
                // 事件没带 source 时不要用 null 覆盖之前拿到的节点
                if (src != null) lastInputNode = src

                // 输入框被清空 -> 允许下一段内容再自动翻一次
                if (newText.isBlank()) {
                    autoInputArmed = true
                }

                // 自动翻译开启时：只有「这一段还没自动翻过」才防抖触发
                val vm = DependencyProvider.viewModel(applicationContext)
                if (vm.isAutoTranslateEnabled() && autoInputArmed && newText.isNotBlank()) {
                    inputHandler.removeCallbacks(autoInputRunnable)
                    inputHandler.postDelayed(autoInputRunnable, 1500)
                }
            }
        }

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val from = event.fromIndex
            val to = event.toIndex
            val fullText = event.text.firstOrNull()?.toString() ?: ""

            if (fullText.isNotEmpty() && from >= 0 && to > from && to <= fullText.length) {
                val selectedText = fullText.substring(from, to)
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

        // 已经有译文层就静默刷新坐标；新页面则自动开始翻译
        vm.translateScreen(silent = vm.uiState.value is TranslationUiState.ScreenBubbles)
    }

    // ── 输入框读写（发送侧） ───────────────────────────

    /** 当前聚焦的输入框节点（X、微信、Telegram 等标准输入框都能拿到）。 */
    private fun focusedInputNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        // 兜底：部分 Compose 输入框不暴露 FOCUS_INPUT，遍历找可编辑节点
        return findEditable(root)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** 读取输入框里现有的文字：优先用文本变化事件缓存，其次找焦点/可编辑节点。 */
    fun readFocusedInputText(): String? {
        val cached = lastInputText
        if (!cached.isNullOrBlank()) return cached
        val node = focusedInputNode() ?: return null
        return node.text?.toString()?.takeIf { it.isNotBlank() }
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
    fun showToast(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 当前活动窗口属于哪个 App —— 用来判断用户有没有切走。 */
    fun currentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    override fun onInterrupt() {}

    override fun onDestroy() {
        inputHandler.removeCallbacks(autoInputRunnable)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"

        /** 供悬浮球调用；无障碍服务在系统里是单例。 */
        @Volatile
        var instance: TranslationAccessibilityService? = null
            private set
    }
}
