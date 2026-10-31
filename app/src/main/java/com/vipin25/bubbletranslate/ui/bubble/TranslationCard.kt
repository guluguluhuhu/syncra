package com.vipin25.bubbletranslate.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipin25.bubbletranslate.data.LanguageDetector
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class)
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("translation", text))
}

@Composable
fun TranslationCard(
    originalText: String,
    translatedText: String,
    onDismiss: () -> Unit,
    onLanguageChange: (String) -> Unit
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .width(300.dp)
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "对方语言",
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                        .clickable { showBottomSheet = !showBottomSheet }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { copyToClipboard(context, translatedText) },
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text("复制", fontSize = 12.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }
            
            if (showBottomSheet) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("选择对方语言", style = MaterialTheme.typography.titleMedium)
                val languages = listOf(
                    LanguageDetector.EN to "English",
                    LanguageDetector.ZH to "中文",
                    LanguageDetector.JA to "日本語",
                    LanguageDetector.KO to "한국어"
                )
                languages.forEach { (code, name) ->
                    Text(
                        text = name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLanguageChange(code)
                                showBottomSheet = false
                            }
                            .padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                Text(
                    text = "原文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

                Text(
                    text = "译文",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = translatedText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
