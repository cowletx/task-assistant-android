package com.example.taskassistant.core

/**
 * 日程条目状态。取值与原型保持一致（storageValue），便于以后从网页 localStorage 导入数据。
 */
enum class EntryStatus {
    /** 待完成。 */
    PENDING,

    /** 已完成（所有组都真实做完且没有跳过）。 */
    COMPLETED,

    /** 已跳过（用户主动跳过当天的任务）。 */
    SKIPPED,

    /** 训练中。 */
    IN_PROGRESS,

    /** 未完成（训练被提前结束）。界面用红色展示。 */
    INCOMPLETE;

    companion object {
        fun fromStorage(raw: String?): EntryStatus = when (raw) {
            "completed" -> COMPLETED
            "skipped" -> SKIPPED
            "inProgress" -> IN_PROGRESS
            "incomplete" -> INCOMPLETE
            else -> PENDING
        }
    }

    val storageValue: String
        get() = when (this) {
            PENDING -> "pending"
            COMPLETED -> "completed"
            SKIPPED -> "skipped"
            IN_PROGRESS -> "inProgress"
            INCOMPLETE -> "incomplete"
        }
}

/** 单组记录（原型的 entry.sets[i]）。 */
data class SetRecord(
    val stepIndex: Int,
    val setIndex: Int,
    val targetReps: Int,
    val actualReps: Int,
    /** 原型的 status: 'done' / 'skipped'。 */
    val skipped: Boolean = false,
)

/**
 * 日程条目（原型的 state.schedule[date][i]，以及 Android 端的 schedule_entries 表）。
 *
 * 这里只放「生成逻辑需要看到」的字段；训练进度的持久化由 Room 实体承载，
 * 但字段名与语义完全对应，保证生成逻辑不会因为换存储而改变行为。
 */
data class ScheduleEntry(
    val id: String,
    val taskId: String,
    val date: DateKey,
    val stepIndexes: List<Int>,
    val status: EntryStatus = EntryStatus.PENDING,
    val sets: List<SetRecord> = emptyList(),
    val pointer: Int = 0,
    val setStarted: Boolean = false,
) {
    /**
     * 这条安排是否已经「被用户动过」：完成 / 跳过 / 提前结束 / 进行中 /
     * 已开始过某一组 / 已记录过任何组数。
     *
     * Bug 2 的保护规则就是建立在这个判断上：凡是 isTouched 的条目，
     * 日程生成既不覆盖也不删除。
     */
    val isTouched: Boolean
        get() = status != EntryStatus.PENDING ||
            sets.isNotEmpty() ||
            pointer != 0 ||
            setStarted

    /** 步骤为空的空壳安排（每周任务取消勾选后可能残留），可以被清理。 */
    val isEmptyShell: Boolean
        get() = stepIndexes.isEmpty() &&
            sets.isEmpty() &&
            pointer == 0 &&
            status == EntryStatus.PENDING
}

/** 训练历史（原型的 state.history[i]）。 */
data class HistoryRecord(
    val id: String,
    val date: DateKey,
    val entryId: String,
    val taskName: String,
    val steps: List<HistoryStep>,
    /** false 表示提前结束，界面显示红色「提前结束」。 */
    val completed: Boolean,
)

data class HistoryStep(
    val name: String,
    val plannedSets: Int,
    val targetReps: Int,
    /** 每组实际次数；0 表示该组被跳过。空列表表示未记录。 */
    val actualReps: List<Int>,
)
