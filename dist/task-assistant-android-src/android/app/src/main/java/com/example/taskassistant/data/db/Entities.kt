package com.example.taskassistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 任务表。对应原型 state.tasks[i]。
 *
 * 原型把 steps / weeklyPattern 直接塞在任务对象里；关系型库里拆成
 * task_steps 与 task_weekly_pattern 两张子表，避免用 JSON 字符串存结构化数据。
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 'rotate' | 'traditional' */
    val orderType: String,
    /** 'once' | 'daily' | 'weekly' */
    val repeat: String,
    /** yyyy-MM-dd */
    val startDate: String,
    /** yyyy-MM-dd；null 表示不设结束日 */
    val endDate: String?,
    val restEnabled: Boolean,
    val restSeconds: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 训练步骤表，index 保留原型 steps 数组的下标语义（weeklyPattern 引用它）。 */
@Entity(
    tableName = "task_steps",
    primaryKeys = ["taskId", "stepIndex"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class StepEntity(
    val taskId: String,
    val stepIndex: Int,
    val name: String,
    val sets: Int,
    val reps: Int,
)

/** 每周步骤分配：某任务某个星期几要做哪几个步骤。 */
@Entity(
    tableName = "task_weekly_pattern",
    primaryKeys = ["taskId", "isoDayOfWeek", "stepIndex"],
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("taskId")],
)
data class WeeklyPatternEntity(
    val taskId: String,
    /** ISO 星期：1=周一 … 7=周日（与原型 isoDay 一致） */
    val isoDayOfWeek: Int,
    val stepIndex: Int,
)

/**
 * 日程表。对应原型 state.schedule[date][i]。
 *
 * stepIndexes 用逗号分隔的字符串存储（Room 的 TypeConverter 负责转换），
 * 因为它是「该做哪几个步骤」的快照，随生成时间固定，不需要参与查询条件。
 */
@Entity(
    tableName = "schedule_entries",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskId", "date"], unique = true),
        Index("date"),
    ],
)
data class ScheduleEntryEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val date: String,
    val stepIndexes: List<Int>,
    /** 'pending' | 'completed' | 'skipped' | 'inProgress' | 'incomplete' */
    val status: String,
    val pointer: Int,
    val setStarted: Boolean,
    val updatedAt: Long,
)

/** 单组训练记录。对应原型 entry.sets[i]，独立成表以便逐步即时保存。 */
@Entity(
    tableName = "set_records",
    primaryKeys = ["entryId", "recordIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entryId")],
)
data class SetRecordEntity(
    val entryId: String,
    /** 记录顺序（等于原型 sets 数组下标） */
    val recordIndex: Int,
    val stepIndex: Int,
    val setIndex: Int,
    val targetReps: Int,
    val actualReps: Int,
    val skipped: Boolean,
)

/** 训练历史表。对应原型 state.history[i]。 */
@Entity(
    tableName = "history_records",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("entryId"), Index("date")],
)
data class HistoryRecordEntity(
    @PrimaryKey val id: String,
    val date: String,
    val entryId: String?,
    val taskId: String?,
    val taskName: String,
    /** false 表示提前结束，界面用红色「提前结束」 */
    val completed: Boolean,
    val createdAt: Long,
)

/** 历史里的每个步骤（含未做步骤的空记录）。 */
@Entity(
    tableName = "history_steps",
    primaryKeys = ["historyId", "stepIndex"],
    foreignKeys = [
        ForeignKey(
            entity = HistoryRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("historyId")],
)
data class HistoryStepEntity(
    val historyId: String,
    val stepIndex: Int,
    val name: String,
    val plannedSets: Int,
    val targetReps: Int,
)

/** 历史里每一组的实际次数；actualReps=0 且 skipped=true 表示跳过，没有行表示未记录。 */
@Entity(
    tableName = "history_sets",
    primaryKeys = ["historyId", "stepIndex", "setIndex"],
    foreignKeys = [
        ForeignKey(
            entity = HistoryRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("historyId")],
)
data class HistorySetEntity(
    val historyId: String,
    val stepIndex: Int,
    val setIndex: Int,
    val actualReps: Int,
    val skipped: Boolean,
)

/** 键值设置表（通知开关、振动开关等）。 */
@Entity(tableName = "app_settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * 生成游标表：跨进程重启保留增量生成进度，避免每次启动都重扫一整年。
 * 对应原型内存中的 rollCursors（原型重启即丢失，这里做持久化，属于可靠性改进）。
 */
@Entity(tableName = "roll_cursors")
data class RollCursorEntity(
    @PrimaryKey val taskId: String,
    val forwardDate: String?,
    val backwardDate: String?,
)
