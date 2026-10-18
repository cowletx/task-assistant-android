package com.example.taskassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.core.RepeatMode
import com.example.taskassistant.core.totalSets
import com.example.taskassistant.data.DayEntry
import com.example.taskassistant.ui.DateUi
import com.example.taskassistant.ui.MainViewModel
import com.example.taskassistant.ui.Screen
import com.example.taskassistant.ui.UiState
import com.example.taskassistant.ui.components.AppCard
import com.example.taskassistant.ui.components.CardSub
import com.example.taskassistant.ui.components.CardTitle
import com.example.taskassistant.ui.components.EmptyHint
import com.example.taskassistant.ui.components.SectionHeader
import com.example.taskassistant.ui.components.StatusChip
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppOutline
import com.example.taskassistant.ui.theme.AppTextSecondary

/**
 * 计划页：任务列表 + 周视图。对应原型 page-plans。
 *
 * 周视图的数据直接来自 Room 的日程表（不是临时算出来的），
 * 因此首页、周视图、历史三处看到的状态必然一致。
 */
@Composable
fun PlansScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(
        currentTab = BottomTab.PLANS,
        onTabSelected = { viewModel.navigate(it.toScreen()) },
    ) {
        Title("任务")

        if (state.tasks.isEmpty()) {
            EmptyHint("还没有任务", "点下方按钮新建")
        } else {
            state.tasks.forEach { task ->
                val summary = task.steps.joinToString(" · ") { "${it.name} ${it.sets}×${it.reps}" }
                AppCard(onClick = { viewModel.navigate(Screen.PlanDetail(task.id)) }) {
                    CardTitle(task.name)
                    CardSub("${task.steps.size} 个步骤 · 共 ${task.totalSets()} 组")
                    if (summary.isNotBlank()) {
                        Text(text = summary, color = Color(0xFF666666), fontSize = 12.sp)
                    }
                    Text(
                        text = repeatSummary(task.repeat, task.startDate.value, task.endDate?.value),
                        color = AppTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(text = "＋ 新建任务") { viewModel.navigate(Screen.PlanEdit(null)) }

        Spacer(Modifier.height(16.dp))
        DividerLine()

        SectionHeader(title = "本周安排") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(text = "←", onClick = { viewModel.changeWeek(-1) })
                SecondaryButton(text = "→", onClick = { viewModel.changeWeek(1) })
            }
        }

        Text(
            text = viewModel.weekRangeLabel(state.weekOffset),
            color = AppTextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )

        viewModel.weekDays(state.weekOffset).forEachIndexed { index, day ->
            WeekDayCard(
                dayLabel = weekdayLabel(index),
                date = day,
                isToday = day == state.viewDate,
                entries = state.weekEntries[day].orEmpty(),
                onClick = { viewModel.navigate(Screen.AddTask(day)) },
            )
        }
    }
}

@Composable
private fun WeekDayCard(
    dayLabel: String,
    date: String,
    isToday: Boolean,
    entries: List<DayEntry>,
    onClick: () -> Unit,
) {
    AppCard(
        borderColor = if (isToday) AppGreen else AppOutline,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$dayLabel${if (isToday) " · 今天" else ""}",
                color = if (isToday) AppGreen else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = DateUi.short(date), color = AppTextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        if (entries.isEmpty()) {
            Text(text = "无安排", color = Color(0xFF555555), fontSize = 12.sp)
        } else {
            entries.forEach { dayEntry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = dayEntry.task.name, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    StatusChip(dayEntry.entry.status)
                }
            }
        }
    }
}

/** 与原型 wd 数组一致：周一 ~ 周日。 */
private fun weekdayLabel(index: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[index.coerceIn(0, 6)]

/** 任务列表里的重复方式摘要。 */
private fun repeatSummary(repeat: RepeatMode, start: String, end: String?): String {
    val endText = end?.takeIf { it.isNotBlank() } ?: "永久"
    return when (repeat) {
        RepeatMode.ONCE -> "仅一次 · $start"
        RepeatMode.DAILY -> "每天 · $start ~ $endText"
        RepeatMode.WEEKLY -> "每周 · $start ~ $endText"
    }
}
