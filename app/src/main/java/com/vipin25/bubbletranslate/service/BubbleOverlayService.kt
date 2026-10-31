package com.vipin25.bubbletranslate.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vipin25.bubbletranslate.DependencyProvider
import com.vipin25.bubbletranslate.data.PrefsDataStore
import com.vipin25.bubbletranslate.domain.model.ScreenBubble
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import com.vipin25.bubbletranslate.ui.bubble.BubbleComposable
import com.vipin25.bubbletranslate.ui.bubble.BubbleTranslationLayer
import kotlinx.coroutines.launch
import kotlin.math.abs
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

class BubbleOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private val overlayLifecycleOwner = OverlayLifecycleOwner()

    private val bubbleItems = mutableStateOf<List<ScreenBubble>>(emptyList())
    private var bubbleLayer: ComposeView? = null

    /** 当前 UI 状态（面板/卡片显示时要让 Compose 接管触摸）。 */
    private val currentUiState = mutableStateOf<TranslationUiState>(TranslationUiState.Idle)

    /** 吸附方向：0 = 没吸附，-1 = 贴左，1 = 贴右。 */
    private val snapSideState = mutableStateOf(0)

    /** 设置小面板是否打开。 */
    private val showSettingsState = mutableStateOf(false)

    /** 自动翻译开关的镜像（真正的值在 prefs）。 */
    private val autoTranslateState = mutableStateOf(false)

    private val prefs by lazy { PrefsDataStore(applicationContext) }
    private var downAt = 0L
    private var lastClickAt = 0L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒检查一次前台 App：一旦离开翻译时的那个 App，就自动清掉译文层。只在译文层显示期间跑。 */
    private val packageChecker = object : Runnable {
        override fun run() {
            val vm = DependencyProvider.viewModel(applicationContext)
            val state = vm.uiState.value
            if (state is TranslationUiState.ScreenBubbles) {
                val current = TranslationAccessibilityService.instance?.currentPackageName()
                if (current != null && state.packageName != null && current != state.packageName) {
                    vm.clearScreenTranslation()
                    return
                }
                mainHandler.postDelayed(this, 1000)
            }
        }
    }
    
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        
        val channelId = "bubble_service_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Bubble Translate Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Syncra")
            .setContentText("屏幕翻译服务正在运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayLifecycleOwner.init()

        overlayLifecycleOwner.lifecycleScope.launch {
            prefs.autoTranslate.collect { enabled ->
                autoTranslateState.value = enabled
            }
        }

        overlayLifecycleOwner.lifecycleScope.launch {
            DependencyProvider.viewModel(applicationContext).uiState.collect { state ->
                currentUiState.value = state
                if (state is TranslationUiState.ScreenBubbles) {
                    ensureBubbleLayer()
                    bubbleItems.value = state.items
                    mainHandler.removeCallbacks(packageChecker)
                    mainHandler.postDelayed(packageChecker, 1000)
                } else if (state !is TranslationUiState.Loading) {
                    // Loading 时保留旧译文层，避免每次点击都闪一下
                    bubbleItems.value = emptyList()
                    mainHandler.removeCallbacks(packageChecker)
                }
            }
        }
        
        composeView = ComposeView(this).apply {
            setContent {
                BubbleComposable(
                    uiState = currentUiState.value,
                    snapSide = snapSideState.value,
                    showSettings = showSettingsState.value,
                    autoTranslate = autoTranslateState.value,
                    onToggleAutoTranslate = {
                        val newVal = !autoTranslateState.value
                        autoTranslateState.value = newVal
                        overlayLifecycleOwner.lifecycleScope.launch { prefs.setAutoTranslate(newVal) }
                        if (!newVal) {
                            DependencyProvider.viewModel(applicationContext).clearScreenTranslation()
                        }
                    },
                    onTranslateNow = {
                        showSettingsState.value = false
                        TranslationAccessibilityService.instance?.disarmAutoInput()
                        val vm = DependencyProvider.viewModel(applicationContext)
                        val input = TranslationAccessibilityService.instance?.readFocusedInputText()
                        if (!input.isNullOrBlank()) vm.translateInput(silent = true)
                        vm.translateScreen()
                    },
                    onCloseSettings = { showSettingsState.value = false },
                    onDismiss = { DependencyProvider.viewModel(applicationContext).dismiss() },
                    onLanguageChange = { newLang ->
                        DependencyProvider.viewModel(applicationContext).setLanguage(newLang)
                    }
                )
            }
        }

        composeView.setViewTreeLifecycleOwner(overlayLifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(overlayLifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(overlayLifecycleOwner)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        composeView.setOnTouchListener { view, event ->
            // 面板或卡片显示时，把触摸整体交给 Compose，否则里面的开关/按钮点不动
            val s = currentUiState.value
            val isCard = s is TranslationUiState.Success ||
                s is TranslationUiState.Error ||
                s is TranslationUiState.ScreenTranslation ||
                s is TranslationUiState.LinkSafe ||
                s is TranslationUiState.LinkSuspicious ||
                s is TranslationUiState.LinkDangerous
            if (showSettingsState.value || isCard) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downAt = android.os.SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    params!!.x = initialX + dx.toInt()
                    params!!.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(composeView, params)
                    // 一拖就恢复全身
                    if (snapSideState.value != 0) snapSideState.value = 0
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) < 10 && abs(dy) < 10) {
                        snapSideState.value = 0
                        val vm = DependencyProvider.viewModel(applicationContext)
                        val held = android.os.SystemClock.uptimeMillis() - downAt
                        if (held > 450) {
                            // 长按悬浮球 = 手动关掉译文层
                            vm.clearScreenTranslation()
                            showSettingsState.value = false
                        } else {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastClickAt < 500) {
                                view.performClick()
                            } else {
                                lastClickAt = now
                                // 点一下 = 打开/收起设置小面板（自动翻译开关在这里）
                                showSettingsState.value = !showSettingsState.value
                            }
                        }
                        view.performClick()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(composeView, params)
        isRunning = true
    }

    /** 创建全屏透明译文层（不可触摸，点击穿透到下面的 App）。 */
    private fun ensureBubbleLayer() {
        if (bubbleLayer != null) return

        val view = ComposeView(this).apply {
            setContent {
                BubbleTranslationLayer(items = bubbleItems.value)
            }
        }
        view.setViewTreeLifecycleOwner(overlayLifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(overlayLifecycleOwner)
        view.setViewTreeViewModelStoreOwner(overlayLifecycleOwner)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(view, params)
        bubbleLayer = view

        // 译文层是后加的全屏窗口，会盖住悬浮球；把悬浮球重新提到最上层
        bringBallToFront()
    }

    /** 把悬浮球重新 add 一次，确保它在译文层之上。 */
    private fun bringBallToFront() {
        if (!::composeView.isInitialized) return
        val p = params ?: return
        runCatching {
            windowManager.removeView(composeView)
            windowManager.addView(composeView, p)
        }
    }

    /** 松手后吸附到左右的屏幕边缘；吸附后只露头。 */
    private fun snapToEdge() {
        val p = params ?: return
        val dm = resources.displayMetrics
        val left = (p.x + composeView.width / 2) < dm.widthPixels / 2
        snapSideState.value = if (left) -1 else 1

        composeView.post {
            val w = composeView.width
            p.x = if (left) 0 else (dm.widthPixels - w).coerceAtLeast(0)
            p.y = p.y.coerceIn(0, (dm.heightPixels - composeView.height).coerceAtLeast(0))
            runCatching { windowManager.updateViewLayout(composeView, p) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转屏幕 / 分屏变化：坐标全变了，重新扫描一次
        val vm = DependencyProvider.viewModel(applicationContext)
        if (vm.uiState.value is TranslationUiState.ScreenBubbles) {
            vm.refreshScreenBubbles()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        mainHandler.removeCallbacks(packageChecker)
        if (::composeView.isInitialized) {
            runCatching { windowManager.removeView(composeView) }
        }
        bubbleLayer?.let { layer -> runCatching { windowManager.removeView(layer) } }
        bubbleLayer = null
        overlayLifecycleOwner.destroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""

        if (text.isNotBlank()) {
            when (action) {
                ACTION_TRANSLATE -> {
                    DependencyProvider.viewModel(applicationContext).translate(text)
                }
                ACTION_CHECK_LINK -> {
                    DependencyProvider.viewModel(applicationContext).checkLink(text)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_TRANSLATE = "com.vipin25.bubbletranslate.ACTION_TRANSLATE"
        const val ACTION_CHECK_LINK = "com.vipin25.bubbletranslate.ACTION_CHECK_LINK"
        const val EXTRA_TEXT = "extra_text"

        /** 悬浮球服务是否在跑（供主界面显示「启动/停止」）。 */
        @Volatile
        var isRunning = false
            private set
    }
}
