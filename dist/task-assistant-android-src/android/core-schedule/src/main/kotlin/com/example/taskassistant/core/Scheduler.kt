package com.example.taskassistant.core

/**
 * 「某个任务在某一天该做哪几个步骤」的唯一判定入口。
 *
 * 原型里这条规则被抄写在多个地方（generateEntriesForTask、stepIndexesForDate、
 * 以及后向回填 B 段的 fillMissingInRange(force=true)），各处规则不一致正是
 * Bug 1（开始日之前仍生成）与 Bug 3（不看星期直接生成）的根源。
 *
 * 在 Android 版本中，所有生成路径都只调用这一个函数，并且它同时检查：
 *   1. 任务有效日期范围（startDate <= date <= endDate，endDate 为空表示不设上限）；
 *   2. 重复方式（daily 取全部步骤；weekly 严格取 weeklyPattern 对应星期；once 取全部步骤）；
 *   3. 结果非空（空结果表示这天不做，绝不生成空安排）。
 *
 * 返回空列表 = 这天不该有这个任务的任何安排。
 */
object StepRule {

    /** 计算结果；空列表表示该任务在该日期不应生成安排。 */
    fun stepIndexesFor(task: TaskDef, date: DateKey): List<Int> {
        // 规则 1：日期必须落在任务有效范围内。这一条是 Bug 1 的根治点。
        if (!task.isDateInRange(date)) return emptyList()
        if (task.steps.isEmpty()) return emptyList()

        return when (task.repeat) {
            RepeatMode.ONCE, RepeatMode.DAILY -> task.steps.indices.toList()
            // 规则 2：每周任务严格按 weeklyPattern 取星期对应项（Bug 3）。
            RepeatMode.WEEKLY -> task.normalizedPattern()[date.isoDayOfWeek].orEmpty()
        }
    }

    /** 便于断言：该任务在该日期是否应生成安排。 */
    fun shouldGenerateOn(task: TaskDef, date: DateKey): Boolean =
        stepIndexesFor(task, date).isNotEmpty()
}

/**
 * 日程生成结果：新增了哪些条目。
 * 生成器只做「插入缺失」，绝不修改已存在条目，因此不需要返回被修改的条目。
 */
data class GenerationResult(
    val inserted: List<ScheduleEntry>,
) {
    val insertedCount: Int get() = inserted.size
    val changed: Boolean get() = inserted.isNotEmpty()

    companion object {
        val EMPTY = GenerationResult(emptyList())
    }
}

/**
 * 某个任务的生成游标缓存。
 *
 * - forward: 前向已经铺到哪一天之后的「下一个待处理日」
 * - backward: 后向连续回填已经走到哪一天之前的「下一个待处理日」
 *
 * 原型把这两个游标放在全局 rollCursors 里，并靠 resetRollCursors() 在任务定义变化时清空。
 * Android 端保留同样机制，但把游标封装在 SchedulerState 内部，避免散落在全局作用域。
 */
data class TaskCursors(
    val forward: DateKey? = null,
    val backward: DateKey? = null,
)

/** 生成器需要的最小存储接口：查询 + 插入。没有更新/删除能力，从类型上保证不会覆盖已有数据。 */
interface ScheduleStore {
    /** 该日期该任务是否已有条目（含任意状态）。 */
    fun findEntry(taskId: String, date: DateKey): ScheduleEntry?

    /** 已存在条目的全部 id（用于去重统计与测试断言）。 */
    fun allEntries(): List<ScheduleEntry>

    /** 插入一条缺失的条目。实现方必须保证同 (taskId, date) 不会重复插入。 */
    fun insert(entry: ScheduleEntry)
}

/** 内存实现，供单元测试使用；Android 端由 Room 实现同名接口。 */
class InMemoryScheduleStore(initial: List<ScheduleEntry> = emptyList()) : ScheduleStore {
    private val entries = LinkedHashMap<Pair<String, DateKey>, ScheduleEntry>()

    init {
        initial.forEach { entries[it.taskId to it.date] = it }
    }

    override fun findEntry(taskId: String, date: DateKey): ScheduleEntry? = entries[taskId to date]

    override fun allEntries(): List<ScheduleEntry> = entries.values.toList()

    override fun insert(entry: ScheduleEntry) {
        val key = entry.taskId to entry.date
        check(!entries.containsKey(key)) {
            "重复插入同一天同一任务的安排: ${entry.taskId} @ ${entry.date.value}"
        }
        entries[key] = entry
    }
}

