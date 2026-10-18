package com.example.taskassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.taskassistant.core.DateKey
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.core.OrderType
import com.example.taskassistant.core.RepeatMode
import com.example.taskassistant.core.ScheduleEntry
import com.example.taskassistant.core.Step
import com.example.taskassistant.core.TaskDef
import com.example.taskassistant.core.WorkoutEngine
import com.example.taskassistant.core.WorkoutSession
import com.example.taskassistant.data.AppSettings
import com.example.taskassistant.data.DayEntry
import com.example.taskassistant.data.HistoryDetail
import com.example.taskassistant.data.SettingsRepository
import com.example.taskassistant.data.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** 顶部导航目标，与原型页面一一对应。 */
sealed interface Screen {
    data object Home : Screen
    data object Plans : Screen
    data object History : Screen
    data object Settings : Screen
    data class PlanDetail(val taskId: String) : Screen
    data class PlanEdit(val taskId: String?) : Screen
    data class WeekAssign(val taskId: String?) : Screen
    data class AddTask(val date: String) : Screen
    data object Workout : Screen
    data class HistoryDetail(val recordId: String) : Screen
    data object Backlog : Screen
}

/** 每周步骤分配页的临时草稿（与原型 weekAssignPattern 对应）。 */
data class WeekAssignDraft(
    val task: TaskDef,
    val pattern: Map<Int, List<Int>>,
    val startDate: String,
    val endDate: String?,
    val currentDay: Int = 1,
)

/** 任务编辑页的草稿（与原型 draftPlan + newPlanForm 对应）。 */
data class PlanDraft(
    val task: TaskDef,
    /** 新建任务时才出现的重复设置（编辑页不显示，与原型一致） */
    val repeat: RepeatMode = RepeatMode.ONCE,
    val onceDate: String = LocalDate.now().toString(),
    val dailyStart: String = LocalDate.now().toString(),
    val dailyEnd: String = "",
    val weeklyStart: String = LocalDate.now().toString(),
    val weeklyEnd: String = "",
)

data class UiState(
    val screen: Screen = Screen.Home,
    /** 首页当前查看的日期（对应原型 simulatedDate，允许翻看任意日期） */
    val viewDate: String = LocalDate.now().toString(),
    /** 周视图偏移，0 = 本周（对应原型 weekOffset） */
    val weekOffset: Int = 0,
    val tasks: List<TaskDef> = emptyList(),
    val dayEntries: List<DayEntry> = emptyList(),
    val weekEntries: Map<String, List<DayEntry>> = emptyMap(),
    val todayEntries: List<DayEntry> = emptyList(),
    val backlogGroups: List<Pair<TaskDef, List<ScheduleEntry>>> = emptyList(),
    /** 已进入「选择要保留的步骤」阶段的堆积任务 id（对应原型 backlogStep[taskId] === 'second'） */
    val backlogStepSelectionTaskId: String? = null,
    /** 该阶段中勾选保留的步骤下标（对应原型 backlogSelectedSteps[taskId]） */
    val backlogSelectedSteps: Map<Int, Boolean> = emptyMap(),
    val history: List<HistoryDetail> = emptyList(),
    val historyDetail: HistoryDetail? = null,
    val planDetailTask: TaskDef? = null,
    val planDraft: PlanDraft? = null,
    val weekAssign: WeekAssignDraft? = null,
    val addTaskDate: String = LocalDate.now().toString(),
    val addTaskSelectedTaskId: String? = null,
    val addTaskCheckedSteps: Set<Int> = emptySet(),
    val workout: WorkoutSession? = null,
    /** 训练时输入的实际次数 */
    val actualReps: Int = 0,
    /** 是否已拿到通知权限（API 33+）。false 时只做界面内计时。 */
    val notificationsAllowed: Boolean = true,
    /** 训练前台服务是否已启动 */
    val workoutServiceRunning: Boolean = false,
    /** 应用设置（通知/震动开关） */
    val settings: AppSettings = AppSettings(),
    val loading: Boolean = false,
    val toast: String? = null,
    /** 生成统计，便于页面显示与测试观察 */
    val lastGeneratedCount: Int = 0,
) {
    val viewDateKey: DateKey get() = DateKey(viewDate)
}

