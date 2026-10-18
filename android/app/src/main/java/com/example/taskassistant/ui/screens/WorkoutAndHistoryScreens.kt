package com.example.taskassistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.core.HistoryStep
import com.example.taskassistant.core.WorkoutEngine
import com.example.taskassistant.ui.DateUi
import com.example.taskassistant.ui.MainViewModel
import com.example.taskassistant.ui.Screen
import com.example.taskassistant.ui.StatusUi
import com.example.taskassistant.ui.UiState
import com.example.taskassistant.ui.components.AppCard
import com.example.taskassistant.ui.components.CardSub
import com.example.taskassistant.ui.components.CardTitle
import com.example.taskassistant.ui.components.EmptyHint
import com.example.taskassistant.ui.components.EarlyFinishTag
import com.example.taskassistant.ui.components.SectionHeader
import com.example.taskassistant.ui.components.StatusChip
import com.example.taskassistant.ui.components.StepCheckRow
import com.example.taskassistant.ui.components.ThinDivider
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppOrange
import com.example.taskassistant.ui.theme.AppRed
import com.example.taskassistant.ui.theme.AppSurfaceVariant
import com.example.taskassistant.ui.theme.AppTextSecondary
import kotlinx.coroutines.delay

/**
 * 训练页。对应原型 page-workout。
 *
 * 与原型的关键差异（Bug 4 相关）：每一步都立即写入 Room，
 * 退出页面或应用被杀掉后，重新进入会从保存的指针/组记录继续，而不是从头开始。
 */
