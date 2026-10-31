package com.vipin25.bubbletranslate.ui.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vipin25.bubbletranslate.domain.model.ScreenBubble

/**
 * 逐条贴合层：直接盖在原文上，而不是附加在下方。
 * 背景不透明（接近 X 气泡的浅灰），高度至少覆盖原文，视觉上就是「原文被翻成了中文」。
 * 整个层是 NOT_TOUCHABLE 的，点击穿透到下面的 App。
 */
@Composable
fun BubbleTranslationLayer(items: List<ScreenBubble>, limitBottomPx: Int = Int.MAX_VALUE) {
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        items.forEach { item ->
            val x = with(density) { item.left.toDp() }
            val originW = with(density) { (item.right - item.left).toDp() }
            val originH = with(density) { (item.bottom - item.top).toDp() }

            // 往上提：保证译文框底部不越过 limitBottomPx（输入框上沿），顶部也至少留出一点
            val yPx = if (limitBottomPx == Int.MAX_VALUE) {
                item.top
            } else {
                minOf(item.top, limitBottomPx - 80)
            }
            val y = with(density) { yPx.toDp() }

            // 高度上限：到 limitBottomPx 为止，超出部分裁掉（宁可截断也不挡输入框）
            val maxHDp = if (limitBottomPx == Int.MAX_VALUE) {
                Dp.Unspecified
            } else {
                with(density) { (limitBottomPx - yPx).coerceAtLeast(24).toDp() }
            }

            // 限制最大宽度，避免短消息被拉成长条；最小 120dp 保证能放下字
            val w = originW.coerceIn(120.dp, 300.dp)

            Box(
                modifier = Modifier
                    .offset(x = x, y = y)
                    .width(w)
                    .then(
                        if (maxHDp == Dp.Unspecified) {
                            Modifier.heightIn(min = originH)
                        } else {
                            Modifier.heightIn(min = minOf(originH, maxHDp), max = maxHDp)
                        }
                    )
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item.translation,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
