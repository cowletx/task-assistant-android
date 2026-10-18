package com.example.taskassistant.core

/** 训练序列中的一个位置（原型的 sequence[i]）。 */
data class SetSlot(
    val stepIndex: Int,
    val setIndex: Int,
    val targetReps: Int,
)

/** 训练会话的持久化状态（对应原型 entry.sets / entry.pointer / entry.setStarted）。 */
data class WorkoutSession(
    val entryId: String,
    val taskId: String,
    val date: DateKey,
    val sequence: List<SetSlot>,
    val pointer: Int = 0,
    val records: List<SetRecord> = emptyList(),
    val setStarted: Boolean = false,
    val restSecondsLeft: Int = 0,
) {
    val isFinishedBySets: Boolean get() = pointer >= sequence.size

    val currentSlot: SetSlot? get() = sequence.getOrNull(pointer)

    /** 进度百分比，与原型的 Math.round(pointer / sequence.length * 100) 一致。 */
    val progressPercent: Int
        get() = if (sequence.isEmpty()) 0 else Math.round(pointer.toFloat() / sequence.size * 100)

    /**
     * 提前结束判定（Bug 4 的核心规则，必须与原型一致）：
     * 只有「所有计划组都真实做完、且没有任何一组被跳过」才算完成。
     */
    val isAllRealDone: Boolean
        get() = records.size == sequence.size && records.all { !it.skipped }
}

/**
 * 训练流程引擎：把原型的 buildSequence / completeSet / skipSet / undoLast / finishWorkout
 * 这些散落在 UI 函数里的规则集中成可测试的纯逻辑。
 *
 * 关键规则（保留原型行为，并补充即时持久化）：
 *  - 顺序：rotate 为 A1-B1-C1-A2...（按最大组数轮换）；traditional 为 A1-A2-A3-B1...
 *  - 两阶段按钮：先「开始本组」（setStarted=true），再「完成本组」（写入一条 SetRecord）；
 *  - 跳过本组：写入一条 skipped=true 的记录（实际次数记 0），不算完成；
 *  - 撤销：弹出最后一条记录并把指针回退一格；
 *  - 结束训练：全部真实做完 → COMPLETED，否则 → INCOMPLETE（界面红色「提前结束」）。
 */
object WorkoutEngine {

    /** 构造训练序列，对应原型 buildSequence(task, stepIndexes)。 */
    fun buildSequence(task: TaskDef, stepIndexes: List<Int>): List<SetSlot> {
        val valid = stepIndexes.filter { it >= 0 && it < task.steps.size }.distinct().sorted()
        if (valid.isEmpty()) return emptyList()
        val slots = mutableListOf<SetSlot>()

        if (task.orderType == OrderType.ROTATE) {
            val maxSets = valid.maxOf { task.steps[it].sets }
            for (setIdx in 0 until maxSets) {
                valid.forEach { stepIndex ->
                    val step = task.steps[stepIndex]
                    if (setIdx < step.sets) {
                        slots += SetSlot(stepIndex, setIdx, step.reps)
                    }
                }
            }
        } else {
            valid.forEach { stepIndex ->
                val step = task.steps[stepIndex]
                for (setIdx in 0 until step.sets) {
                    slots += SetSlot(stepIndex, setIdx, step.reps)
                }
            }
        }
        return slots
    }

    /** 开始或恢复训练。已保存的进度会被带回来（原型 startWorkout 的行为）。 */
    fun start(task: TaskDef, entry: ScheduleEntry): WorkoutSession {
        val sequence = buildSequence(task, entry.stepIndexes)
        return WorkoutSession(
            entryId = entry.id,
            taskId = task.id,
            date = entry.date,
            sequence = sequence,
            pointer = entry.pointer.coerceIn(0, sequence.size),
            records = entry.sets,
            setStarted = entry.setStarted,
            restSecondsLeft = 0,
        )
    }

    /** 点击「开始本组」：只置位，不写记录。 */
    fun markSetStarted(session: WorkoutSession): WorkoutSession =
        session.copy(setStarted = true)

