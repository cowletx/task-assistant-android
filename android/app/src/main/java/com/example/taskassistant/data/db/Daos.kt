package com.example.taskassistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 任务、步骤、每周分配。 */
@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY createdAt ASC")
    suspend fun getTasks(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): TaskEntity?

    @Query("SELECT * FROM task_steps ORDER BY taskId ASC, stepIndex ASC")
    suspend fun getAllSteps(): List<StepEntity>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepIndex ASC")
    suspend fun getSteps(taskId: String): List<StepEntity>

    @Query("SELECT * FROM task_weekly_pattern ORDER BY taskId ASC, isoDayOfWeek ASC, stepIndex ASC")
    suspend fun getAllPatterns(): List<WeeklyPatternEntity>

    @Query("SELECT * FROM task_weekly_pattern WHERE taskId = :taskId")
    suspend fun getPatterns(taskId: String): List<WeeklyPatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(task: TaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<StepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPatterns(patterns: List<WeeklyPatternEntity>)

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteSteps(taskId: String)

    @Query("DELETE FROM task_weekly_pattern WHERE taskId = :taskId")
    suspend fun deletePatterns(taskId: String)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    /** 任务定义变化时清空该任务的生成游标，强制重算。 */
    @Query("DELETE FROM roll_cursors WHERE taskId = :taskId")
    suspend fun deleteCursor(taskId: String)

    @Query("DELETE FROM roll_cursors")
    suspend fun clearAllCursors()

    @Transaction
    suspend fun replaceTaskGraph(
        task: TaskEntity,
        steps: List<StepEntity>,
        patterns: List<WeeklyPatternEntity>,
    ) {
        upsertTask(task)
        deleteSteps(task.id)
        if (steps.isNotEmpty()) upsertSteps(steps)
        deletePatterns(task.id)
        if (patterns.isNotEmpty()) upsertPatterns(patterns)
        // 定义变化后游标失效，下次生成时重新计算
        deleteCursor(task.id)
    }
}

/** 日程条目与单组记录。 */
@Dao
interface ScheduleDao {

