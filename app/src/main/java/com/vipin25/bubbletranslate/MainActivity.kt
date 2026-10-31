package com.vipin25.bubbletranslate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vipin25.bubbletranslate.data.OpenRouterModel
import com.vipin25.bubbletranslate.data.OpenRouterModelsRepository
import com.vipin25.bubbletranslate.data.PrefsDataStore
import com.vipin25.bubbletranslate.data.TranslationCache
import com.vipin25.bubbletranslate.data.UiLanguage
import com.vipin25.bubbletranslate.service.BubbleOverlayService
import com.vipin25.bubbletranslate.service.TranslationAccessibilityService
import com.vipin25.bubbletranslate.ui.Strings
import com.vipin25.bubbletranslate.ui.UiStrings
import com.vipin25.bubbletranslate.ui.theme.BubbleTranslateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BubbleTranslateTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }

    @Composable
    private fun SetupScreen() {
        val prefs = remember { PrefsDataStore(applicationContext) }
        val scope = rememberCoroutineScope()

        var apiKey by remember { mutableStateOf("") }
        var keyVisible by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf("") }
        var allowPaid by remember { mutableStateOf(false) }
        var nativeLang by remember { mutableStateOf(UiLanguage.ZH) }
        var models by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var showModelDialog by remember { mutableStateOf(false) }
        var showLangDialog by remember { mutableStateOf(false) }
        var accEnabled by remember { mutableStateOf(isAccessibilityEnabled()) }
        var accConnected by remember { mutableStateOf(TranslationAccessibilityService.instance != null) }
        var bubbleRunning by remember { mutableStateOf(BubbleOverlayService.isRunning) }
        var autoTranslate by remember { mutableStateOf(false) }
        var showOnboarding by remember { mutableStateOf(false) }
        var cacheCount by remember { mutableStateOf(0) }
        var ballScale by remember { mutableStateOf(1f) }

        val s: UiStrings = remember(nativeLang) { Strings.of(nativeLang) }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    accEnabled = isAccessibilityEnabled()
                    accConnected = TranslationAccessibilityService.instance != null
                    bubbleRunning = BubbleOverlayService.isRunning
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        suspend fun loadModels(force: Boolean) {
            loading = true
            error = null
            OpenRouterModelsRepository.fetch(force)
                .onSuccess { list ->
                    models = list
                    loading = false
                    if (model.isBlank()) {
                        val free = list.firstOrNull { it.id == OpenRouterModelsRepository.FREE_ROUTER_ID }
                            ?: list.firstOrNull { it.isFree }
                        if (free != null) {
                            model = free.id
                            prefs.setModel(free.id)
                        }
                    }
                }
                .onFailure {
                    error = it.message ?: "load failed"
                    loading = false
                }
        }

        LaunchedEffect(Unit) {
            apiKey = prefs.apiKey.first()
            model = prefs.model.first()
            allowPaid = prefs.allowPaidModels.first()
            nativeLang = UiLanguage.fromTag(prefs.nativeLanguage.first())
            autoTranslate = prefs.autoTranslate.first()
            showOnboarding = !prefs.onboardingDone.first()
            TranslationCache.init(applicationContext)
            cacheCount = TranslationCache.size()
            ballScale = prefs.ballScale.first()
            loadModels(force = false)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(s.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                s.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── API Key（默认隐藏） ──
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(s.apiKeyLabel) },
                placeholder = { Text(s.apiKeyPlaceholder) },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(if (keyVisible) "🙈" else "👁")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            // ── 模型 ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.modelLabel, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.ifBlank { s.modelNotChosen },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        val picked = models.firstOrNull { it.id == model }
                        Text(
                            text = picked?.let { "${it.priceLabel()} · ${it.ctxLabel()}" }
                                ?: if (loading) s.modelLoading else s.modelHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (picked?.isFree == true) Color(0xFF2E7D32)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { showModelDialog = true }) { Text(s.chooseModel) }
                }
            }

            // ── 无障碍状态 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.accessibilityTitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (accEnabled) s.accessibilityOn else s.accessibilityOff,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (accEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                    if (accEnabled && !accConnected) {
                        Text(
                            s.accNotConnected,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(if (accEnabled) s.goView else s.goEnable) }
            }

            // ── 允许付费 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.allowPaidTitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.allowPaidDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = allowPaid,
                    onCheckedChange = {
                        allowPaid = it
                        scope.launch { prefs.setAllowPaidModels(it) }
                    }
                )
            }

            // ── 自动跟随 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.autoFollowTitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.autoFollowDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoTranslate,
                    onCheckedChange = {
                        autoTranslate = it
                        scope.launch { prefs.setAutoTranslate(it) }
                    }
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        prefs.setApiKey(apiKey)
                        prefs.setModel(model)
                        Toast.makeText(this@MainActivity, s.saved, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(s.save) }

            Button(
                onClick = {
                    if (bubbleRunning) {
                        stopService(Intent(this@MainActivity, BubbleOverlayService::class.java))
                        bubbleRunning = false
                    } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } else {
                        startForegroundService(
                            Intent(this@MainActivity, BubbleOverlayService::class.java)
                        )
                        bubbleRunning = true
                        Toast.makeText(this@MainActivity, s.bubbleStarted, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (bubbleRunning) s.stopBubble else s.startBubble) }

            // ── 底部：母语切换 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.nativeLanguage, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        nativeLang.nativeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Button(onClick = { showLangDialog = true }) { Text(s.switchLanguage) }
            }

            Text(
                s.accessibilityHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 悬浮球大小 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = s.ballSize,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                listOf(
                    0.8f to s.small,
                    1.0f to s.medium,
                    1.2f to s.large
                ).forEach { (value, label) ->
                    TextButton(onClick = {
                        ballScale = value
                        scope.launch { prefs.setBallScale(value) }
                    }) {
                        Text(
                            text = label,
                            fontWeight = if (ballScale == value) FontWeight.Bold else FontWeight.Normal,
                            color = if (ballScale == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    TranslationCache.clear()
                    cacheCount = 0
                    Toast.makeText(this@MainActivity, s.cacheCleared, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("${s.clearCache}（$cacheCount）") }
        }

        if (showModelDialog) {
            ModelPickerDialog(
                s = s,
                models = models,
                loading = loading,
                error = error,
                allowPaid = allowPaid,
                current = model,
                onRefresh = { scope.launch { loadModels(force = true) } },
                onPick = { m ->
                    model = m.id
                    scope.launch { prefs.setModel(m.id) }
                    showModelDialog = false
                },
                onDismiss = { showModelDialog = false }
            )
        }

        if (showLangDialog) {
            AlertDialog(
                onDismissRequest = { showLangDialog = false },
                title = { Text(s.pickLanguageTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        UiLanguage.entries.forEach { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        nativeLang = lang
                                        scope.launch { prefs.setNativeLanguage(lang.tag) }
                                        showLangDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.nativeName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                if (lang == nativeLang) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLangDialog = false }) { Text(s.close) }
                }
            )
        }

        if (showOnboarding) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(s.welcomeTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(s.welcomeDesc)
                        OnboardStep("1", s.stepKey, apiKey.isNotBlank())
                        OnboardStep("2", s.stepAccessibility, accEnabled && accConnected)
                        OnboardStep("3", s.stepBubble, bubbleRunning)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showOnboarding = false
                        scope.launch { prefs.setOnboardingDone(true) }
                    }) { Text(s.gotIt) }
                }
            )
        }
    }

    @Composable
    private fun OnboardStep(index: String, text: String, done: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (done) "✓" else "○",
                color = if (done) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "  $index. $text",
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }

    @Composable
    private fun ModelPickerDialog(
        s: UiStrings,
        models: List<OpenRouterModel>,
        loading: Boolean,
        error: String?,
        allowPaid: Boolean,
        current: String,
        onRefresh: () -> Unit,
        onPick: (OpenRouterModel) -> Unit,
        onDismiss: () -> Unit
    ) {
        var query by remember { mutableStateOf("") }

        val filtered = remember(models, query, allowPaid) {
            models.filter { m ->
                (allowPaid || m.isFree) &&
                    (query.isBlank() ||
                        m.id.contains(query, ignoreCase = true) ||
                        m.name.contains(query, ignoreCase = true))
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(s.chooseModel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(s.searchModel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (allowPaid) s.allModels else s.onlyFree,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }

                        error != null -> Text(
                            "${s.loadFailed}：$error",
                            color = MaterialTheme.colorScheme.error
                        )

                        filtered.isEmpty() -> Text(s.noMatch)

                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(filtered, key = { it.id }) { m ->
                                ModelRow(
                                    model = m,
                                    selected = m.id == current,
                                    onClick = { onPick(m) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onRefresh) { Text(s.refresh) }
                TextButton(onClick = onDismiss) { Text(s.close) }
            }
        )
    }

    @Composable
    private fun ModelRow(model: OpenRouterModel, selected: Boolean, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${model.priceLabel()} · ${model.ctxLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (model.isFree) Color(0xFF2E7D32)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "selected")
            }
        }
    }
}
