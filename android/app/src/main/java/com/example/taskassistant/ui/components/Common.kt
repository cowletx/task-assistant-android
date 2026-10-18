package com.example.taskassistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.ui.StatusUi
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppGreenDark
import com.example.taskassistant.ui.theme.AppOutline
import com.example.taskassistant.ui.theme.AppSurface
import com.example.taskassistant.ui.theme.AppSurfaceVariant
import com.example.taskassistant.ui.theme.AppTextSecondary

/** 卡片：对应原型 .card（深色圆角 + 1px 边框）。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    borderColor: Color = AppOutline,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(AppSurface)
        .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    Column(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
fun CardSub(text: String, color: Color = AppTextSecondary) {
    Text(text = text, color = color, fontSize = 13.sp)
}

/** 状态标签（完成/跳过/进行中/未完成）。 */
@Composable
fun StatusChip(status: EntryStatus, modifier: Modifier = Modifier) {
    Text(
        text = StatusUi.label(status),
        color = Color(StatusUi.colorArgb(status)),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** 提前结束的红色标记，历史/详情复用。 */
@Composable
fun EarlyFinishTag(modifier: Modifier = Modifier) {
    Text(
        text = "提前结束",
        color = Color(0xFFE53935),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, color = Color(0xFFCCCCCC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

@Composable
fun EmptyHint(text: String, hint: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = Color(0xFF666666), fontSize = 14.sp)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = hint, color = Color(0xFF555555), fontSize = 12.sp)
        }
    }
}

@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    // 用 HorizontalDivider（material3 1.2+ 的正式 API），避免 Divider 的弃用告警
    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 1.dp, modifier = modifier)
}

/** 步骤勾选行：对应原型 .step-checkbox。 */
@Composable
fun StepCheckRow(
    name: String,
    detail: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) AppGreenDark else AppSurfaceVariant)
            .border(1.dp, if (checked) AppGreen else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = Color.White, fontSize = 14.sp)
            Text(text = detail, color = AppTextSecondary, fontSize = 12.sp)
        }
    }
}

/** 小色块，用于图例/进度点。 */
@Composable
fun Dot(color: Color, size: Int = 6) {
    Spacer(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp))
            .background(color)
    )
}

/** 统一给「设置样式的行」使用。 */
@Composable
fun SettingRow(label: String, value: String, valueColor: Color = AppTextSecondary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color(0xFFE0E0E0), fontSize = 15.sp)
        Text(text = value, color = valueColor, fontSize = 14.sp)
    }
    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 1.dp)
}

/** 供 MaterialTheme.typography 引用，避免未使用告警。 */
@Composable
fun cardTitleStyle() = MaterialTheme.typography.titleMedium
