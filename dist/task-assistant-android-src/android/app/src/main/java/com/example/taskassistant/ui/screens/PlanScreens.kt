package com.example.taskassistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.taskassistant.core.RepeatMode
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
import com.example.taskassistant.ui.components.StepCheckRow
import com.example.taskassistant.ui.components.ThinDivider
import com.example.taskassistant.ui.theme.AppGreen
import com.example.taskassistant.ui.theme.AppTextSecondary

/** 简单的日期选择行：用「前一天/后一天」与文本展示，避免引入额外的选择器依赖。 */
@Composable
private fun DateRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = AppTextSecondary, fontSize = 14.sp, modifier = Modifier.width(72.dp))
        SecondaryButton(text = "−1天", onClick = { onChange(shiftDate(value, -1)) })
        Text(
            text = if (value.isBlank()) "（未设置）" else DateUi.full(value),
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        SecondaryButton(text = "+1天", onClick = { onChange(shiftDate(value, 1)) })
    }
}

private fun shiftDate(value: String, delta: Long): String {
    val base = if (value.isBlank()) java.time.LocalDate.now() else java.time.LocalDate.parse(value)
    return base.plusDays(delta).toString()
}

/** 新建/编辑任务页。对应原型 page-plan-edit。 */
@Composable
fun PlanEditScreen(viewModel: MainViewModel, state: UiState) {
    val draft = state.planDraft
    if (draft == null) {
        ScreenScaffold(currentTab = null, onTabSelected = {}) {
            BackButton { viewModel.goBack() }
            EmptyHint("没有正在编辑的任务")
        }
        return
    }
    val isNew = state.tasks.none { it.id == draft.task.id }

    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton { viewModel.goBack() }
        Title(if (isNew) "新建任务" else "编辑任务")

        AppCard {
            OutlinedTextField(
                value = draft.task.name,
                onValueChange = { value -> viewModel.updateDraft { it.copy(task = it.task.copy(name = value)) } },
                label = { Text("任务名称") },
                placeholder = { Text("如：健身、英语、读书") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(text = "顺序", color = AppTextSecondary, fontSize = 14.sp)
            OrderTypeSelector(
                current = draft.task.orderType,
                onChange = { order -> viewModel.updateDraft { it.copy(task = it.task.copy(orderType = order)) } },
            )
        }

        SectionHeader(title = "步骤池") {
            SecondaryButton(text = "＋ 添加步骤", onClick = { viewModel.addDraftStep() })
        }

        draft.task.steps.forEachIndexed { index, step ->
            AppCard {
                Text(text = "步骤 ${index + 1}", color = AppGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = step.name,
                    onValueChange = { value -> viewModel.updateDraftStep(index) { it.copy(name = value) } },
                    label = { Text("名称") },
                    placeholder = { Text("如：俯卧撑") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = step.sets.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { n -> viewModel.updateDraftStep(index) { it.copy(sets = n) } }
                        },
                        label = { Text("组数") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = step.reps.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { n -> viewModel.updateDraftStep(index) { it.copy(reps = n) } }
                        },
                        label = { Text("目标次数") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    DangerButton(text = "删除", onClick = { viewModel.removeDraftStep(index) })
                }
            }
        }

        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "组间计时", color = Color(0xFFE0E0E0), fontSize = 15.sp)
                Switch(
                    checked = draft.task.restEnabled,
                    onCheckedChange = { on ->
                        viewModel.updateDraft { it.copy(task = it.task.copy(restEnabled = on)) }
                    },
                )
            }
            if (draft.task.restEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.task.restSeconds.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { n ->
                            viewModel.updateDraft { it.copy(task = it.task.copy(restSeconds = n)) }
                        }
                    },
                    label = { Text("休息秒数") },
                    modifier = Modifier.fillMaxWidth(),
                )
                CardSub("完成一组后自动开始倒计时，但不会阻止你继续操作。")
            }
        }

        if (isNew) {
            AppCard {
                CardTitle("重复安排")
                RepeatSelector(
                    current = draft.repeat,
                    onChange = { mode -> viewModel.updateDraft { it.copy(repeat = mode) } },
                )
                Spacer(Modifier.height(8.dp))
                when (draft.repeat) {
                    RepeatMode.ONCE -> {
                        DateRow("日期", draft.onceDate) { value -> viewModel.updateDraft { it.copy(onceDate = value) } }
                        CardSub("只在选定这一天安排一次。")
                    }
                    RepeatMode.DAILY -> {
                        DateRow("开始", draft.dailyStart) { value -> viewModel.updateDraft { it.copy(dailyStart = value) } }
                        DateRow("结束", draft.dailyEnd) { value -> viewModel.updateDraft { it.copy(dailyEnd = value) } }
                        CardSub("结束日期留空表示永久每天重复。")
                    }
                    RepeatMode.WEEKLY -> {
                        DateRow("开始", draft.weeklyStart) { value -> viewModel.updateDraft { it.copy(weeklyStart = value) } }
                        DateRow("结束", draft.weeklyEnd) { value -> viewModel.updateDraft { it.copy(weeklyEnd = value) } }
                        CardSub("点「下一步」后进入「每周步骤分配」，为周一到周日分别勾选要做哪几个步骤。")
                    }
                }
            }
        } else {
            CardSub("编辑不会重置已有的训练记录；结束日改早只会清理今天之后还没动过的待做安排。")
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = if (isNew && draft.repeat == RepeatMode.WEEKLY) "下一步" else "保存",
        ) {
            viewModel.savePlan { message -> viewModel.showToast(message) }
        }
    }
}

