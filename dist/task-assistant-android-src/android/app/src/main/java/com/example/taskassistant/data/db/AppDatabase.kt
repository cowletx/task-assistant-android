package com.example.taskassistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        StepEntity::class,
        WeeklyPatternEntity::class,
        ScheduleEntryEntity::class,
        SetRecordEntity::class,
        HistoryRecordEntity::class,
        HistoryStepEntity::class,
        HistorySetEntity::class,
        SettingEntity::class,
        RollCursorEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    abstract fun scheduleDao(): ScheduleDao

    abstract fun historyDao(): HistoryDao

    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DB_NAME = "task_assistant.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // 外键约束默认关闭，日程/组数/历史之间的级联删除依赖它
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
