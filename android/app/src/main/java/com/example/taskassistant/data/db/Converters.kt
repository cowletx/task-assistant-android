package com.example.taskassistant.data.db

import androidx.room.TypeConverter

/** List<Int> ⇄ 逗号分隔字符串（用于 schedule_entries.stepIndexes）。 */
class Converters {

    @TypeConverter
    fun fromIntList(value: List<Int>?): String =
        value?.joinToString(separator = ",") ?: ""

    @TypeConverter
    fun toIntList(value: String?): List<Int> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    @TypeConverter
    fun fromBoolean(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    fun toBoolean(value: Int): Boolean = value != 0
}
