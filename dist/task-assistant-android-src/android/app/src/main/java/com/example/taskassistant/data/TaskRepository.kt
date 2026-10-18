package com.example.taskassistant.data

import com.example.taskassistant.core.DateKey
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.core.GenerationResult
import com.example.taskassistant.core.HistoryRecord
import com.example.taskassistant.core.InMemoryScheduleStore
import com.example.taskassistant.core.ScheduleEntry
import com.example.taskassistant.core.Scheduler
import com.example.taskassistant.core.TaskDef
import com.example.taskassistant.core.WorkoutEngine
import com.example.taskassistant.core.WorkoutSession
import com.example.taskassistant.data.db.AppDatabase
import com.example.taskassistant.data.db.HistoryRecordEntity
import com.example.taskassistant.data.db.HistorySetEntity
import com.example.taskassistant.data.db.HistoryStepEntity
import com.example.taskassistant.data.db.ScheduleEntryEntity
import com.example.taskassistant.data.db.SetRecordEntity
import com.example.taskassistant.data.db.TaskEntity
import com.example.taskassistant.data.mapper.toDomain
import com.example.taskassistant.data.mapper.toEntity
import com.example.taskassistant.data.mapper.toPatternEntities
import com.example.taskassistant.data.mapper.toStepEntities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** 首页某一天要展示的一条安排（带任务定义，便于界面直接用）。 */
data class DayEntry(
    val entry: ScheduleEntry,
    val task: TaskDef,
)

/** 历史记录 + 明细。 */
data class HistoryDetail(
    val record: HistoryRecord,
)

/**
 * 数据仓库：把 Room 与 pure-Kotlin 的 [Scheduler] / [WorkoutEngine] 接起来。
 *
 * 设计要点（对应「不能覆盖已有训练数据」的要求）：
 *  1. 生成时先把可能涉及区间内的已有条目一次性载入内存，交给 [Scheduler]；
 *  2. [Scheduler] 只调用 store.insert()，没有任何 update 路径；
 *  3. 写回数据库时使用 @Insert(onConflict = IGNORE)，即使并发重复也只忽略，不会覆盖。
 */