    /** 取某日期区间内的全部条目；生成前一次性载入，避免逐日查询。 */
    @Query("SELECT * FROM schedule_entries WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    suspend fun getEntriesBetween(from: String, to: String): List<ScheduleEntryEntity>

    @Query("SELECT * FROM schedule_entries WHERE date = :date")
    fun observeEntriesOnDate(date: String): Flow<List<ScheduleEntryEntity>>

    @Query("SELECT * FROM schedule_entries WHERE date = :date")
    suspend fun getEntriesOnDate(date: String): List<ScheduleEntryEntity>

    @Query("SELECT * FROM schedule_entries WHERE id = :id")
    suspend fun getEntry(id: String): ScheduleEntryEntity?

    @Query("SELECT * FROM schedule_entries WHERE taskId = :taskId AND date = :date LIMIT 1")
    suspend fun findEntry(taskId: String, date: String): ScheduleEntryEntity?

    /** 已有的 (taskId, date) 组合，用于生成前构建去重集合。 */
    @Query("SELECT taskId || '@' || date FROM schedule_entries WHERE date BETWEEN :from AND :to")
    suspend fun getExistingKeys(from: String, to: String): List<String>

    @Query("SELECT MIN(date) FROM schedule_entries")
    suspend fun earliestDate(): String?

    @Query("SELECT MAX(date) FROM schedule_entries")
    suspend fun latestDate(): String?

    @Query("SELECT COUNT(*) FROM schedule_entries")
    suspend fun countEntries(): Int

    /**
     * 只插入缺失条目：已存在同 (taskId, date) 时忽略。
     * 这是「生成不覆盖已有训练数据」的存储层保证。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(entries: List<ScheduleEntryEntity>): List<Long>

    /** 仅用于「添加任务到指定日期」等明确的新建场景。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: ScheduleEntryEntity)

    @Update
    suspend fun updateEntry(entry: ScheduleEntryEntity)

    @Query("UPDATE schedule_entries SET status = :status, updatedAt = :now WHERE id = :entryId")
    suspend fun updateStatus(entryId: String, status: String, now: Long)

    /** 训练进度即时保存：指针 + 是否已开始本组。 */
    @Query("UPDATE schedule_entries SET pointer = :pointer, setStarted = :setStarted, updatedAt = :now WHERE id = :entryId")
    suspend fun updateProgress(entryId: String, pointer: Int, setStarted: Boolean, now: Long)

    @Query("DELETE FROM schedule_entries WHERE taskId = :taskId AND date > :after AND status = 'pending' AND pointer = 0 AND setStarted = 0 AND id NOT IN (SELECT entryId FROM set_records)")
    suspend fun deleteStalePendingAfter(taskId: String, after: String): Int

    @Query("DELETE FROM schedule_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    // ---- 单组记录 ----

    @Query("SELECT * FROM set_records WHERE entryId = :entryId ORDER BY recordIndex ASC")
    suspend fun getSetRecords(entryId: String): List<SetRecordEntity>

    @Query("SELECT * FROM set_records WHERE entryId IN (:entryIds) ORDER BY entryId ASC, recordIndex ASC")
    suspend fun getSetRecordsFor(entryIds: List<String>): List<SetRecordEntity>

    @Query("SELECT * FROM set_records ORDER BY entryId ASC, recordIndex ASC")
    suspend fun getAllSetRecords(): List<SetRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetRecord(record: SetRecordEntity)

    @Query("DELETE FROM set_records WHERE entryId = :entryId AND recordIndex >= :fromIndex")
    suspend fun deleteSetRecordsFrom(entryId: String, fromIndex: Int)

    @Query("SELECT COUNT(*) FROM set_records WHERE entryId = :entryId")
    suspend fun countSetRecords(entryId: String): Int
}

/** 训练历史。 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_records ORDER BY date DESC, createdAt DESC")
    fun observeHistory(): Flow<List<HistoryRecordEntity>>

    @Query("SELECT * FROM history_records ORDER BY date DESC, createdAt DESC")
    suspend fun getHistory(): List<HistoryRecordEntity>

    @Query("SELECT * FROM history_records WHERE id = :id")
    suspend fun getHistoryRecord(id: String): HistoryRecordEntity?

    @Query("SELECT * FROM history_steps WHERE historyId = :historyId ORDER BY stepIndex ASC")
    suspend fun getHistorySteps(historyId: String): List<HistoryStepEntity>

    @Query("SELECT * FROM history_sets WHERE historyId = :historyId ORDER BY stepIndex ASC, setIndex ASC")
    suspend fun getHistorySets(historyId: String): List<HistorySetEntity>

    @Query("SELECT * FROM history_steps ORDER BY historyId ASC, stepIndex ASC")
    suspend fun getAllHistorySteps(): List<HistoryStepEntity>

    @Query("SELECT * FROM history_sets ORDER BY historyId ASC, stepIndex ASC, setIndex ASC")
    suspend fun getAllHistorySets(): List<HistorySetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: HistoryRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSteps(steps: List<HistoryStepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSets(sets: List<HistorySetEntity>)

    @Query("DELETE FROM history_records WHERE entryId = :entryId")
    suspend fun deleteByEntry(entryId: String)

    @Transaction
    suspend fun replaceForEntry(
        record: HistoryRecordEntity,
        steps: List<HistoryStepEntity>,
        sets: List<HistorySetEntity>,
    ) {
        record.entryId?.let { deleteByEntry(it) }
        upsertRecord(record)
        if (steps.isNotEmpty()) upsertSteps(steps)
        if (sets.isNotEmpty()) upsertSets(sets)
    }
}

/** 设置与游标。 */
@Dao
interface SettingsDao {

    @Query("SELECT value FROM app_settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT * FROM roll_cursors")
    suspend fun getCursors(): List<RollCursorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(cursor: RollCursorEntity)
}