/**
 * 训练前台服务的桥接接口。
 *
 * ViewModel 不直接依赖 Android Context/Service，而是通过这个接口操作通知，
 * 好处：可以在纯 JVM 测试里传入空实现，也避免 ViewModel 里出现 Android 类型。
 */
interface WorkoutServiceController {
    /** 是否允许发通知（决定能否使用前台服务）。 */
    fun canPostNotifications(): Boolean

    /** 启动/更新训练通知；返回 false 表示系统不允许（退回界面内计时）。 */
    fun start(title: String, text: String): Boolean

    /** 停止训练通知。 */
    fun stop()

    /** 休息结束时的短震动；设备不支持时静默忽略。 */
    fun vibrateShort()
}

/** 默认实现：不做任何事（用于预览/测试）。 */
object NoopWorkoutServiceController : WorkoutServiceController {
    override fun canPostNotifications(): Boolean = true
    override fun start(title: String, text: String): Boolean = false
    override fun stop() = Unit
    override fun vibrateShort() = Unit
}

/**
 * 全应用唯一的 ViewModel。
 *
 * 与原型的对应关系：
 *   state.simulatedDate → viewDate
 *   state.weekOffset    → weekOffset
 *   state.tasks         → tasks（Room）
 *   各 renderXxx() 函数 → UiState 的字段 + Compose 重组
 *   showPage(id)        → screen
 */