@Composable
private fun OrderTypeSelector(
    current: com.example.taskassistant.core.OrderType,
    onChange: (com.example.taskassistant.core.OrderType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (current == com.example.taskassistant.core.OrderType.ROTATE) {
        "轮换式（A1-B1-C1-A2...）"
    } else {
        "传统式（A1-A2-A3-B1...）"
    }
    Column {
        SecondaryButton(text = label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            com.example.taskassistant.core.OrderType.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option == com.example.taskassistant.core.OrderType.ROTATE) {
                                "轮换式（A1-B1-C1-A2...）"
                            } else {
                                "传统式（A1-A2-A3-B1...）"
                            }
                        )
                    },
                    onClick = { onChange(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun RepeatSelector(current: RepeatMode, onChange: (RepeatMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (current) {
        RepeatMode.ONCE -> "仅一次"
        RepeatMode.DAILY -> "每天"
        RepeatMode.WEEKLY -> "每周（按星期分配）"
    }
    Column {
        Text(text = "重复", color = AppTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        SecondaryButton(text = label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RepeatMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                RepeatMode.ONCE -> "仅一次"
                                RepeatMode.DAILY -> "每天"
                                RepeatMode.WEEKLY -> "每周（按星期分配）"
                            }
                        )
                    },
                    onClick = { onChange(option); expanded = false },
                )
            }
        }
    }
}

/** 任务详情页。对应原型 page-plan-detail。 */
@Composable
fun PlanDetailScreen(viewModel: MainViewModel, state: UiState, taskId: String) {
    val task = state.planDetailTask ?: state.tasks.firstOrNull { it.id == taskId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton { viewModel.goBack() }
        if (task == null) {
            EmptyHint("任务不存在")
        } else {
            PlanDetailBody(
                viewModel = viewModel,
                task = task,
                onRequestDelete = { showDeleteConfirm = true },
            )
        }
    }

    // 删除是不可撤销操作，按原型语义先确认
    if (showDeleteConfirm && task != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除任务") },
            text = { Text("删除这个任务？已安排到日历上的该任务也会被移除，历史记录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deletePlan(task.id)
                }) { Text("删除", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PlanDetailBody(
    viewModel: MainViewModel,
    task: com.example.taskassistant.core.TaskDef,
    onRequestDelete: () -> Unit,
) {
    Title(task.name)

    AppCard {
        SettingLine(
            "顺序模式",
            if (task.orderType == com.example.taskassistant.core.OrderType.ROTATE) {
                "轮换式（A1-B1-C1-A2...）"
            } else {
                "传统式（A1-A2-A3-B1...）"
            },
        )
        ThinDivider()
        SettingLine("步骤数", "${task.steps.size} 个")
        ThinDivider()
        SettingLine("总组数", "${task.steps.sumOf { it.sets }} 组")
        ThinDivider()
        SettingLine("重复", repeatSummary(task.repeat, task.startDate.value, task.endDate?.value))
        ThinDivider()
        SettingLine("组间计时", if (task.restEnabled) "开启（${task.restSeconds} 秒）" else "关闭")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = "编辑任务",
            onClick = { viewModel.navigate(Screen.PlanEdit(task.id)) },
            modifier = Modifier.weight(1f),
        )
        DangerButton(text = "删除", onClick = { onRequestDelete() })
    }

    Spacer(Modifier.height(16.dp))
    SectionHeader(title = "步骤列表")

    task.steps.forEachIndexed { index, step ->
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    CardTitle("${index + 1}. ${step.name}")
                    CardSub("${step.sets} 组 × ${step.reps}")
                }
                StatusChip(com.example.taskassistant.core.EntryStatus.PENDING)
            }
        }
    }
}

