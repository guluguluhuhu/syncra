package com.vipin25.bubbletranslate.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipin25.bubbletranslate.domain.model.ScreenBubble

/**
 * 逐条贴合层：直接盖在原文上，而不是附加在下方。
 * 背景不透明（接近 X 气泡的浅灰），高度至少覆盖原文，视觉上就是「原文被翻成了中文」。
 * 整个层是 NOT_TOUCHABLE 的，点击穿透到下面的 App。
 */
@Composable
fun BubbleTranslationLayer(items: List<ScreenBubble>) {
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        items.forEach { item ->
            val x = with(density) { item.left.toDp() }
            val y = with(density) { item.top.toDp() }
            val originW = with(density) { (item.right - item.left).toDp() }
            val originH = with(density) { (item.bottom - item.top).toDp() }

            // 限制最大宽度，避免短消息被拉成长条；最小 120dp 保证能放下字
            val w = originW.coerceIn(120.dp, 300.dp)

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .width(w)
                    .heightIn(min = originH)
                    .background(Color(0xFFF7F7F8), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item.translation,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = Color.Black
                )
            }
        }
    }
}