class MainViewModel(
    private val repository: TaskRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val workoutService: WorkoutServiceController = NoopWorkoutServiceController,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(notificationsAllowed = workoutService.canPostNotifications())
            settingsRepository?.let { repo ->
                _state.value = _state.value.copy(settings = repo.load())
            }
            refreshAll()
            // 启动即滚动生成一次，保证首页/周视图有数据
            regenerate()
        }
    }

    // ------------------------------------------------------------ 设置

    /** 切换「常驻通知」；关闭时立即停止正在运行的通知。 */
    fun setPersistentNotification(enabled: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(persistentNotification = enabled))
        viewModelScope.launch { settingsRepository?.setPersistentNotification(enabled) }
        if (!enabled) {
            workoutService.stop()
            _state.value = _state.value.copy(workoutServiceRunning = false)
        } else {
            syncWorkoutNotification()
        }
        showToast(if (enabled) "已开启常驻通知" else "已关闭常驻通知")
    }

    /** 切换「震动提醒」。 */
    fun setVibrateOnRestEnd(enabled: Boolean) {
        _state.value = _state.value.copy(settings = _state.value.settings.copy(vibrateOnRestEnd = enabled))
        viewModelScope.launch { settingsRepository?.setVibrateOnRestEnd(enabled) }
        showToast(if (enabled) "已开启震动提醒" else "已关闭震动提醒")
    }

    /** 通知权限结果回传（Activity 请求后调用）。 */
    fun onNotificationPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(notificationsAllowed = granted)
        if (granted) syncWorkoutNotification() else workoutService.stop()
    }

    /**
     * 把当前训练状态同步到通知。
     * 未授权通知或用户关闭常驻通知时直接停掉服务，只用界面内倒计时。
     */
    private fun syncWorkoutNotification() {
        val session = _state.value.workout
        val allowed = _state.value.notificationsAllowed && _state.value.settings.persistentNotification
        if (session == null || !allowed) {
            workoutService.stop()
            _state.value = _state.value.copy(workoutServiceRunning = false)
            return
        }
        val task = _state.value.tasks.firstOrNull { it.id == session.taskId }
        val text = if (session.restSecondsLeft > 0) {
            "组间休息：${session.restSecondsLeft} 秒"
        } else {
            val stepName = session.currentSlot?.let { slot -> task?.steps?.getOrNull(slot.stepIndex)?.name }.orEmpty()
            "进度 ${session.pointer}/${session.sequence.size} 组" + if (stepName.isNotEmpty()) " · $stepName" else ""
        }
        val started = workoutService.start("训练进行中", text)
        _state.value = _state.value.copy(workoutServiceRunning = started)
    }

    // ------------------------------------------------------------ 基础刷新

    private suspend fun refreshAll() {
        val tasks = repository.getTasks()
        _state.value = _state.value.copy(tasks = tasks)
        refreshViewDate()
        refreshWeek()
        refreshToday()
        refreshBacklog()
        refreshHistory()
    }

    private suspend fun refreshViewDate() {
        val date = DateKey(_state.value.viewDate)
        _state.value = _state.value.copy(dayEntries = repository.getDayEntries(date))
    }

    private suspend fun refreshToday() {
        val today = DateKey(LocalDate.now().toString())
        _state.value = _state.value.copy(todayEntries = repository.getDayEntries(today))
    }

    private suspend fun refreshWeek() {
        val days = weekDays(_state.value.weekOffset)
        val map = repository.getWeekEntries(days)
        _state.value = _state.value.copy(weekEntries = map.mapKeys { it.key.value })
    }

    private suspend fun refreshBacklog() {
        val groups = repository.getBacklogGroups().toList()
        _state.value = _state.value.copy(backlogGroups = groups)
    }

    private suspend fun refreshHistory() {
        _state.value = _state.value.copy(history = repository.getHistory())
    }

    /**
     * 滚动生成。等价于原型 ensureSchedule(weekViewEnd)：
     * 同时把「当前视图周的结束日」传进去，保证远期周也能拿到安排。
     */
    fun regenerate() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val weekEnd = DateKey(weekDays(_state.value.weekOffset).last())
            val result = repository.generateSchedule(viewWeekEnd = weekEnd)
            _state.value = _state.value.copy(loading = false, lastGeneratedCount = result.insertedCount)
            refreshViewDate()
            refreshWeek()
            refreshToday()
            refreshBacklog()
        }
    }

    /** 当前视图周的 7 天（周一 ~ 周日），对应原型 getWeekDays。 */
    fun weekDays(offset: Int): List<String> {
        val base = LocalDate.parse(_state.value.viewDate).plusWeeks(offset.toLong())
        val monday = base.minusDays((base.dayOfWeek.value - 1).toLong())
        return (0 until 7).map { monday.plusDays(it.toLong()).toString() }
    }

    fun weekRangeLabel(offset: Int): String {
        val days = weekDays(offset)
        return "${DateUi.short(days.first())} ~ ${DateUi.short(days.last())}"
    }

    // ------------------------------------------------------------ 导航

    fun navigate(screen: Screen) {
        _state.value = _state.value.copy(screen = screen)
        when (screen) {
            is Screen.PlanDetail -> loadPlanDetail(screen.taskId)
            is Screen.PlanEdit -> openPlanEdit(screen.taskId)
            is Screen.WeekAssign -> openWeekAssign(screen.taskId)
            is Screen.AddTask -> openAddTask(screen.date)
            is Screen.HistoryDetail -> loadHistoryDetail(screen.recordId)
            else -> Unit
        }
    }

    fun goBack() {
        val target = when (val current = _state.value.screen) {
            is Screen.PlanDetail, is Screen.PlanEdit, is Screen.WeekAssign -> Screen.Plans
            is Screen.HistoryDetail -> Screen.History
            Screen.Backlog -> Screen.Home
            else -> Screen.Home
        }
        _state.value = _state.value.copy(screen = target)
    }

    /** 回到首页（原型 goHome）。 */
    fun goHome() {
        _state.value = _state.value.copy(screen = Screen.Home)
    }

    /** 清空生成游标并重新生成（设置页用）。 */
    fun resetCursors() {
        viewModelScope.launch {
            repository.resetCursors()
            repository.generateSchedule(DateKey(weekDays(_state.value.weekOffset).last()))
            refreshViewDate()
            refreshWeek()
            refreshToday()
        }
    }

    // ------------------------------------------------------------ 数据导出

    /** 生成导出内容（供 Activity 写文件）。 */
    suspend fun buildExportJson(): String = repository.exportJson()

    /** 导出成功后提示。 */
    fun onExportSucceeded() {
        showToast("已导出数据")
    }

    /** 导出失败提示。 */
    fun onExportFailed(reason: String) {
        showToast("导出失败：$reason")
    }

    // ------------------------------------------------------------ 首页日期浏览

    fun changeViewDate(deltaDays: Long) {
        val next = LocalDate.parse(_state.value.viewDate).plusDays(deltaDays).toString()
        _state.value = _state.value.copy(viewDate = next)
        viewModelScope.launch {
            refreshViewDate()
            // 翻看的历史周/未来周也需要补齐安排（原型 changeDate 里同样会 ensureSchedule）
            val weekEnd = DateKey(weekDays(_state.value.weekOffset).last())
            repository.generateSchedule(weekEnd)
            refreshViewDate()
            refreshWeek()
        }
    }

    fun jumpToToday() {
        val today = LocalDate.now().toString()
        _state.value = _state.value.copy(viewDate = today, weekOffset = 0)
        viewModelScope.launch {
            repository.generateSchedule(DateKey(weekDays(0).last()))
            refreshViewDate()
            refreshWeek()
            refreshToday()
        }
    }

    fun changeWeek(delta: Int) {
        val offset = _state.value.weekOffset + delta
        _state.value = _state.value.copy(weekOffset = offset)
        viewModelScope.launch {
            // 传入视图周末尾：远期周也能立刻拿到安排（原型修复点）
            repository.generateSchedule(DateKey(weekDays(offset).last()))
            refreshWeek()
        }
    }

    // ------------------------------------------------------------ 任务编辑

    private fun openPlanEdit(taskId: String?) {
        val existing = taskId?.let { id -> _state.value.tasks.firstOrNull { it.id == id } }
        val draft = if (existing != null) {
            PlanDraft(
                task = existing,
                repeat = existing.repeat,
                onceDate = existing.startDate.value,
                dailyStart = existing.startDate.value,
                dailyEnd = existing.endDate?.value.orEmpty(),
                weeklyStart = existing.startDate.value,
                weeklyEnd = existing.endDate?.value.orEmpty(),
            )
        } else {
            PlanDraft(
                task = TaskDef(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    orderType = OrderType.ROTATE,
                    steps = listOf(Step("", 3, 10)),
                    repeat = RepeatMode.ONCE,
                    startDate = DateKey(_state.value.viewDate),
                ),
            )
        }
        _state.value = _state.value.copy(planDraft = draft)
    }

    fun updateDraft(transform: (PlanDraft) -> PlanDraft) {
        val draft = _state.value.planDraft ?: return
        _state.value = _state.value.copy(planDraft = transform(draft))
    }

    fun addDraftStep() = updateDraft { draft ->
        draft.copy(task = draft.task.copy(steps = draft.task.steps + Step("", 3, 10)))
    }

    fun removeDraftStep(index: Int) = updateDraft { draft ->
        draft.copy(task = draft.task.copy(steps = draft.task.steps.filterIndexed { i, _ -> i != index }))
    }

    fun updateDraftStep(index: Int, transform: (Step) -> Step) = updateDraft { draft ->
        draft.copy(
            task = draft.task.copy(
                steps = draft.task.steps.mapIndexed { i, step -> if (i == index) transform(step) else step }
            )
        )
    }

    /** 校验草稿；返回错误文案，null 表示通过。对应原型 validateDraftPlan。 */
    fun validateDraft(draft: PlanDraft): String? {
        val task = draft.task
        if (task.name.isBlank()) return "请填写任务名称"
        if (task.steps.isEmpty()) return "请至少添加一个步骤"
        task.steps.forEach { step ->
            if (step.name.isBlank()) return "步骤名称不能为空"
            if (step.sets < 1) return "组数至少为 1"
            if (step.reps < 1) return "目标次数至少为 1"
        }
        if (task.restEnabled && task.restSeconds < 5) return "休息秒数至少为 5"
        return null
    }

    /**
     * 保存任务。
     *  - 新建每周任务 → 进入每周步骤分配页（与原型一致）
     *  - 新建一次性/每天任务 → 直接保存并重新生成
     *  - 编辑 → 保存并重算该任务的日程（不覆盖已有训练数据由仓库保证）
     */
    fun savePlan(onError: (String) -> Unit) {
        val draft = _state.value.planDraft ?: return
        validateDraft(draft)?.let { onError(it); return }
        val isNew = _state.value.tasks.none { it.id == draft.task.id }

        if (isNew && draft.repeat == RepeatMode.WEEKLY) {
            // 进入每周分配页
            val pattern = (1..7).associateWith { draft.task.steps.indices.toList() }
            _state.value = _state.value.copy(
                screen = Screen.WeekAssign(draft.task.id),
                weekAssign = WeekAssignDraft(
                    task = draft.task.copy(
                        repeat = RepeatMode.WEEKLY,
                        startDate = DateKey(draft.weeklyStart),
                        endDate = draft.weeklyEnd.takeIf { it.isNotBlank() }?.let { DateKey(it) },
                    ),
                    pattern = pattern,
                    startDate = draft.weeklyStart,
                    endDate = draft.weeklyEnd.takeIf { it.isNotBlank() },
                ),
            )
            return
        }

        viewModelScope.launch {
            val task = when (draft.repeat) {
                RepeatMode.ONCE -> draft.task.copy(
                    repeat = RepeatMode.ONCE,
                    startDate = DateKey(draft.onceDate),
                    endDate = DateKey(draft.onceDate),
                )
                RepeatMode.DAILY -> draft.task.copy(
                    repeat = RepeatMode.DAILY,
                    startDate = DateKey(draft.dailyStart),
                    endDate = draft.dailyEnd.takeIf { it.isNotBlank() }?.let { DateKey(it) },
                )
                RepeatMode.WEEKLY -> draft.task.copy(
                    repeat = RepeatMode.WEEKLY,
                    startDate = DateKey(draft.weeklyStart),
                    endDate = draft.weeklyEnd.takeIf { it.isNotBlank() }?.let { DateKey(it) },
                )
            }
            repository.saveTask(task, isNew = isNew)

            if (isNew && task.repeat == RepeatMode.ONCE) {
                // 一次性任务：只在那一天建一条独立日程
                repository.addTaskToDate(task.id, task.startDate, task.steps.indices.toList())
                repository.resetCursors()
                _state.value = _state.value.copy(viewDate = task.startDate.value)
            }
            repository.resetCursors()
            repository.generateSchedule(DateKey(weekDays(_state.value.weekOffset).last()))

            val tasks = repository.getTasks()
            _state.value = _state.value.copy(
                tasks = tasks,
                planDraft = null,
                screen = Screen.Plans,
                toast = "已保存",
            )
            refreshViewDate()
            refreshWeek()
            refreshToday()
        }
    }

    // ------------------------------------------------------------ 每周步骤分配

    private fun openWeekAssign(taskId: String?) {
        val task = _state.value.tasks.firstOrNull { it.id == taskId } ?: return
        _state.value = _state.value.copy(
            weekAssign = WeekAssignDraft(
                task = task,
                pattern = (1..7).associateWith { day -> task.stepIndexesByIsoDay[day].orEmpty() },
                startDate = task.startDate.value,
                endDate = task.endDate?.value,
            ),
        )
    }

    fun selectWeekAssignDay(day: Int) {
        val draft = _state.value.weekAssign ?: return
        _state.value = _state.value.copy(weekAssign = draft.copy(currentDay = day))
    }

    fun toggleWeekAssignStep(stepIndex: Int) {
        val draft = _state.value.weekAssign ?: return
        val current = draft.pattern[draft.currentDay].orEmpty()
        val next = if (current.contains(stepIndex)) current - stepIndex else current + stepIndex
        _state.value = _state.value.copy(
            weekAssign = draft.copy(pattern = draft.pattern + (draft.currentDay to next.sorted())),
        )
    }

    fun selectAllWeekAssignSteps(all: Boolean) {
        val draft = _state.value.weekAssign ?: return
        val indexes = if (all) draft.task.steps.indices.toList() else emptyList()
        _state.value = _state.value.copy(
            weekAssign = draft.copy(pattern = draft.pattern + (draft.currentDay to indexes)),
        )
    }

    fun updateWeekAssignDates(start: String, end: String) {
        val draft = _state.value.weekAssign ?: return
        _state.value = _state.value.copy(
            weekAssign = draft.copy(startDate = start, endDate = end.takeIf { it.isNotBlank() }),
        )
    }

    fun saveWeekAssign(onError: (String) -> Unit) {
        val draft = _state.value.weekAssign ?: return
        if (draft.startDate.isBlank()) { onError("请选择开始日期"); return }
        if (!draft.endDate.isNullOrBlank() && draft.endDate < draft.startDate) {
            onError("结束日期不能早于开始日期"); return
        }
        if (draft.pattern.values.all { it.isEmpty() }) { onError("至少给一天勾选步骤"); return }

        viewModelScope.launch {
            val isNew = _state.value.tasks.none { it.id == draft.task.id }
            val task = draft.task.copy(
                repeat = RepeatMode.WEEKLY,
                startDate = DateKey(draft.startDate),
                endDate = draft.endDate?.takeIf { it.isNotBlank() }?.let { DateKey(it) },
                stepIndexesByIsoDay = draft.pattern.filterValues { it.isNotEmpty() },
            )
            repository.saveTask(task, isNew = isNew)
            repository.resetCursors()
            repository.generateSchedule(DateKey(weekDays(_state.value.weekOffset).last()))
            _state.value = _state.value.copy(
                tasks = repository.getTasks(),
                weekAssign = null,
                planDraft = null,
                screen = Screen.Plans,
                toast = "已保存并安排",
            )
            refreshWeek()
            refreshViewDate()
        }
    }

    // ------------------------------------------------------------ 任务详情

    private fun loadPlanDetail(taskId: String) {
        _state.value = _state.value.copy(
            planDetailTask = _state.value.tasks.firstOrNull { it.id == taskId },
        )
    }

    fun deletePlan(taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            repository.generateSchedule(DateKey(weekDays(_state.value.weekOffset).last()))
            _state.value = _state.value.copy(
                tasks = repository.getTasks(),
                screen = Screen.Plans,
                toast = "已删除",
            )
            refreshViewDate()
            refreshWeek()
            refreshToday()
            refreshHistory()
        }
    }

    // ------------------------------------------------------------ 添加任务到日期

    private fun openAddTask(date: String) {
        val firstTask = _state.value.tasks.firstOrNull()
        _state.value = _state.value.copy(
            addTaskDate = date,
            addTaskSelectedTaskId = firstTask?.id,
            addTaskCheckedSteps = firstTask?.steps?.indices?.toSet().orEmpty(),
        )
    }

    fun selectAddTask(taskId: String) {
        val task = _state.value.tasks.firstOrNull { it.id == taskId } ?: return
        _state.value = _state.value.copy(
            addTaskSelectedTaskId = taskId,
            addTaskCheckedSteps = task.steps.indices.toSet(),
        )
    }

    fun toggleAddTaskStep(index: Int) {
        val current = _state.value.addTaskCheckedSteps
        _state.value = _state.value.copy(
            addTaskCheckedSteps = if (current.contains(index)) current - index else current + index,
        )
    }

    fun selectAllAddTaskSteps(all: Boolean) {
        val task = _state.value.tasks.firstOrNull { it.id == _state.value.addTaskSelectedTaskId } ?: return
        _state.value = _state.value.copy(
            addTaskCheckedSteps = if (all) task.steps.indices.toSet() else emptySet(),
        )
    }

    fun setAddTaskDate(date: String) {
        _state.value = _state.value.copy(addTaskDate = date)
    }

    fun confirmAddTask(onError: (String) -> Unit) {
        val taskId = _state.value.addTaskSelectedTaskId ?: return
        val date = DateKey(_state.value.addTaskDate)
        val steps = _state.value.addTaskCheckedSteps.sorted()
        if (steps.isEmpty()) { onError("请至少勾选一个步骤"); return }
        viewModelScope.launch {
            val ok = repository.addTaskToDate(taskId, date, steps)
            if (!ok) {
                onError("这一天已经有该任务")
                return@launch
            }
            _state.value = _state.value.copy(viewDate = date.value, screen = Screen.Home, toast = "已添加")
            refreshViewDate()
            refreshWeek()
            if (date.value == LocalDate.now().toString()) refreshToday()
        }
    }

    // ------------------------------------------------------------ 训练

    fun startWorkout(entry: ScheduleEntry, task: TaskDef) {
        viewModelScope.launch {
            repository.markWorkoutStarted(entry.id)
            val session = WorkoutEngine.start(task, entry)
            _state.value = _state.value.copy(
                workout = session,
                actualReps = session.currentSlot?.targetReps ?: 0,
                screen = Screen.Workout,
            )
            refreshViewDate()
            // 训练开始 → 启动前台服务通知（未授权时自动退回界面内计时）
            syncWorkoutNotification()
        }
    }

    fun resumeWorkout(entryId: String) {
        viewModelScope.launch {
            val entry = repository.getEntry(entryId) ?: return@launch
            val task = _state.value.tasks.firstOrNull { it.id == entry.taskId } ?: return@launch
            startWorkout(entry, task)
        }
    }

    fun adjustActualReps(delta: Int) {
        _state.value = _state.value.copy(actualReps = (_state.value.actualReps + delta).coerceAtLeast(0))
    }

    fun setActualReps(value: Int) {
        _state.value = _state.value.copy(actualReps = value.coerceAtLeast(0))
    }

    /** 两阶段按钮：先「开始本组」，再「完成本组」。每步都即时落库。 */
    fun handleSetButton() {
        val session = _state.value.workout ?: return
        viewModelScope.launch {
            if (!session.setStarted) {
                val next = WorkoutEngine.markSetStarted(session)
                syncSession(next, previousRecordCount = session.records.size)
                return@launch
            }
            val task = currentWorkoutTask() ?: return@launch
            val next = WorkoutEngine.completeSet(session, task, _state.value.actualReps) ?: return@launch
            syncSession(next, previousRecordCount = session.records.size)
            if (next.isFinishedBySets) {
                finishWorkout()
            } else {
                _state.value = _state.value.copy(actualReps = next.currentSlot?.targetReps ?: 0)
            }
        }
    }

    fun skipSet() {
        val session = _state.value.workout ?: return
        viewModelScope.launch {
            val next = WorkoutEngine.skipSet(session) ?: return@launch
            syncSession(next, previousRecordCount = session.records.size)
            if (next.isFinishedBySets) {
                finishWorkout()
            } else {
                _state.value = _state.value.copy(actualReps = next.currentSlot?.targetReps ?: 0)
            }
        }
    }

    fun undoLastSet() {
        val session = _state.value.workout ?: return
        viewModelScope.launch {
            val next = WorkoutEngine.undoLast(session) ?: return@launch
            syncSession(next, previousRecordCount = session.records.size)
            _state.value = _state.value.copy(actualReps = next.currentSlot?.targetReps ?: 0)
        }
    }

    fun tickRest() {
        val session = _state.value.workout ?: return
        if (session.restSecondsLeft <= 0) return
        val next = WorkoutEngine.tickRest(session)
        _state.value = _state.value.copy(workout = next)
        // 倒计时归零的那一刻震动提醒（受设置开关控制）
        if (next.restSecondsLeft == 0 && _state.value.settings.vibrateOnRestEnd) {
            workoutService.vibrateShort()
        }
        // 通知里的倒计时跟着走；只在剩余秒数变化时刷新（每秒一次，开销很小）
        syncWorkoutNotification()
    }

    fun skipRest() {
        val session = _state.value.workout ?: return
        _state.value = _state.value.copy(workout = WorkoutEngine.skipRest(session))
    }

    private suspend fun syncSession(session: WorkoutSession, previousRecordCount: Int) {
        _state.value = _state.value.copy(workout = session)
        repository.persistWorkout(session, previousRecordCount)
    }

    private fun currentWorkoutTask(): TaskDef? {
        val session = _state.value.workout ?: return null
        return _state.value.tasks.firstOrNull { it.id == session.taskId }
    }

    /**
     * 结束训练（Bug 4）：写入最终状态与历史记录。
     * 有跳过 / 没做满 → incomplete，历史里 completed=false，界面显示红色「提前结束」。
     */
    fun finishWorkout() {
        val session = _state.value.workout ?: return
        val task = currentWorkoutTask() ?: return
        viewModelScope.launch {
            repository.persistWorkout(session, session.records.size)
            val history = repository.finishWorkout(task, session)
            // 训练结束 → 立即停止通知服务
            workoutService.stop()
            _state.value = _state.value.copy(
                workout = null,
                workoutServiceRunning = false,
                screen = Screen.Home,
                toast = if (history.completed) "已保存" else "已保存（提前结束）",
            )
            refreshViewDate()
            refreshWeek()
            refreshToday()
            refreshHistory()
        }
    }

    /** 首页直接标记完成（不做完整训练流程时使用）。 */
    fun markEntryCompleted(entry: ScheduleEntry) {
        val task = _state.value.tasks.firstOrNull { it.id == entry.taskId } ?: return
        viewModelScope.launch {
            repository.completeEntryNow(task, entry)
            _state.value = _state.value.copy(toast = "已标记完成")
            refreshViewDate()
            refreshWeek()
            refreshToday()
            refreshHistory()
        }
    }

    fun skipEntry(entry: ScheduleEntry) {
        viewModelScope.launch {
            repository.setEntryStatus(entry.id, EntryStatus.SKIPPED)
            _state.value = _state.value.copy(toast = "已跳过")
            refreshViewDate()
            refreshWeek()
            refreshToday()
        }
    }

    // ------------------------------------------------------------ 历史

    private fun loadHistoryDetail(recordId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(historyDetail = repository.getHistoryDetail(recordId))
        }
    }

    // ------------------------------------------------------------ 逾期处理

    /**
     * 第一阶段：点「做」→ 进入「选择要保留的步骤」。
     * 对应原型 backlogDo(taskId)：backlogStep[taskId] = 'second'，并把全部步骤默认勾选。
     */
    fun backlogDo(task: TaskDef) {
        _state.value = _state.value.copy(
            backlogStepSelectionTaskId = task.id,
            backlogSelectedSteps = task.steps.indices.associateWith { true },
        )
    }

    /** 返回第一阶段（对应原型 backlogReset）。 */
    fun backlogReset() {
        _state.value = _state.value.copy(
            backlogStepSelectionTaskId = null,
            backlogSelectedSteps = emptyMap(),
        )
    }

    /** 勾选/取消某个要保留的步骤。 */
    fun toggleBacklogStep(stepIndex: Int) {
        val current = _state.value.backlogSelectedSteps
        _state.value = _state.value.copy(
            backlogSelectedSteps = current + (stepIndex to !(current[stepIndex] ?: false)),
        )
    }

    /** 全选/全不选要保留的步骤。 */
    fun selectAllBacklogSteps(task: TaskDef, all: Boolean) {
        _state.value = _state.value.copy(
            backlogSelectedSteps = task.steps.indices.associateWith { all },
        )
    }

    /** 当前勾选保留的步骤（保序）。 */
    private fun selectedBacklogSteps(): List<Int> =
        _state.value.backlogSelectedSteps.filterValues { it }.keys.sorted()

    /** 第一阶段：跳过所有堆积条目。 */
    fun backlogSkip(task: TaskDef, entries: List<ScheduleEntry>) {
        viewModelScope.launch {
            repository.backlogSkip(entries.map { it.id })
            backlogReset()
            _state.value = _state.value.copy(toast = "已跳过")
            refreshBacklog()
            refreshViewDate()
        }
    }

    /**
     * 第二阶段：对保留下来的步骤执行 顺延 / 覆盖 / 一起做。
     *
     * 与原型一致：先把堆积条目置为已跳过，再按选择结果在目标日期建一条新安排；
     * 步骤筛选只影响新安排的步骤集合，不会改动历史记录里已有的训练数据。
     */
    fun backlogAction(task: TaskDef, entries: List<ScheduleEntry>, action: String) {
        val kept = selectedBacklogSteps().ifEmpty { task.steps.indices.toList() }
        viewModelScope.launch {
            val today = DateKey(LocalDate.now().toString())
            when (action) {
                "顺延" -> {
                    entries.forEach { entry ->
                        // 只保留勾选的步骤；全部没勾选就不建新条目
                        val steps = entry.stepIndexes.filter { kept.contains(it) }
                        repository.setEntryStatus(entry.id, EntryStatus.SKIPPED)
                        if (steps.isNotEmpty()) {
                            repository.addTaskToDate(task.id, entry.date.plusDays(1), steps)
                        }
                    }
                    _state.value = _state.value.copy(toast = "已顺延")
                }

                "覆盖" -> {
                    entries.forEach { repository.setEntryStatus(it.id, EntryStatus.SKIPPED) }
                    repository.addTaskToDate(task.id, today, kept)
                    _state.value = _state.value.copy(toast = "已覆盖到今天")
                }

                "一起做" -> {
                    entries.forEach { repository.setEntryStatus(it.id, EntryStatus.SKIPPED) }
                    repository.addTaskToDate(task.id, today, kept)
                    _state.value = _state.value.copy(toast = "已合并到今天")
                }
            }
            backlogReset()
            refreshBacklog()
            refreshViewDate()
            refreshToday()
        }
    }

    // ------------------------------------------------------------ Toast

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }

    fun showToast(message: String) {
        _state.value = _state.value.copy(toast = message)
    }

    class Factory(
        private val repository: TaskRepository,
        private val settingsRepository: SettingsRepository? = null,
        private val workoutService: WorkoutServiceController = NoopWorkoutServiceController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository, settingsRepository, workoutService) as T
    }
}
