package com.example.taskassistant.data.mapper

import com.example.taskassistant.core.DateKey
import com.example.taskassistant.core.EntryStatus
import com.example.taskassistant.core.HistoryRecord
import com.example.taskassistant.core.HistoryStep
import com.example.taskassistant.core.OrderType
import com.example.taskassistant.core.RepeatMode
import com.example.taskassistant.core.ScheduleEntry
import com.example.taskassistant.core.SetRecord
import com.example.taskassistant.core.Step
import com.example.taskassistant.core.TaskDef
import com.example.taskassistant.data.db.HistoryRecordEntity
import com.example.taskassistant.data.db.HistorySetEntity
import com.example.taskassistant.data.db.HistoryStepEntity
import com.example.taskassistant.data.db.ScheduleEntryEntity
import com.example.taskassistant.data.db.SetRecordEntity
import com.example.taskassistant.data.db.StepEntity
import com.example.taskassistant.data.db.TaskEntity
import com.example.taskassistant.data.db.WeeklyPatternEntity

/**
 * 实体 ⇄ 领域模型的双向映射。
 *
 * 领域模型（core-schedule 模块）不依赖 Android/Room，所有跨层转换集中在这里，
 * 避免在仓库或界面里出现「字段拼错但编译通过」的隐性错误。
 */

fun TaskEntity.toDomain(
    steps: List<StepEntity>,
    patterns: List<WeeklyPatternEntity>,
): TaskDef = TaskDef(
    id = id,
    name = name,
    orderType = OrderType.fromStorage(orderType),
    steps = steps
        .sortedBy { it.stepIndex }
        .map { Step(name = it.name, sets = it.sets, reps = it.reps) },
    repeat = RepeatMode.fromStorage(repeat),
    startDate = DateKey(startDate),
    endDate = endDate?.let { DateKey(it) },
    // 只保留步骤索引仍合法的分配，避免任务删过步骤后引用越界
    stepIndexesByIsoDay = patterns
        .groupBy { it.isoDayOfWeek }
        .mapValues { (_, list) -> list.map { it.stepIndex }.distinct().sorted() },
    restEnabled = restEnabled,
    restSeconds = restSeconds,
)

fun TaskDef.toEntity(now: Long, createdAt: Long = now): TaskEntity = TaskEntity(
    id = id,
    name = name,
    orderType = orderType.storageValue,
    repeat = repeat.storageValue,
    startDate = startDate.value,
    endDate = endDate?.value,
    restEnabled = restEnabled,
    restSeconds = restSeconds,
    createdAt = createdAt,
    updatedAt = now,
)

fun TaskDef.toStepEntities(): List<StepEntity> = steps.mapIndexed { index, step ->
    StepEntity(taskId = id, stepIndex = index, name = step.name, sets = step.sets, reps = step.reps)
}

fun TaskDef.toPatternEntities(): List<WeeklyPatternEntity> {
    val maxIndex = steps.size
    return normalizedPattern().flatMap { (isoDay, indexes) ->
        indexes.filter { it in 0 until maxIndex }
            .map { WeeklyPatternEntity(taskId = id, isoDayOfWeek = isoDay, stepIndex = it) }
    }
}

fun ScheduleEntryEntity.toDomain(sets: List<SetRecordEntity>): ScheduleEntry = ScheduleEntry(
    id = id,
    taskId = taskId,
    date = DateKey(date),
    stepIndexes = stepIndexes,
    status = EntryStatus.fromStorage(status),
    sets = sets
        .sortedBy { it.recordIndex }
        .map { SetRecord(it.stepIndex, it.setIndex, it.targetReps, it.actualReps, it.skipped) },
    pointer = pointer,
    setStarted = setStarted,
)

fun ScheduleEntry.toEntity(now: Long): ScheduleEntryEntity = ScheduleEntryEntity(
    id = id,
    taskId = taskId,
    date = date.value,
    stepIndexes = stepIndexes,
    status = status.storageValue,
    pointer = pointer,
    setStarted = setStarted,
    updatedAt = now,
)

fun SetRecordEntity.toDomain(): SetRecord = SetRecord(stepIndex, setIndex, targetReps, actualReps, skipped)

fun HistoryRecordEntity.toDomain(steps: List<HistoryStepEntity>, sets: List<HistorySetEntity>): HistoryRecord {
    val setsByStep = sets.groupBy { it.stepIndex }
    return HistoryRecord(
        id = id,
        date = DateKey(date),
        entryId = entryId.orEmpty(),
        taskName = taskName,
        steps = steps.sortedBy { it.stepIndex }.map { step ->
            HistoryStep(
                name = step.name,
                plannedSets = step.plannedSets,
                targetReps = step.targetReps,
                // 只把「有记录的行」放进列表：没有行 = 未记录（界面显示未记录）
                actualReps = setsByStep[step.stepIndex]
                    ?.sortedBy { it.setIndex }
                    ?.map { if (it.skipped) 0 else it.actualReps }
                    .orEmpty(),
            )
        },
        completed = completed,
    )
}
