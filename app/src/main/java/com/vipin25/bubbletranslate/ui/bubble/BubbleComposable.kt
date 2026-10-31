package com.vipin25.bubbletranslate.ui.bubble

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipin25.bubbletranslate.R
import com.vipin25.bubbletranslate.domain.model.TranslationUiState

@Composable
fun BubbleComposable(
    uiState: TranslationUiState,
    snapSide: Int = 0,
    showSettings: Boolean = false,
    autoTranslate: Boolean = false,
    ballScale: Float = 1f,
    lockedTargetLang: String = "",
    onLockedTargetChange: (String) -> Unit = {},
    onTranslateInputNow: () -> Unit = {},
    onToggleAutoTranslate: () -> Unit = {},
    onTranslateNow: () -> Unit = {},
    onCloseSettings: () -> Unit = {},
    onDismiss: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    if (showSettings) {
        SettingsPanel(
            autoTranslate = autoTranslate,
            onToggle = onToggleAutoTranslate,
            onTranslateNow = onTranslateNow,
            onTranslateInputNow = onTranslateInputNow,
            lockedTargetLang = lockedTargetLang,
            onLockedTargetChange = onLockedTargetChange,
            onClose = onCloseSettings
        )
        return
    }

    val isOverlayCard = uiState is TranslationUiState.Success || uiState is TranslationUiState.Error
    val isLinkCard = uiState is TranslationUiState.LinkSafe || uiState is TranslationUiState.LinkSuspicious || uiState is TranslationUiState.LinkDangerous
    val isScreenCard = uiState is TranslationUiState.ScreenTranslation

    if (isOverlayCard || isLinkCard || isScreenCard) {
        if (isScreenCard) {
            ScreenTranslationCard(
                items = (uiState as TranslationUiState.ScreenTranslation).items,
                onDismiss = onDismiss
            )
        } else if (isOverlayCard) {
            val translated = if (uiState is TranslationUiState.Success) uiState.translation else (uiState as TranslationUiState.Error).message
            val original = if (uiState is TranslationUiState.Success) uiState.originalText else ""
            TranslationCard(
                originalText = original,
                translatedText = translated,
                onDismiss = onDismiss,
                onLanguageChange = onLanguageChange
            )
        } else {
            LinkShieldCard(uiState, onDismiss)
        }
    } else if (snapSide != 0) {
        // 吸附到屏幕边缘：只露出头，左侧镜像
        Image(
            painter = painterResource(R.drawable.mascot_peek),
            contentDescription = "DeepSeek whale girl",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size((96 * ballScale).dp)
                .alpha(0.92f)
                .graphicsLayer { scaleX = if (snapSide < 0) -1f else 1f }
        )
    } else {
        val infinite = rememberInfiniteTransition(label = "bob")
        val bob by infinite.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bobY"
        )
        val ballModifier = Modifier
            .size((112 * ballScale).dp)
            .offset(y = bob.dp)

        if (uiState is TranslationUiState.Loading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.mascot_thinking),
                    contentDescription = "DeepSeek whale girl",
                    contentScale = ContentScale.Fit,
                    modifier = ballModifier
                )
                if (uiState.preview.isNotBlank()) {
                    Text(
                        text = uiState.preview,
                        fontSize = 10.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(Color(0xCC1F2937), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        } else {
            val res = if (uiState is TranslationUiState.ScreenBubbles) {
                R.drawable.mascot_done
            } else {
                R.drawable.mascot_idle
            }
            Image(
                painter = painterResource(res),
                contentDescription = "DeepSeek whale girl",
                contentScale = ContentScale.Fit,
                modifier = ballModifier
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    autoTranslate: Boolean,
    onToggle: () -> Unit,
    onTranslateNow: () -> Unit,
    onTranslateInputNow: () -> Unit,
    lockedTargetLang: String,
    onLockedTargetChange: (String) -> Unit,
    onClose: () -> Unit
) {
    var langExpanded by remember { mutableStateOf(false) }

    androidx.compose.material3.Card(
        modifier = Modifier.width(200.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动翻译", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (autoTranslate) "开 · 实时跟随" else "关 · 休眠",
                        fontSize = 10.sp,
                        color = if (autoTranslate) Color(0xFF2E7D32) else Color.Gray
                    )
                }
                androidx.compose.material3.Switch(
                    checked = autoTranslate,
                    onCheckedChange = { onToggle() }
                )
            }

            androidx.compose.material3.Button(
                onClick = onTranslateNow,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                Text("立即翻译一次", fontSize = 12.sp)
            }

            // 目标语言下拉：锁死后“翻译输入框”就固定用它
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("目标语言", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box {
                    androidx.compose.material3.TextButton(onClick = { langExpanded = true }) {
                        Text(langLabel(lockedTargetLang), fontSize = 12.sp)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = langExpanded,
                        onDismissRequest = { langExpanded = false }
                    ) {
                        langOptions.forEach { (tag, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onLockedTargetChange(tag)
                                    langExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            androidx.compose.material3.Button(
                onClick = onTranslateInputNow,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                Text("翻译输入框", fontSize = 12.sp)
            }

            androidx.compose.material3.TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭", fontSize = 12.sp)
            }
        }
    }
}

private val langOptions = listOf(
    "" to "自动",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "ru" to "Русский",
    "ar" to "العربية",
    "zh" to "中文"
)

private fun langLabel(tag: String): String =
    langOptions.firstOrNull { it.first == tag }?.second ?: "自动"

@Composable
fun ShieldScanningIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Scanning",
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(32.dp)
        )
    }
}
