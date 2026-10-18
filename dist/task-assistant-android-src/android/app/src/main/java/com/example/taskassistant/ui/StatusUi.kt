package com.example.taskassistant.ui

import com.example.taskassistant.core.EntryStatus

/** 与原型 statusText / CSS 类一致的显示映射。 */
object StatusUi {

    fun label(status: EntryStatus): String = when (status) {
        EntryStatus.PENDING -> "待做"
        EntryStatus.COMPLETED -> "完成"
        EntryStatus.SKIPPED -> "跳过"
        EntryStatus.IN_PROGRESS -> "进行中"
        EntryStatus.INCOMPLETE -> "未完成"
    }

    /** 颜色（ARGB），与原型 .status-* 的颜色语义一致。 */
    fun colorArgb(status: EntryStatus): Long = when (status) {
        EntryStatus.PENDING -> 0xFF888888
        EntryStatus.COMPLETED -> 0xFF4CAF50
        EntryStatus.SKIPPED -> 0xFFE53935
        EntryStatus.IN_PROGRESS -> 0xFFFF9800
        EntryStatus.INCOMPLETE -> 0xFFE53935
    }
}

/** 日期显示工具，保持与原型 formatDate / formatShort 相同的输出。 */
object DateUi {

    private val weekdays = listOf("日", "一", "二", "三", "四", "五", "六")

    fun full(value: String): String {
        val parts = value.split("-")
        if (parts.size != 3) return value
        val year = parts[0].toIntOrNull() ?: return value
        val month = parts[1].toIntOrNull() ?: return value
        val day = parts[2].toIntOrNull() ?: return value
        val dow = java.time.LocalDate.of(year, month, day).dayOfWeek.value % 7 // 1..7 -> 1..6,0
        return "$value 周${weekdays[dow]}"
    }

    fun short(value: String): String {
        val parts = value.split("-")
        if (parts.size != 3) return value
        val month = parts[1].toIntOrNull() ?: return value
        val day = parts[2].toIntOrNull() ?: return value
        return "$month-$day"
    }
}