    /**
     * 点击「完成本组」：写入一条真实记录，指针前进，并给出休息秒数。
     * 返回 null 表示当前没有可完成的组。
     */
    fun completeSet(
        session: WorkoutSession,
        task: TaskDef,
        actualReps: Int,
    ): WorkoutSession? {
        val slot = session.currentSlot ?: return null
        val record = SetRecord(
            stepIndex = slot.stepIndex,
            setIndex = slot.setIndex,
            targetReps = slot.targetReps,
            actualReps = actualReps.coerceAtLeast(0),
            skipped = false,
        )
        val nextPointer = session.pointer + 1
        val rest = if (task.restEnabled && nextPointer < session.sequence.size) task.restSeconds else 0
        return session.copy(
            pointer = nextPointer,
            records = session.records + record,
            setStarted = false,
            restSecondsLeft = rest,
        )
    }

    /** 跳过本组：写入一条 skipped 记录，指针前进，不进入休息。 */
    fun skipSet(session: WorkoutSession): WorkoutSession? {
        val slot = session.currentSlot ?: return null
        val record = SetRecord(
            stepIndex = slot.stepIndex,
            setIndex = slot.setIndex,
            targetReps = slot.targetReps,
            actualReps = 0,
            skipped = true,
        )
        return session.copy(
            pointer = session.pointer + 1,
            records = session.records + record,
            setStarted = false,
            restSecondsLeft = 0,
        )
    }

    /** 撤销上一组：弹出一条记录，指针回退，不清除其余进度。 */
    fun undoLast(session: WorkoutSession): WorkoutSession? {
        if (session.records.isEmpty()) return null
        return session.copy(
            pointer = (session.pointer - 1).coerceAtLeast(0),
            records = session.records.dropLast(1),
            setStarted = false,
            restSecondsLeft = 0,
        )
    }

    /** 休息倒计时递减一秒。 */
    fun tickRest(session: WorkoutSession): WorkoutSession =
        session.copy(restSecondsLeft = (session.restSecondsLeft - 1).coerceAtLeast(0))

    /** 跳过休息。 */
    fun skipRest(session: WorkoutSession): WorkoutSession = session.copy(restSecondsLeft = 0)

    /**
     * 结束训练时的最终状态（Bug 4）：
     *  - 全部计划组真实完成 → COMPLETED
     *  - 否则（有跳过 / 有没做的组 / 提前结束）→ INCOMPLETE
     */
    fun finalStatus(session: WorkoutSession): EntryStatus =
        if (session.isAllRealDone) EntryStatus.COMPLETED else EntryStatus.INCOMPLETE

    /**
     * 生成历史记录，对应原型 finishWorkout 里的 map 组装 + 补齐未做步骤。
     * 未做或未记录的步骤保留空 actual 列表，界面显示「未记录」，不会被误标为已完成。
     */
    fun buildHistory(
        recordId: String,
        task: TaskDef,
        session: WorkoutSession,
        entryStepIndexes: List<Int>,
    ): HistoryRecord {
        val byStep = linkedMapOf<Int, MutableList<Int>>()
        session.records.forEach { record ->
            byStep.getOrPut(record.stepIndex) { mutableListOf() }
                .add(if (record.skipped) 0 else record.actualReps)
        }
        val planned = (entryStepIndexes + byStep.keys).distinct().sorted()
        val steps = planned.mapNotNull { stepIndex ->
            val step = task.steps.getOrNull(stepIndex) ?: return@mapNotNull null
            HistoryStep(
                name = step.name,
                plannedSets = step.sets,
                targetReps = step.reps,
                actualReps = byStep[stepIndex]?.toList().orEmpty(),
            )
        }
        return HistoryRecord(
            id = recordId,
            date = session.date,
            entryId = session.entryId,
            taskName = task.name,
            steps = steps,
            completed = session.isAllRealDone,
        )
    }

    /** 某个步骤在本次训练中已记录的组数（历史界面用：done/plannedSets）。 */
    fun recordedSetsOf(record: HistoryStep): Int = record.actualReps.count { it > 0 }
}
