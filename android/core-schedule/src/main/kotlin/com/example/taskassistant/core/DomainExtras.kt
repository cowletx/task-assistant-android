package com.example.taskassistant.core

/** 某条安排一共要做多少组（对应原型 totalSetsOfEntry）。 */
fun TaskDef.totalSetsOf(entry: ScheduleEntry): Int =
    entry.stepIndexes.sumOf { index -> steps.getOrNull(index)?.sets ?: 0 }

/** 某条安排当天的步骤合计。 */
fun TaskDef.stepsOf(entry: ScheduleEntry): List<Step> =
    entry.stepIndexes.mapNotNull { steps.getOrNull(it) }

/** 任务的总组数（任务详情页用）。 */
fun TaskDef.totalSets(): Int = steps.sumOf { it.sets }

/** 任务在一周里被分配到的星期（用于列表摘要展示）。 */
fun TaskDef.assignedWeekdays(): List<Int> =
    if (repeat == RepeatMode.WEEKLY) normalizedPattern().keys.sorted() else emptyList()
