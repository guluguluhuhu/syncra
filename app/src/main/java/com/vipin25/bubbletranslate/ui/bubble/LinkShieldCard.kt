package com.vipin25.bubbletranslate.ui.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipin25.bubbletranslate.domain.model.RiskSignal
import com.vipin25.bubbletranslate.domain.model.TranslationUiState
import com.vipin25.bubbletranslate.domain.model.Weight

@Composable
fun LinkShieldCard(
    uiState: TranslationUiState,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(300.dp)
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            
            when (uiState) {
                is TranslationUiState.LinkSafe -> SafeState(uiState)
                is TranslationUiState.LinkSuspicious -> SuspiciousState(uiState)
                is TranslationUiState.LinkDangerous -> PhishingState(uiState)
                else -> {}
            }
        }
    }
}

@Composable
private fun SafeState(state: TranslationUiState.LinkSafe) {
    val context = LocalContext.current
    val domain = try { Uri.parse(state.url).host ?: state.url } catch (e: Exception) { state.url }

    Icon(Icons.Default.CheckCircle, contentDescription = "Safe", tint = Color(0xFF4CAF50), modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(8.dp))
    BadgeLabel("SAFE", Color(0xFF4CAF50))
    Spacer(modifier = Modifier.height(4.dp))
    Text(domain, color = Color.Gray, fontSize = 14.sp)
    
    Spacer(modifier = Modifier.height(16.dp))
    
    LazyColumn() {
        items(state.reasons) { reason ->
            Text("• $reason", fontSize = 14.sp, color = Color.DarkGray)
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { openUrl(context, state.url) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
        ) {
            Text("Open Link")
        }
        OutlinedButton(onClick = { copyUrl(context, state.url) }) {
            Text("Copy URL")
        }
    }
}

@Composable
private fun SuspiciousState(state: TranslationUiState.LinkSuspicious) {
    val context = LocalContext.current
    val domain = try { Uri.parse(state.url).host ?: state.url } catch (e: Exception) { state.url }

    Icon(Icons.Default.Warning, contentDescription = "Suspicious", tint = Color(0xFFFF9800), modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(8.dp))
    BadgeLabel("SUSPICIOUS", Color(0xFFFF9800))
    Spacer(modifier = Modifier.height(4.dp))
    Text(domain, color = Color.Gray, fontSize = 14.sp)
    
    Spacer(modifier = Modifier.height(16.dp))
    
    LazyColumn() {
        items(state.signals) { signal ->
            SignalRow(signal)
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { openUrl(context, state.url) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9800))
        ) {
            Text("Open anyway")
        }
        OutlinedButton(onClick = { copyUrl(context, state.url) }) {
            Text("Copy URL")
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text("Proceed with caution", fontSize = 12.sp, color = Color.Gray)
}

@Composable
private fun PhishingState(state: TranslationUiState.LinkDangerous) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Icon(Icons.Default.Close, contentDescription = "Phishing", tint = Color.Red, modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(8.dp))
    BadgeLabel("PHISHING", Color.Red)
    Spacer(modifier = Modifier.height(4.dp))
    Text(state.url, color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    
    Spacer(modifier = Modifier.height(16.dp))
    
    LazyColumn() {
        items(state.signals) { signal ->
            SignalRow(signal)
        }
    }
    
    Spacer(modifier = Modifier.height(16.dp))
    
    Button(
        onClick = { /* Does not open, just block. But user has option to open with second confirmation? Wait, the task says 'NO open button', but it has an alert dialog. Let's make the text clickable or copyable? No, task says: NO 'Open' button. Navigation to URL requires second confirmation. Let's make the red button say Block, and add a text/link or hold action? Wait, 'NO Open button. Navigation requires second confirmation. Confirmation: AlertDialog ...' How does the use trigger the dialog? Maybe if they click the URL? Ah, we can make the URL clickable to trigger the dialog.*/ },
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Block — Do not open")
    }

    TextButton(onClick = { showDialog = true }) {
        Text("Open anyway", color = Color.Gray, fontSize = 12.sp)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Are you sure?") },
            text = { Text("This link was flagged.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        openUrl(context, state.url)
                    }
                ) {
                    Text("Open anyway", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SignalRow(signal: RiskSignal) {
    val dotColor = when (signal.weight) {
        Weight.HIGH -> Color.Red
        Weight.MEDIUM -> Color(0xFFFF9800)
        Weight.LOW -> Color.Blue
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(signal.type.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.DarkGray)
            Text(signal.detail, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun BadgeLabel(text: String, bgColor: Color) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
    }
}

private fun copyUrl(context: Context, url: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { }
}
