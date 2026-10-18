package com.example.taskassistant.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.core.totalSetsOf
import com.example.taskassistant.data.DayEntry
import com.example.taskassistant.ui.DateUi
import com.example.taskassistant.ui.MainViewModel
import com.example.taskassistant.ui.Screen
import com.example.taskassistant.ui.UiState
import com.example.taskassistant.ui.components.AppCard
import com.example.taskassistant.ui.components.CardSub
import com.example.taskassistant.ui.components.CardTitle
import com.example.taskassistant.ui.components.EmptyHint
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppOrange
import com.example.taskassistant.ui.theme.AppRed
import com.example.taskassistant.ui.theme.AppTextSecondary

/**
 * 首页：日期浏览 + 当天安排 + 训练入口 + 堆积任务入口。对应原型 page-home。
 *
 * 与原型的差异：原型顶部有「⚠ 日期切换仅供原型测试」提示并直接改 simulatedDate；
 * Android 端保留日期浏览（用户确实需要回看/预览其它日期），但不再需要那条警告。
 */
@Composable
fun HomeScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(
        currentTab = BottomTab.HOME,
        onTabSelected = { viewModel.navigate(it.toScreen()) },
    ) {
        DateControl(
            dateText = DateUi.full(state.viewDate),
            onPrev = { viewModel.changeViewDate(-1) },
            onNext = { viewModel.changeViewDate(1) },
            onToday = { viewModel.jumpToToday() },
        )

        Title("今日安排")

        if (state.dayEntries.isEmpty()) {
            EmptyHint("今天没有安排", "点下方按钮添加任务")
        } else {
            state.dayEntries.forEach { dayEntry -> HomeEntryCard(viewModel, dayEntry) }
        }

        Spacer(Modifier.height(12.dp))
        PrimaryButton(text = "＋ 添加任务") {
            viewModel.navigate(Screen.AddTask(state.viewDate))
        }

        if (state.backlogGroups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            val total = state.backlogGroups.sumOf { it.second.size }
            AppCard(borderColor = AppOrange) {
                Text(
                    text = "⚠️ 有 $total 个堆积任务",
                    color = AppOrange,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                CardSub("点击处理遗留任务")
                Spacer(Modifier.height(8.dp))
                WarningButton(text = "去处理", onClick = { viewModel.navigate(Screen.Backlog) })
            }
        }
    }
}

@Composable
private fun DateControl(
    dateText: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SecondaryButton(text = "← 前一天", onClick = onPrev)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = dateText,
                    color = AppGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "今天",
                    color = AppGreen,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { onToday() },
                )
            }
            SecondaryButton(text = "后一天 →", onClick = onNext)
        }
    }
}

@Composable
private fun HomeEntryCard(viewModel: MainViewModel, dayEntry: DayEntry) {
    val entry = dayEntry.entry
    val task = dayEntry.task
    val steps = entry.stepIndexes.mapNotNull { task.steps.getOrNull(it) }
    val totalSets = task.totalSetsOf(entry)
    val doneSets = entry.sets.size

    AppCard {
        CardTitle(task.name)
        CardSub("今日 ${steps.size} 个步骤 · 共 $totalSets 组")

        if (entry.status == EntryStatus.IN_PROGRESS) {
            Text(
                text = if (doneSets > 0) {
                    "进行中 · 已完成 $doneSets/$totalSets 组"
                } else {
                    "进行中 · 尚未开始第一组"
                },
                color = AppOrange,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (steps.isNotEmpty()) {
            Text(
                text = steps.joinToString(" · ") { "${it.name} ${it.sets}组×${it.reps}" },
                color = Color(0xFF666666),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        when (entry.status) {
            EntryStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "开始",
                    onClick = { viewModel.startWorkout(entry, task) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "跳过",
                    onClick = { viewModel.skipEntry(entry) },
                    modifier = Modifier.width(88.dp),
                )
            }

            EntryStatus.IN_PROGRESS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = "继续",
                    onClick = { viewModel.resumeWorkout(entry.id) },
                    modifier = Modifier.weight(1f),
                )
                WarningButton(
                    text = "完成",
                    onClick = { viewModel.markEntryCompleted(entry) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "跳过",
                    onClick = { viewModel.skipEntry(entry) },
                    modifier = Modifier.width(88.dp),
                )
            }

            EntryStatus.COMPLETED -> Text("✓ 已完成", color = AppGreen, fontSize = 13.sp)
            EntryStatus.INCOMPLETE -> Text("⚠ 未完成（提前结束）", color = AppRed, fontSize = 13.sp)
            EntryStatus.SKIPPED -> Text("已跳过", color = AppTextSecondary, fontSize = 13.sp)
        }
    }
}