class TaskRepository(
    private val db: AppDatabase,
    private val today: () -> DateKey = { DateKey(LocalDate.now().toString()) },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private val taskDao = db.taskDao()
    private val scheduleDao = db.scheduleDao()
    private val historyDao = db.historyDao()

    // ---------------------------------------------------------------- 任务

    suspend fun getTasks(): List<TaskDef> = withContext(Dispatchers.IO) {
        val steps = taskDao.getAllSteps().groupBy { it.taskId }
        val patterns = taskDao.getAllPatterns().groupBy { it.taskId }
        taskDao.getTasks().map { entity ->
            entity.toDomain(steps[entity.id].orEmpty(), patterns[entity.id].orEmpty())
        }
    }

    suspend fun getTask(id: String): TaskDef? = withContext(Dispatchers.IO) {
        val entity = taskDao.getTask(id) ?: return@withContext null
        entity.toDomain(taskDao.getSteps(id), taskDao.getPatterns(id))
    }

    /** 新建或编辑任务；同时清空该任务的生成游标，使新定义立即生效。 */
    suspend fun saveTask(task: TaskDef, isNew: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = taskDao.getTask(task.id)
        val createdAt = existing?.createdAt ?: now
        taskDao.replaceTaskGraph(
            task = task.toEntity(now, createdAt),
            steps = task.toStepEntities(),
            patterns = task.toPatternEntities(),
        )
        if (!isNew) {
            // 结束日被改早时，清掉「今天之后、还没被动过」的越界待做安排。
            // 用字符串比较取较晚的日期，避免与 core 模块的 maxOf 语义混淆。
            task.endDate?.let { end ->
                val endValue = end.value
                val todayValue = today().value
                val after = if (endValue >= todayValue) endValue else todayValue
                scheduleDao.deleteStalePendingAfter(task.id, after)
            }
        }
    }

    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) {
        // 外键 CASCADE 会一并删除步骤、分配、日程、组数记录
        taskDao.deleteTask(id)
        taskDao.deleteCursor(id)
    }

    // ---------------------------------------------------------------- 日程生成

    /**
     * 滚动生成。与原型 ensureSchedule 等价，但：
     *  - 所有生成都经过 core 的 StepRule，天然遵守 startDate / endDate / weeklyPattern；
     *  - 已存在条目只读不写。
     *
     * @param viewWeekEnd 当前视图周的周日
     */
    suspend fun generateSchedule(viewWeekEnd: DateKey? = null): GenerationResult = withContext(Dispatchers.IO) {
        val tasks = getTasks()
        if (tasks.isEmpty()) return@withContext GenerationResult.EMPTY
        val todayKey = today()

        // 载入可能涉及的区间：向后 400 天、向前 370 天（覆盖 365 天滚动 + 视图周）
        val from = minOf(todayKey.minusDays(370), (viewWeekEnd ?: todayKey).minusDays(7))
        val to = maxOf(todayKey.plusDays(400), viewWeekEnd ?: todayKey)
        val existing = scheduleDao.getEntriesBetween(from.value, to.value)
        val setRecords = if (existing.isEmpty()) {
            emptyMap()
        } else {
            scheduleDao.getSetRecordsFor(existing.map { it.id }).groupBy { it.entryId }
        }

        val store = InMemoryScheduleStore(
            existing.map { it.toDomain(setRecords[it.id].orEmpty()) }
        )
        val scheduler = Scheduler(
            store = store,
            clock = { todayKey },
            idFactory = idFactory,
        )
        val result = scheduler.ensureSchedule(tasks, viewWeekEnd)

        if (result.inserted.isNotEmpty()) {
            val now = System.currentTimeMillis()
            // IGNORE：即使并发或其他入口已插入同一 (taskId, date)，也不会覆盖
            scheduleDao.insertMissing(result.inserted.map { it.toEntity(now) })
        }

        // 清理空壳安排（每周任务取消勾选后可能残留），只删真正没做过任何事的空壳
        pruneEmptyShells(from, to)

        result
    }

    private suspend fun pruneEmptyShells(from: DateKey, to: DateKey) {
        val entries = scheduleDao.getEntriesBetween(from.value, to.value)
        val shells = entries.filter { it.stepIndexes.isEmpty() && it.pointer == 0 && !it.setStarted && it.status == "pending" }
            .filter { scheduleDao.countSetRecords(it.id) == 0 }
        shells.forEach { scheduleDao.deleteEntry(it.id) }
    }

    // ---------------------------------------------------------------- 读取

    /** 某一天的安排（带任务定义）。 */
    suspend fun getDayEntries(date: DateKey): List<DayEntry> = withContext(Dispatchers.IO) {
        val tasks = getTasks().associateBy { it.id }
        val entries = scheduleDao.getEntriesOnDate(date.value)
        val sets = scheduleDao.getSetRecordsFor(entries.map { it.id }).groupBy { it.entryId }
        entries.mapNotNull { entity ->
            val task = tasks[entity.taskId] ?: return@mapNotNull null
            DayEntry(entity.toDomain(sets[entity.id].orEmpty()), task)
        }
    }

    /** 一周 7 天的安排，key 为日期。 */
    suspend fun getWeekEntries(days: List<DateKey>): Map<DateKey, List<DayEntry>> = withContext(Dispatchers.IO) {
        val tasks = getTasks().associateBy { it.id }
        val result = linkedMapOf<DateKey, List<DayEntry>>()
        days.forEach { day ->
            val entries = scheduleDao.getEntriesOnDate(day.value)
            val sets = scheduleDao.getSetRecordsFor(entries.map { it.id }).groupBy { it.entryId }
            result[day] = entries.mapNotNull { entity ->
                val task = tasks[entity.taskId] ?: return@mapNotNull null
                DayEntry(entity.toDomain(sets[entity.id].orEmpty()), task)
            }
        }
        result
    }

    suspend fun getEntry(entryId: String): ScheduleEntry? = withContext(Dispatchers.IO) {
        val entity = scheduleDao.getEntry(entryId) ?: return@withContext null
        entity.toDomain(scheduleDao.getSetRecords(entryId))
    }

    // ---------------------------------------------------------------- 指定日期添加任务

    /**
     * 「添加任务到指定日期」：新建一条独立日程（不参与重复规则）。
     * 同一天同一任务已存在（且不是已跳过）时返回 false，与原型提示一致。
     */
    suspend fun addTaskToDate(taskId: String, date: DateKey, stepIndexes: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            val existing = scheduleDao.findEntry(taskId, date.value)
            if (existing != null && existing.status != EntryStatus.SKIPPED.storageValue) return@withContext false
            val now = System.currentTimeMillis()
            scheduleDao.insertEntry(
                ScheduleEntryEntity(
                    id = idFactory(),
                    taskId = taskId,
                    date = date.value,
                    stepIndexes = stepIndexes.sorted(),
                    status = EntryStatus.PENDING.storageValue,
                    pointer = 0,
                    setStarted = false,
                    updatedAt = now,
                )
            )
            true
        }

    /** 跳过/取消跳过某天的任务。 */
    suspend fun setEntryStatus(entryId: String, status: EntryStatus) = withContext(Dispatchers.IO) {
        scheduleDao.updateStatus(entryId, status.storageValue, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------- 训练即时保存

    /**
     * 训练进度即时落库（Bug 4 要求：不能只放在内存里）。
     * 写指针、是否已开始本组、以及最后一条组记录。
     */
    suspend fun persistWorkout(session: WorkoutSession, previousRecordCount: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        scheduleDao.updateProgress(
            entryId = session.entryId,
            pointer = session.pointer,
            setStarted = session.setStarted,
            now = now,
        )
        val entry = scheduleDao.getEntry(session.entryId) ?: return@withContext
        if (entry.status != EntryStatus.IN_PROGRESS.storageValue) {
            scheduleDao.updateEntry(entry.copy(status = EntryStatus.IN_PROGRESS.storageValue, updatedAt = now))
        }
        // 撤销时记录变少 → 删掉多余的组记录
        if (session.records.size < previousRecordCount) {
            scheduleDao.deleteSetRecordsFrom(session.entryId, session.records.size)
        }
        session.records.forEachIndexed { index, record ->
            scheduleDao.upsertSetRecord(
                SetRecordEntity(
                    entryId = session.entryId,
                    recordIndex = index,
                    stepIndex = record.stepIndex,
                    setIndex = record.setIndex,
                    targetReps = record.targetReps,
                    actualReps = record.actualReps,
                    skipped = record.skipped,
                )
            )
        }
    }

    /** 开始/恢复训练：把状态置为进行中。 */
    suspend fun markWorkoutStarted(entryId: String) = withContext(Dispatchers.IO) {
        scheduleDao.updateStatus(entryId, EntryStatus.IN_PROGRESS.storageValue, System.currentTimeMillis())
    }

    /**
     * 结束训练：写入最终状态与历史记录（Bug 4）。
     * 完成 → completed；有跳过/未做满 → incomplete（界面红色「提前结束」）。
     */
    suspend fun finishWorkout(task: TaskDef, session: WorkoutSession): HistoryRecord = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val status = WorkoutEngine.finalStatus(session)
        scheduleDao.updateProgress(session.entryId, session.pointer, false, now)
        scheduleDao.updateStatus(session.entryId, status.storageValue, now)

        val history = WorkoutEngine.buildHistory(idFactory(), task, session, session.sequence.map { it.stepIndex }.distinct())
        val recordId = history.id
        historyDao.replaceForEntry(
            record = HistoryRecordEntity(
                id = recordId,
                date = history.date.value,
                entryId = session.entryId,
                taskId = task.id,
                taskName = history.taskName,
                completed = history.completed,
                createdAt = now,
            ),
            steps = history.steps.mapIndexed { index, step ->
                HistoryStepEntity(
                    historyId = recordId,
                    stepIndex = index,
                    name = step.name,
                    plannedSets = step.plannedSets,
                    targetReps = step.targetReps,
                )
            },
            sets = history.steps.flatMapIndexed { index, step ->
                step.actualReps.mapIndexed { setIndex, reps ->
                    HistorySetEntity(
                        historyId = recordId,
                        stepIndex = index,
                        setIndex = setIndex,
                        actualReps = reps,
                        skipped = reps == 0,
                    )
                }
            },
        )
        history
    }

    /** 首页「完成」按钮：直接标记完成并补写历史。 */
    suspend fun completeEntryNow(task: TaskDef, entry: ScheduleEntry): HistoryRecord = withContext(Dispatchers.IO) {
        val session = WorkoutEngine.start(task, entry).copy(pointer = WorkoutEngine.buildSequence(task, entry.stepIndexes).size)
        finishWorkout(task, session)
    }

    // ---------------------------------------------------------------- 历史

    suspend fun getHistory(): List<HistoryDetail> = withContext(Dispatchers.IO) {
        historyDao.getHistory().map { entity ->
            HistoryDetail(entity.toDomain(historyDao.getHistorySteps(entity.id), historyDao.getHistorySets(entity.id)))
        }
    }

    suspend fun getHistoryDetail(id: String): HistoryDetail? = withContext(Dispatchers.IO) {
        val entity = historyDao.getHistoryRecord(id) ?: return@withContext null
        HistoryDetail(entity.toDomain(historyDao.getHistorySteps(id), historyDao.getHistorySets(id)))
    }

    // ---------------------------------------------------------------- 逾期任务

    /**
     * 逾期任务分组（原型 getBacklogGroups）：
     * 日期早于今天、状态为 pending 或 inProgress 的条目，按任务分组。
     * 用真实今天而不是「模拟日期」，避免误把未来任务当成堆积。
     */
    suspend fun getBacklogGroups(): Map<TaskDef, List<ScheduleEntry>> = withContext(Dispatchers.IO) {
        val todayKey = today()
        val tasks = getTasks().associateBy { it.id }
        val result = linkedMapOf<TaskDef, List<ScheduleEntry>>()
        val from = todayKey.minusDays(370)
        val entries = scheduleDao.getEntriesBetween(from.value, todayKey.minusDays(1).value)
        val sets = scheduleDao.getSetRecordsFor(entries.map { it.id }).groupBy { it.entryId }
        entries.filter { it.status == EntryStatus.PENDING.storageValue || it.status == EntryStatus.IN_PROGRESS.storageValue }
            .groupBy { it.taskId }
            .forEach { (taskId, list) ->
                val task = tasks[taskId] ?: return@forEach
                result[task] = list.sortedBy { it.date }
                    .map { it.toDomain(sets[it.id].orEmpty()) }
            }
        result
    }

    /** 逾期处理：跳过所有堆积条目。 */
    suspend fun backlogSkip(entryIds: List<String>) = withContext(Dispatchers.IO) {
        entryIds.forEach { scheduleDao.updateStatus(it, EntryStatus.SKIPPED.storageValue, System.currentTimeMillis()) }
    }

    /**
     * 导出全部数据为完整 JSON（设置页用，可自行备份/查看）。
     *
     * 用轻量手写序列化而不是引入 JSON 库：字段少、结构固定，
     * 手写能避免再加一个依赖，也能精确控制输出字段。
     */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val tasks = taskDao.getTasks()
        val steps = taskDao.getAllSteps().groupBy { it.taskId }
        val patterns = taskDao.getAllPatterns().groupBy { it.taskId }
        val entries = scheduleDao.getEntriesBetween("0000-01-01", "9999-12-31")
        val sets = scheduleDao.getAllSetRecords().groupBy { it.entryId }
        val history = historyDao.getHistory()
        val historySteps = historyDao.getAllHistorySteps().groupBy { it.historyId }
        val historySets = historyDao.getAllHistorySets().groupBy { it.historyId }

        buildString {
            append("{\n")
            append("\"formatVersion\": 1,\n")
            append("\"exportedAt\": ").append(System.currentTimeMillis()).append(",\n")
            append("\"app\": \"task-assistant-android\",\n")

            // 任务 + 步骤 + 每周分配
            append("\"tasks\": [\n")
            tasks.forEachIndexed { taskIndex, task ->
                append("  {\n")
                append("    \"id\": ").append(jsonString(task.id)).append(",\n")
                append("    \"name\": ").append(jsonString(task.name)).append(",\n")
                append("    \"orderType\": ").append(jsonString(task.orderType)).append(",\n")
                append("    \"repeat\": ").append(jsonString(task.repeat)).append(",\n")
                append("    \"startDate\": ").append(jsonString(task.startDate)).append(",\n")
                append("    \"endDate\": ").append(jsonNullableString(task.endDate)).append(",\n")
                append("    \"restEnabled\": ").append(task.restEnabled).append(",\n")
                append("    \"restSeconds\": ").append(task.restSeconds).append(",\n")
                append("    \"steps\": [")
                append(
                    steps[task.id].orEmpty().sortedBy { it.stepIndex }.joinToString(", ") { step ->
                        """{"index": ${step.stepIndex}, "name": ${jsonString(step.name)}, "sets": ${step.sets}, "reps": ${step.reps}}"""
                    }
                )
                append("],\n")
                append("    \"weeklyPattern\": {")
                append(
                    patterns[task.id].orEmpty().groupBy { it.isoDayOfWeek }
                        .entries.sortedBy { it.key }
                        .joinToString(", ") { (day, list) ->
                            "\"$day\": [${list.map { it.stepIndex }.distinct().sorted().joinToString(", ")}]"
                        }
                )
                append("}\n")
                append("  }").append(if (taskIndex == tasks.lastIndex) "\n" else ",\n")
            }
            append("],\n")

            // 日程条目 + 组记录
            append("\"entries\": [\n")
            entries.forEachIndexed { entryIndex, entry ->
                append("  {\n")
                append("    \"id\": ").append(jsonString(entry.id)).append(",\n")
                append("    \"taskId\": ").append(jsonString(entry.taskId)).append(",\n")
                append("    \"date\": ").append(jsonString(entry.date)).append(",\n")
                append("    \"stepIndexes\": [").append(entry.stepIndexes.joinToString(", ")).append("],\n")
                append("    \"status\": ").append(jsonString(entry.status)).append(",\n")
                append("    \"pointer\": ").append(entry.pointer).append(",\n")
                append("    \"setStarted\": ").append(entry.setStarted).append(",\n")
                append("    \"sets\": [")
                append(
                    sets[entry.id].orEmpty().sortedBy { it.recordIndex }.joinToString(", ") { record ->
                        """{"stepIndex": ${record.stepIndex}, "setIndex": ${record.setIndex}, "targetReps": ${record.targetReps}, "actualReps": ${record.actualReps}, "skipped": ${record.skipped}}"""
                    }
                )
                append("]\n")
                append("  }").append(if (entryIndex == entries.lastIndex) "\n" else ",\n")
            }
            append("],\n")

            // 训练历史
            append("\"history\": [\n")
            history.forEachIndexed { historyIndex, record ->
                append("  {\n")
                append("    \"id\": ").append(jsonString(record.id)).append(",\n")
                append("    \"date\": ").append(jsonString(record.date)).append(",\n")
                append("    \"entryId\": ").append(jsonNullableString(record.entryId)).append(",\n")
                append("    \"taskName\": ").append(jsonString(record.taskName)).append(",\n")
                append("    \"completed\": ").append(record.completed).append(",\n")
                append("    \"steps\": [")
                append(
                    historySteps[record.id].orEmpty().sortedBy { it.stepIndex }.joinToString(", ") { step ->
                        val actual = historySets[record.id].orEmpty()
                            .filter { it.stepIndex == step.stepIndex }
                            .sortedBy { it.setIndex }
                            .map { if (it.skipped) 0 else it.actualReps }
                        """{"name": ${jsonString(step.name)}, "plannedSets": ${step.plannedSets}, "targetReps": ${step.targetReps}, "actualReps": [${actual.joinToString(", ")}]}"""
                    }
                )
                append("]\n")
                append("  }").append(if (historyIndex == history.lastIndex) "\n" else ",\n")
            }
            append("]\n")

            append("}\n")
        }
    }

    /** JSON 字符串转义（含控制字符）。 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonNullableString(value: String?): String =
        if (value == null) "null" else jsonString(value)

    /** 清空生成游标（任务定义大改后用）。 */
    suspend fun resetCursors() = withContext(Dispatchers.IO) { taskDao.clearAllCursors() }
}