@Composable
private fun SettingLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = AppTextSecondary, fontSize = 14.sp)
        Text(text = value, color = Color(0xFFE0E0E0), fontSize = 14.sp)
    }
}

/** 每周步骤分配页。对应原型 page-week-assign。 */
@Composable
fun WeekAssignScreen(viewModel: MainViewModel, state: UiState) {
    val draft = state.weekAssign
    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton { viewModel.goBack() }
        if (draft == null) {
            EmptyHint("没有正在分配的任务")
        } else {
            WeekAssignBody(viewModel, draft)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WeekAssignBody(
    viewModel: MainViewModel,
    draft: com.example.taskassistant.ui.WeekAssignDraft,
) {
    Title("每周步骤分配")
    CardSub("为每个星期几勾选要做哪几个步骤。上方切换星期，设置完直接保存。")

        Spacer(Modifier.height(10.dp))

        // 星期切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..7).forEach { day ->
                val selected = draft.currentDay == day
                val hasContent = draft.pattern[day].orEmpty().isNotEmpty()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SecondaryButton(
                        text = weekdayLabel(day - 1).removePrefix("周"),
                        onClick = { viewModel.selectWeekAssignDay(day) },
                    )
                    if (hasContent) {
                        Text("●", color = if (selected) Color.White else AppGreen, fontSize = 10.sp)
                    } else {
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        AppCard {
            SectionHeader(title = weekdayLabel(draft.currentDay - 1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton(text = "全选", onClick = { viewModel.selectAllWeekAssignSteps(true) })
                    SecondaryButton(text = "全不选", onClick = { viewModel.selectAllWeekAssignSteps(false) })
                }
            }
            Spacer(Modifier.height(8.dp))
            val selected = draft.pattern[draft.currentDay].orEmpty()
            draft.task.steps.forEachIndexed { index, step ->
                StepCheckRow(
                    name = step.name.ifBlank { "（未命名）" },
                    detail = "${step.sets} 组 × ${step.reps}",
                    checked = selected.contains(index),
                    onToggle = { viewModel.toggleWeekAssignStep(index) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        AppCard {
            CardSub(
                "开始日期：${draft.startDate}" +
                    if (draft.endDate.isNullOrBlank()) "　（永久重复）" else "　结束：${draft.endDate}"
            )
        }

        PrimaryButton(text = "保存并安排") {
            viewModel.saveWeekAssign { message -> viewModel.showToast(message) }
        }
}

/** 添加任务到指定日期页。对应原型 page-add-task。 */
@Composable
fun AddTaskScreen(viewModel: MainViewModel, state: UiState) {
    ScreenScaffold(currentTab = null, onTabSelected = {}) {
        BackButton { viewModel.goBack() }
        Title("添加任务")

        if (state.tasks.isEmpty()) {
            EmptyHint("还没有任务", "请先到「计划」里新建任务")
        } else {
            AddTaskBody(viewModel, state)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AddTaskBody(
    viewModel: MainViewModel,
    state: UiState,
) {
    val selectedTask = state.tasks.firstOrNull { it.id == state.addTaskSelectedTaskId }

    AppCard {
        Text(text = "任务", color = AppTextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        state.tasks.forEach { task ->
            val selected = task.id == state.addTaskSelectedTaskId
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = selected, onCheckedChange = { viewModel.selectAddTask(task.id) })
                Text(text = task.name, color = if (selected) AppGreen else Color(0xFFCCCCCC), fontSize = 14.sp)
            }
        }
        ThinDivider()
        DateRow("日期", state.addTaskDate) { value -> viewModel.setAddTaskDate(value) }
        CardSub("只会安排到这一天。如果要周期性安排，请到任务的编辑页重新保存并选择重复方式。")
    }

    AppCard {
        CardTitle("选择今天要做的步骤")
        CardSub("从步骤池中勾选今天做哪几个")
        Spacer(Modifier.height(8.dp))
        selectedTask?.steps?.forEachIndexed { index, step ->
            StepCheckRow(
                name = step.name.ifBlank { "（未命名）" },
                detail = "${step.sets} 组 × ${step.reps}",
                checked = state.addTaskCheckedSteps.contains(index),
                onToggle = { viewModel.toggleAddTaskStep(index) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "全选", onClick = { viewModel.selectAllAddTaskSteps(true) })
            SecondaryButton(text = "全不选", onClick = { viewModel.selectAllAddTaskSteps(false) })
        }
    }

    PrimaryButton(text = "添加") {
        viewModel.confirmAddTask { message -> viewModel.showToast(message) }
    }
}

/** 日期选择行对外复用（其它页面也可能用到）。 */
@Composable
fun DatePickerRow(label: String, value: String, onChange: (String) -> Unit) = DateRow(label, value, onChange)
