package com.example.taskassistant

import android.app.Application
import com.example.taskassistant.data.SettingsRepository
import com.example.taskassistant.data.TaskRepository
import com.example.taskassistant.data.db.AppDatabase

/**
 * 应用入口：只负责构建数据库与仓库，供 ViewModel 使用。
 * 没有依赖注入框架（保持依赖最少、构建最稳）。
 */
class TaskAssistantApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val repository: TaskRepository by lazy { TaskRepository(database) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.settingsDao()) }
}
