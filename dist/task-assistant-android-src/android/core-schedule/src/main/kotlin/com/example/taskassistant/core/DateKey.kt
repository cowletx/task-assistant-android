package com.example.taskassistant.core

import java.time.LocalDate

/**
 * 以 `yyyy-MM-dd` 表示的本地日历日期。
 *
 * 原型用字符串比较 + JS Date 做日期运算；这里换成 java.time.LocalDate（minSdk 26 可用），
 * 但对外仍保留 `yyyy-MM-dd` 的字符串形态，因为它是原型里所有日期比较/存储的基准，
 * 保持形态一致可以避免移植过程中出现「格式不同导致比较结果不同」的隐性 bug。
 */
@JvmInline
value class DateKey(val value: String) : Comparable<DateKey> {

    init {
        require(value.length == 10 && value[4] == '-' && value[7] == '-') {
            "DateKey 必须是 yyyy-MM-dd 格式，实际为: $value"
        }
    }

    override fun compareTo(other: DateKey): Int = value.compareTo(other.value)

    /** ISO 星期：1=周一 … 7=周日，等价于原型的 isoDay()。 */
    val isoDayOfWeek: Int get() = toLocalDate().dayOfWeek.value

    fun toLocalDate(): LocalDate = LocalDate.parse(value)

    fun plusDays(days: Long): DateKey = DateKey(toLocalDate().plusDays(days).toString())

    fun minusDays(days: Long): DateKey = DateKey(toLocalDate().minusDays(days).toString())

    /** 该日期所在周的周一（原型 getWeekDays 的 Monday 基准）。 */
    fun mondayOfWeek(): DateKey =
        DateKey(toLocalDate().with(java.time.DayOfWeek.MONDAY).toString())

    /** 该日期所在周的周日。 */
    fun sundayOfWeek(): DateKey =
        DateKey(toLocalDate().with(java.time.DayOfWeek.SUNDAY).toString())

    override fun toString(): String = value
}

/** 构造用：把 java.time.LocalDate 转成 DateKey。 */
fun LocalDate.toDateKey(): DateKey = DateKey(this.toString())

/** yyyy-MM-dd 字符串转 DateKey。 */
fun String.toDateKey(): DateKey = DateKey(this)

/** 两个日期取较早/较晚者，对应原型里的 minDate / 内联比较。 */
fun minOf(a: DateKey, b: DateKey): DateKey = if (a <= b) a else b

fun maxOf(a: DateKey, b: DateKey): DateKey = if (a >= b) a else b

/**
 * 生成 [from, to] 区间内的每一天（含两端）。若 from > to 返回空序列。
 * 对应原型里各处 `let d = ...; while (d <= end) { ...; d = addDays(d, 1) }` 的循环，
 * 但集中成一个函数，避免每个分支各写一遍循环而漏掉边界检查。
 */
fun dateRange(from: DateKey, to: DateKey): Sequence<DateKey> = sequence {
    var current = from
    while (current <= to) {
        yield(current)
        current = current.plusDays(1)
    }
}
