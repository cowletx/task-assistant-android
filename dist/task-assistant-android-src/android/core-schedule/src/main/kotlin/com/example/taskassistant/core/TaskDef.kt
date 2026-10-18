package com.example.taskassistant.core

/** 重复方式，对应原型 task.repeat 的 'once' / 'daily' / 'weekly'。 */
enum class RepeatMode {
    /** 仅一次，只生成 startDate 当天。 */
    ONCE,

    /** 每天重复，生成 [startDate, endDate] 内每一天。 */
    DAILY,

    /** 每周重复，只生成 weeklyPattern 指定星期的日期。 */
    WEEKLY;

    companion object {
        fun fromStorage(raw: String?): RepeatMode = when (raw) {
            "once" -> ONCE
            "daily" -> DAILY
            "weekly" -> WEEKLY
            else -> ONCE
        }
    }

    val storageValue: String
        get() = when (this) {
            ONCE -> "once"
            DAILY -> "daily"
            WEEKLY -> "weekly"
        }
}

/** 训练顺序，对应原型 task.orderType 的 'rotate' / 'traditional'。 */
enum class OrderType {
    /** 轮换式：A1-B1-C1-A2... */
    ROTATE,

    /** 传统式：A1-A2-A3-B1... */
    TRADITIONAL;

    companion object {
        fun fromStorage(raw: String?): OrderType = when (raw) {
            "traditional" -> TRADITIONAL
            else -> ROTATE
        }
    }

    val storageValue: String
        get() = if (this == ROTATE) "rotate" else "traditional"
}

/** 训练步骤（原型的 task.steps[i]）。 */
data class Step(
    val name: String,
    val sets: Int,
    val reps: Int,
)

/**
 * 任务定义（原型的 state.tasks[i]）。
 *
 * @param stepIndexesByIsoDay 每周任务的步骤分配，key 为 ISO 星期 1..7（对应原型 weeklyPattern 的数字键）。
 *        某天不在表里、或对应列表为空，都表示那天不做这个任务（不生成空安排）。
 * @param weeklyPatternFromStrings 兼容旧导出的 {"1":[...], "2":[...]} 形式。
 */
data class TaskDef(
    val id: String,
    val name: String,
    val orderType: OrderType = OrderType.ROTATE,
    val steps: List<Step> = emptyList(),
    val repeat: RepeatMode = RepeatMode.ONCE,
    val startDate: DateKey,
    /** 为空表示不设结束日，持续循环生成。 */
    val endDate: DateKey? = null,
    val stepIndexesByIsoDay: Map<Int, List<Int>> = emptyMap(),
    val restEnabled: Boolean = false,
    val restSeconds: Int = 60,
) {
    /** 任务在 [date] 是否为有效日期（含开始日与结束日）。 */
    fun isDateInRange(date: DateKey): Boolean {
        if (date < startDate) return false
        if (endDate != null && date > endDate) return false
        return true
    }

    /**
     * 规范化后的每周分配：只保留 1..7 且步骤索引在合法范围内的项。
     * 原型里 stepIndexesForDate 每次都做一次过滤，这里提前规范化，避免各处规则不一致。
     */
    fun normalizedPattern(): Map<Int, List<Int>> {
        if (repeat != RepeatMode.WEEKLY) return emptyMap()
        val maxIndex = steps.size
        return stepIndexesByIsoDay
            .filterKeys { it in 1..7 }
            .mapValues { (_, indexes) ->
                indexes.filter { it >= 0 && it < maxIndex }.distinct().sorted()
            }
            .filterValues { it.isNotEmpty() }
    }
}