/** 生成条目的 id 工厂，便于测试注入确定性 id。 */
fun interface EntryIdFactory {
    fun newId(): String
}

/**
 * 日程生成器。
 *
 * 移植自原型 ensureSchedule / ensureTaskCoverage / fillMissingInRange，但做了三处修正：
 *   - 所有分支都通过 [StepRule.stepIndexesFor] 判定，因而同时遵守任务日期范围与每周规则；
 *   - 只有 [ScheduleStore.insert]，没有 update，从结构上保证已存在的训练数据不被覆盖；
 *   - 游标推进与「是否创建了条目」解耦（原型里每周任务遇到休息日会原地踏步）。
 */
class Scheduler(
    private val store: ScheduleStore,
    private val clock: () -> DateKey,
    private val idFactory: EntryIdFactory,
    private val horizonDays: Long = DEFAULT_HORIZON_DAYS,
    private val backfillDays: Long = DEFAULT_BACKFILL_DAYS,
) {

    private val cursors = mutableMapOf<String, TaskCursors>()

    /** 任务定义变化（新建/编辑/改重复方式）后必须调用，游标重算。 */
    fun resetCursors() {
        cursors.clear()
    }

    fun cursorsOf(taskId: String): TaskCursors = cursors[taskId] ?: TaskCursors()

    /**
     * 滚动生成入口。等价于原型 ensureSchedule(viewWeekEnd)。
     *
     * @param tasks 当前全部任务
     * @param viewWeekEnd 当前视图周的结束日（周视图渲染前传入），可空
     */
    fun ensureSchedule(tasks: List<TaskDef>, viewWeekEnd: DateKey? = null): GenerationResult {
        val today = clock()
        val inserted = mutableListOf<ScheduleEntry>()
        tasks.forEach { task ->
            inserted += ensureTaskCoverage(task, today, viewWeekEnd)
        }
        return GenerationResult(inserted)
    }

    /**
     * 单个任务的覆盖生成。完整对应原型 ensureTaskCoverage 的前向段、A 段、B 段。
     */
    fun ensureTaskCoverage(
        task: TaskDef,
        today: DateKey,
        viewWeekEnd: DateKey?,
    ): List<ScheduleEntry> {
        val inserted = mutableListOf<ScheduleEntry>()

        val horizonEnd = today.plusDays(horizonDays)
        val backwardFloor = today.minusDays(backfillDays)

        // 视图周结束日与 365 天取较晚者；再由任务结束日收窄。
        val forwardViewEnd = if (viewWeekEnd != null && viewWeekEnd > horizonEnd) viewWeekEnd else horizonEnd
        val forwardScanEnd = task.endDate?.let { minOf(forwardViewEnd, it) } ?: forwardViewEnd
        // 后向能扫到的最晚日期：不能越过任务结束日。
        val backwardScanEnd = task.endDate?.let { minOf(today, it) } ?: today

        // ---------- 前向滚动 ----------
        var forward = cursors[task.id]?.forward
        if (forward == null) {
            forward = resolveForwardStart(task, today, backwardFloor)
        }
        val rawForwardStart = forward
        var fillFrom = maxOf(rawForwardStart, task.startDate)
        fillFrom = maxOf(fillFrom, backwardFloor)
        if (fillFrom <= forwardScanEnd) {
            // 只扫这一段「新出现」的区间；每天都会推进游标（修复原型休息日不推进的问题）。
            inserted += insertMissing(task, fillFrom, forwardScanEnd)
            cursors[task.id] = cursorsOf(task.id).copy(forward = forwardScanEnd.plusDays(1))
        } else if (fillFrom > rawForwardStart) {
            // 扫描起点被开始日 / 回填下限抬起时，游标也要跟上，避免每次重复走同一段空档。
            cursors[task.id] = cursorsOf(task.id).copy(forward = minOf(fillFrom, forwardScanEnd.plusDays(1)))
        }

        // ---------- 后向回填（仅在往前翻历史周时工作） ----------
        if (viewWeekEnd != null && viewWeekEnd < today) {
            // A 段：连续回填到今天（受任务开始日与 365 天下限约束）
            inserted += continuousBackfill(task, backwardScanEnd, backwardFloor)

            // B 段：当前视图周的定点补齐（独立于 A 段游标）
            inserted += fillViewWeek(task, viewWeekEnd, backwardFloor, forwardScanEnd)
        }

        return inserted
    }

    /**
     * 前向起点：从今天往回找第一个「还没有安排」的日期，最多回看 backfillDays 天。
     * 找不到（说明整段都已有安排）就从今天开始。
     */
    private fun resolveForwardStart(task: TaskDef, today: DateKey, floor: DateKey): DateKey {
        var d = today
        while (d >= floor) {
            if (store.findEntry(task.id, d) == null) return d
            d = d.minusDays(1)
        }
        return today
    }

    /**
     * A 段连续回填：从后向游标（或 backwardScanEnd）一路补到 max(任务开始日, 365 天下限)。
     *
     * 游标一旦走到下限就整段跳过，不再把 d 重置回下限，避免每次翻历史周都重扫整段区间。
     * 每天无论有没有创建条目都推进游标。
     */
    private fun continuousBackfill(
        task: TaskDef,
        backwardScanEnd: DateKey,
        backwardFloor: DateKey,
    ): List<ScheduleEntry> {
        val backLimit = maxOf(backwardFloor, task.startDate)
        if (backwardScanEnd < backLimit) return emptyList()

        val cached = cursors[task.id]?.backward
        // 游标已在下限之下 → 整段跳过
        if (cached != null && cached < backLimit) return emptyList()

        var d = minOf(cached ?: backwardScanEnd, backwardScanEnd)
        if (d < backLimit) d = backLimit

        val inserted = mutableListOf<ScheduleEntry>()
        while (d >= backLimit) {
            // 只补任务开始日之后、且确实该做的日期
            if (d >= task.startDate) {
                inserted += insertIfMissing(task, d)
            }
            cursors[task.id] = cursorsOf(task.id).copy(backward = d.minusDays(1))
            d = d.minusDays(1)
        }
        return inserted
    }

    /**
     * B 段：当前视图周的定点补齐。
     *
     * 范围同时满足：
     *   - 不早于 365 天下限；
     *   - 不早于任务开始日（Bug 1：视图落在开始日之前时，这一周不该生成任何安排）；
     *   - 不晚于 min(视图周日, 任务结束日)。
     */
    private fun fillViewWeek(
        task: TaskDef,
        viewWeekEnd: DateKey,
        backwardFloor: DateKey,
        forwardScanEnd: DateKey,
    ): List<ScheduleEntry> {
        if (viewWeekEnd < backwardFloor) return emptyList()
        val weekStart = viewWeekEnd.minusDays(6)
        val from = maxOf(weekStart, maxOf(backwardFloor, task.startDate))
        val to = minOf(viewWeekEnd, forwardScanEnd)
        if (from > to) return emptyList()
        return insertMissing(task, from, to)
    }

    /** 在 [from, to] 内补齐缺失条目；已存在的一律跳过（Bug 2）。 */
    private fun insertMissing(task: TaskDef, from: DateKey, to: DateKey): List<ScheduleEntry> {
        if (from > to) return emptyList()
        val inserted = mutableListOf<ScheduleEntry>()
        for (date in dateRange(from, to)) {
            inserted += insertIfMissing(task, date)
        }
        return inserted
    }

    /**
     * 单日补齐：已存在 → 不动；该做 → 插入；不该做 → 什么都不做。
     * 这里是「已有训练数据不被覆盖」的唯一写入口。
     */
    private fun insertIfMissing(task: TaskDef, date: DateKey): List<ScheduleEntry> {
        if (store.findEntry(task.id, date) != null) return emptyList()
        val stepIndexes = StepRule.stepIndexesFor(task, date)
        if (stepIndexes.isEmpty()) return emptyList()
        val entry = ScheduleEntry(
            id = idFactory.newId(),
            taskId = task.id,
            date = date,
            stepIndexes = stepIndexes,
        )
        store.insert(entry)
        return listOf(entry)
    }

    companion object {
        /** 向前生成范围：今天 + 365 天（与原型 ROLL_HORIZON_DAYS 一致）。 */
        const val DEFAULT_HORIZON_DAYS: Long = 365

        /** 向后补充下限：今天 - 365 天（与原型 ROLL_BACK_DAYS 一致）。 */
        const val DEFAULT_BACKFILL_DAYS: Long = 365
    }
}