@Composable
fun WorkoutScreen(viewModel: MainViewModel, state: UiState) {
    val session = state.workout
    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        if (session == null) {
            EmptyHint("没有进行中的训练")
            PrimaryButton(text = "返回首页", onClick = { viewModel.navigate(Screen.Home) })
        } else {
            WorkoutBody(viewModel, state, session)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WorkoutBody(
    viewModel: MainViewModel,
    state: UiState,
    session: com.example.taskassistant.core.WorkoutSession,
) {
    val task = state.tasks.firstOrNull { it.id == session.taskId }

    // 休息倒计时：仅在剩余秒数 > 0 时运行
    LaunchedEffect(session.restSecondsLeft, session.entryId) {
        while (true) {
            delay(1000)
            viewModel.tickRest()
        }
    }

    if (task == null) {
        EmptyHint("任务不存在")
    } else if (session.isFinishedBySets) {
        Title("完成 🎉")
        AppCard {
            CardTitle(task.name)
            CardSub("已做完所有组")
        }
        PrimaryButton(text = "结束并保存", onClick = { viewModel.finishWorkout() })
    } else {
        val current = session.currentSlot
        val step = current?.let { task.steps.getOrNull(it.stepIndex) }
        if (current == null || step == null) {
            EmptyHint("没有可执行的组")
        } else {
            WorkoutActiveContent(viewModel, state, session, task, current, step)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WorkoutActiveContent(
    viewModel: MainViewModel,
    state: UiState,
    session: com.example.taskassistant.core.WorkoutSession,
    task: com.example.taskassistant.core.TaskDef,
    current: com.example.taskassistant.core.SetSlot,
    step: com.example.taskassistant.core.Step,
) {
    Title("训练中")

    // 进度条
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color(0xFF333333)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(session.progressPercent / 100f)
                .height(6.dp)
                .background(AppGreen),
        )
    }

    val nextText = session.sequence.getOrNull(session.pointer + 1)?.let { next ->
        val nextStep = task.steps.getOrNull(next.stepIndex)
        "${nextStep?.name ?: ""} 第${next.setIndex + 1}组"
    } ?: "（最后一组）"

    Text(
        text = "进度 ${session.pointer}/${session.sequence.size} · 下一项：$nextText",
        color = AppTextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )

    AppCard {
        Text(
            text = step.name,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "第 ${current.setIndex + 1}/${step.sets} 组",
            color = AppTextSecondary,
            fontSize = 16.sp,
        )
        Text(text = "目标 ${current.targetReps} 次", color = AppGreen, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.actualReps.toString(),
            color = when {
                state.actualReps < current.targetReps -> AppRed
                state.actualReps > current.targetReps -> AppGreen
                else -> Color.White
            },
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(text = "−", onClick = { viewModel.adjustActualReps(-1) })
            SecondaryButton(text = "＋", onClick = { viewModel.adjustActualReps(1) })
        }
    }

    if (session.setStarted) {
        Text(
            text = "本组已开始，点击「完成本组」记录",
            color = AppOrange,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
    }

    if (task.restEnabled) {
        AppCard(borderColor = if (session.restSecondsLeft > 0) AppGreen else AppTextSecondary) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (session.restSecondsLeft > 0) {
                    Text(text = "组间休息", color = Color(0xFFA5D6A7), fontSize = 13.sp)
                    Text(
                        text = "${session.restSecondsLeft}s",
                        color = AppGreen,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    SecondaryButton(text = "跳过", onClick = { viewModel.skipRest() })
                } else {
                    Text(
                        text = if (session.records.isEmpty()) {
                            "完成一组后开始倒计时"
                        } else {
                            "休息结束，开始下一组"
                        },
                        color = AppTextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = if (session.setStarted) "完成本组" else "开始本组",
            onClick = { viewModel.handleSetButton() },
            modifier = Modifier.weight(1f),
        )
        WarningButton(text = "跳过本组", onClick = { viewModel.skipSet() }, modifier = Modifier.weight(1f))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(text = "撤销", onClick = { viewModel.undoLastSet() }, modifier = Modifier.weight(1f))
        DangerButton(text = "结束训练", onClick = { viewModel.finishWorkout() }, modifier = Modifier.weight(1f))
    }

    Spacer(Modifier.height(16.dp))
    SectionHeader(title = "已完成")

    if (session.records.isEmpty()) {
        EmptyHint("暂无")
    } else {
        AppCard {
            session.records.forEachIndexed { index, record ->
                val recordStep = task.steps.getOrNull(record.stepIndex)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${recordStep?.name ?: ""} 第${record.setIndex + 1}组",
                        color = Color(0xFFCCCCCC),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (record.skipped) "跳过" else "${record.actualReps}/${record.targetReps}",
                        color = when {
                            record.skipped -> AppTextSecondary
                            record.actualReps >= record.targetReps -> AppGreen
                            else -> AppRed
                        },
                        fontSize = 14.sp,
                    )
                }
                if (index != session.records.lastIndex) ThinDivider()
            }
        }
    }
}

/** 历史列表。对应原型 page-history。 */
@Composable
fun HistoryScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(
        currentTab = BottomTab.HISTORY,
        onTabSelected = { viewModel.navigate(it.toScreen()) },
    ) {
        Title("历史记录")

        if (state.history.isEmpty()) {
            EmptyHint("暂无历史")
        } else {
            state.history.groupBy { it.record.date.value }.forEach { (date, records) ->
                Text(
                    text = DateUi.full(date),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                )
                records.forEach { detail ->
                    val record = detail.record
                    AppCard(onClick = { viewModel.navigate(Screen.HistoryDetail(record.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CardTitle(record.taskName)
                            if (!record.completed) {
                                Spacer(Modifier.width(8.dp))
                                EarlyFinishTag()
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        record.steps.forEach { step ->
                            HistoryStepRow(step)
                        }
                    }
                }
            }
        }
    }
}

/** 历史详情。对应原型 page-detail。 */
@Composable
fun HistoryDetailScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton { viewModel.goBack() }
        val detail = state.historyDetail
        if (detail == null) {
            EmptyHint("无")
        } else {
            val record = detail.record
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(record.taskName)
                if (!record.completed) {
                    Spacer(Modifier.width(8.dp))
                    EarlyFinishTag()
                }
            }
            CardSub(DateUi.full(record.date.value))

            record.steps.forEach { step ->
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        CardTitle(step.name)
                        Text(
                            text = "${WorkoutEngine.recordedSetsOf(step)}/${step.plannedSets}组",
                            color = AppTextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (step.actualReps.isEmpty()) {
                        Text(text = "未记录", color = AppTextSecondary, fontSize = 13.sp)
                    } else {
                        step.actualReps.forEachIndexed { index, reps ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = "第 ${index + 1} 组", color = Color(0xFFCCCCCC), fontSize = 14.sp)
                                Text(
                                    text = if (reps == 0) "跳过" else "$reps/${step.targetReps}",
                                    color = when {
                                        reps == 0 -> AppTextSecondary
                                        reps >= step.targetReps -> AppGreen
                                        else -> AppRed
                                    },
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryStepRow(step: HistoryStep) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${step.name} ${WorkoutEngine.recordedSetsOf(step)}/${step.plannedSets}组",
            color = AppTextSecondary,
            fontSize = 13.sp,
        )
        Text(
            text = if (step.actualReps.isEmpty()) {
                "未记录"
            } else {
                step.actualReps.joinToString(" · ") { reps ->
                    if (reps == 0) "跳过" else "$reps/${step.targetReps}"
                }
            },
            color = AppTextSecondary,
            fontSize = 13.sp,
        )
    }
}

/** 逾期（堆积）任务处理。对应原型 page-backlog。 */
@Composable
fun BacklogScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton("← 返回首页") { viewModel.goHome() }
        Title("堆积任务处理")

        if (state.backlogGroups.isEmpty()) {
            EmptyHint("没有堆积任务")
        } else {
            state.backlogGroups.forEach { (task, entries) ->
                BacklogTaskCard(
                    viewModel = viewModel,
                    task = task,
                    entries = entries,
                    selectingSteps = state.backlogStepSelectionTaskId == task.id,
                    selectedSteps = state.backlogSelectedSteps,
                )
            }
        }
    }
}

/**
 * 单个堆积任务卡片。
 *
 * 两阶段流程（与原型 backlogStep 一致）：
 *   第一阶段「做 / 跳过」→ 点「做」进入第二阶段；
 *   第二阶段「选择要保留的步骤」+「顺延 / 覆盖 / 一起做 / 返回」。
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.BacklogTaskCard(
    viewModel: MainViewModel,
    task: com.example.taskassistant.core.TaskDef,
    entries: List<com.example.taskassistant.core.ScheduleEntry>,
    selectingSteps: Boolean,
    selectedSteps: Map<Int, Boolean>,
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardTitle(task.name)
            Text(text = "${entries.size} 天未处理", color = AppOrange, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = DateUi.full(entry.date.value), color = AppTextSecondary, fontSize = 13.sp)
                Text(
                    text = if (entry.status == EntryStatus.IN_PROGRESS) "进行中" else "未开始",
                    color = AppTextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        if (!selectingSteps) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton(
                    text = "做",
                    onClick = { viewModel.backlogDo(task) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "跳过",
                    onClick = { viewModel.backlogSkip(task, entries) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            ThinDivider()
            CardSub("选择要保留的步骤（不勾的会被去掉）：")
            Spacer(Modifier.height(6.dp))
            task.steps.forEachIndexed { index, step ->
                StepCheckRow(
                    name = step.name.ifBlank { "（未命名）" },
                    detail = "${step.sets} 组 × ${step.reps}",
                    checked = selectedSteps[index] ?: false,
                    onToggle = { viewModel.toggleBacklogStep(index) },
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(
                    text = "全选",
                    onClick = { viewModel.selectAllBacklogSteps(task, true) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "全不选",
                    onClick = { viewModel.selectAllBacklogSteps(task, false) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton(
                    text = "顺延",
                    onClick = { viewModel.backlogAction(task, entries, "顺延") },
                    modifier = Modifier.weight(1f),
                )
                WarningButton(
                    text = "覆盖",
                    onClick = { viewModel.backlogAction(task, entries, "覆盖") },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(
                    text = "一起做",
                    onClick = { viewModel.backlogAction(task, entries, "一起做") },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "返回",
                    onClick = { viewModel.backlogReset() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 设置页。对应原型 page-settings，但开关是真实生效的。 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    state: UiState,
    onExportRequest: () -> Unit,
) {
    ScreenScaffold(
        currentTab = BottomTab.SETTINGS,
        onTabSelected = { viewModel.navigate(it.toScreen()) },
    ) {
        Title("设置")

        AppCard {
            CardTitle("通知")
            CardSub(
                if (state.notificationsAllowed) {
                    "通知权限：已授权"
                } else {
                    "通知权限：未授权（训练时只在应用内显示倒计时）"
                }
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = "常驻通知",
                checked = state.settings.persistentNotification,
                onCheckedChange = { viewModel.setPersistentNotification(it) },
            )
            ToggleRow(
                label = "震动提醒",
                checked = state.settings.vibrateOnRestEnd,
                onCheckedChange = { viewModel.setVibrateOnRestEnd(it) },
            )
            if (state.workoutServiceRunning) {
                CardSub("训练通知正在运行")
            }
        }

        AppCard {
            CardTitle("数据")
            CardSub("存储方式：本地数据库（Room），不上传、不需要账号")
            CardSub("任务 ${state.tasks.size} 个 · 待处理堆积 ${state.backlogGroups.sumOf { it.second.size }} 条")
            Spacer(Modifier.height(8.dp))
            PrimaryButton(text = "导出 JSON", onClick = onExportRequest)
            Spacer(Modifier.height(6.dp))
            SecondaryButton(text = "重置生成游标", onClick = { viewModel.resetCursors() })
            Spacer(Modifier.height(6.dp))
            CardSub("导出会写入你选择的位置；重置游标不会删除任何数据，只让日程重新计算一遍。")
        }

        AppCard {
            CardTitle("关于")
            CardSub("任务助手 · Android 原生版 v1.0")
            CardSub("最低支持 Android 8.0（API 26）")
            Spacer(Modifier.height(6.dp))
            CardSub("状态色：绿=完成，橙=进行中，红=跳过/未完成（提前结束）")
            Spacer(Modifier.height(6.dp))
            StatusLegend()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = Color(0xFFE0E0E0), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 状态色图例（设置页用）。 */
@Composable
private fun StatusLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusChip(EntryStatus.COMPLETED)
        StatusChip(EntryStatus.IN_PROGRESS)
        StatusChip(EntryStatus.SKIPPED)
        StatusChip(EntryStatus.INCOMPLETE)
    }
}
