package com.example.taskassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程生成的核心回归测试。
 *
 * 这些用例直接对应移植过程中要修的 Bug：
 *   Bug 1 —— 每周任务在开始日期之前不能生成；
 *   Bug 2 —— 生成不得覆盖任何已有训练数据；
 *   Bug 3 —— 每周任务必须严格按 weeklyPattern 生成。
 *
 * 全部为纯 JVM 测试（无 Android 依赖），因此 `./gradlew :core-schedule:test` 即可运行。
 * 测试里的 today 由 [Scheduler] 的 clock 注入，固定在 2026-03-15，避免依赖真实时钟。
 */
class SchedulerTest {

    private val today = DateKey("2026-03-15")

    private fun scheduler(store: ScheduleStore, startId: Int = 1): Pair<Scheduler, () -> Int> {
        var counter = startId
        val next: () -> Int = { counter++ }
        val s = Scheduler(
            store = store,
            clock = { today },
            idFactory = { "e${next()}" },
        )
        return s to next
    }

    private fun dailyTask(
        id: String = "t-daily",
        start: String = "2026-03-15",
        end: String? = null,
    ) = TaskDef(
        id = id,
        name = "每天任务",
        repeat = RepeatMode.DAILY,
        startDate = DateKey(start),
        endDate = end?.let { DateKey(it) },
        steps = listOf(Step("A", 3, 10)),
    )

    /** 2026-10-13 是周二；pattern 指定周二做第 1 个步骤。 */
    private fun weeklyTuesdayTask(
        id: String = "t-weekly",
        start: String = "2026-10-13",
        end: String? = null,
        pattern: Map<Int, List<Int>> = mapOf(2 to listOf(0)),
    ) = TaskDef(
        id = id,
        name = "每周任务",
        repeat = RepeatMode.WEEKLY,
        startDate = DateKey(start),
        endDate = end?.let { DateKey(it) },
        steps = listOf(Step("A", 2, 10)),
        stepIndexesByIsoDay = pattern,
    )

    // ---------------- Bug 1：开始日期边界 ----------------

    @Test
    fun `每周任务在开始日期的前一天不生成安排`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = weeklyTuesdayTask(start = "2026-10-13")

        // 视图停在开始日所在的那一周（2026-10-12 周一 ~ 10-18 周日）
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-18"))

        assertNull(
            "开始日 2026-10-13 的前一天 2026-10-12 不该有该任务的安排（Bug 1）",
            store.findEntry(task.id, DateKey("2026-10-12")),
        )
        assertNotNull(
            "开始日当天 2026-10-13 是周二、符合 weeklyPattern，应当生成",
            store.findEntry(task.id, DateKey("2026-10-13")),
        )
    }

    @Test
    fun `开始日之前的整周都不生成安排`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        // 每天任务，但开始日很晚：开始日之前一天都不该出现
        val task = dailyTask(start = "2026-10-13")

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-18"))

        val before = store.allEntries().filter { it.date < task.startDate }
        assertTrue("开始日之前不应有任何安排，实际有: ${before.map { it.date.value }}", before.isEmpty())
        assertNotNull(store.findEntry(task.id, DateKey("2026-10-13")))
        assertNotNull(store.findEntry(task.id, DateKey("2026-10-12").plusDays(2))) // 10-14
    }

    @Test
    fun `查看开始日之前的历史周也不会补出该任务`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = weeklyTuesdayTask(start = "2026-10-13")

        // 先正向铺一遍
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-18"))
        // 再翻到开始日之前的那一周（2026-10-05 ~ 10-11）
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-11"))

        val leaked = store.allEntries().filter { it.date < task.startDate }
        assertTrue("翻到开始日之前的历史周后仍生成了: ${leaked.map { it.date.value }}", leaked.isEmpty())
    }

    // ---------------- Bug 3：每周规则 ----------------

    @Test
    fun `每周任务只在 weeklyPattern 指定的星期生成`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = weeklyTuesdayTask(start = "2026-10-01", pattern = mapOf(2 to listOf(0)))

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-31"))

        val generated = store.allEntries().map { it.date }
        assertTrue("10-31 之前应已生成若干条目", generated.isNotEmpty())
        generated.forEach { date ->
            assertEquals(
                "只有周二(iso=2)该生成，实际生成了 ${date.value}（周${date.isoDayOfWeek}）",
                2, date.isoDayOfWeek,
            )
        }
        // 不符合规则的具体日期：2026-10-14 是周三
        assertNull(store.findEntry(task.id, DateKey("2026-10-14")))
        // 符合规则的具体日期：2026-10-13 周二、2026-10-20 周二
        assertNotNull(store.findEntry(task.id, DateKey("2026-10-13")))
        assertNotNull(store.findEntry(task.id, DateKey("2026-10-20")))
    }

    @Test
    fun `每周任务 pattern 为空数组的星期不生成`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = weeklyTuesdayTask(
            start = "2026-10-01",
            pattern = mapOf(2 to listOf(0), 3 to emptyList()),
        )

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-31"))

        // 2026-10-14 是周三，pattern 里是空数组 → 不生成
        assertNull(store.findEntry(task.id, DateKey("2026-10-14")))
    }

    @Test
    fun `每周任务的步骤索引严格按当天分配`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = TaskDef(
            id = "t-multi",
            name = "多步骤每周",
            repeat = RepeatMode.WEEKLY,
            startDate = DateKey("2026-10-01"),
            steps = listOf(Step("A", 1, 1), Step("B", 1, 1), Step("C", 1, 1)),
            stepIndexesByIsoDay = mapOf(1 to listOf(0), 3 to listOf(1, 2), 5 to listOf(0, 2)),
        )

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-10-31"))

        assertEquals(listOf(0), store.findEntry(task.id, DateKey("2026-10-05"))?.stepIndexes) // 周一
        assertEquals(listOf(1, 2), store.findEntry(task.id, DateKey("2026-10-07"))?.stepIndexes) // 周三
        assertEquals(listOf(0, 2), store.findEntry(task.id, DateKey("2026-10-09"))?.stepIndexes) // 周五
        assertNull(store.findEntry(task.id, DateKey("2026-10-06"))) // 周二无分配
    }

    // ---------------- Bug 2：已有数据保护 ----------------

    @Test
    fun `生成过程不覆盖任何已有训练数据`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = dailyTask()

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-03-21"))

        // 人为把若干条目改成各种状态
        val probeDates = listOf("2026-03-16", "2026-03-17", "2026-03-18", "2026-03-19", "2026-03-20", "2026-03-21")
        val statuses = listOf(
            EntryStatus.COMPLETED, EntryStatus.SKIPPED, EntryStatus.INCOMPLETE,
            EntryStatus.IN_PROGRESS, EntryStatus.PENDING, EntryStatus.PENDING,
        )
        val touched = mutableListOf<ScheduleEntry>()
        probeDates.forEachIndexed { index, raw ->
            val date = DateKey(raw)
            val original = store.findEntry(task.id, date)!!
            touched += original.copy(
                status = statuses[index],
                sets = listOf(SetRecord(0, 0, 10, 7 + index)),
                pointer = index,
                setStarted = index % 2 == 0,
            )
        }
        val protected = InMemoryScheduleStore(touched)
        val (scheduler2, _) = scheduler(protected, startId = 1000)

        // 反复生成：正向、远期周、历史周都跑一遍
        repeat(3) {
            scheduler2.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2027-06-01"))
            scheduler2.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-03-21"))
            scheduler2.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-01-05"))
        }

        touched.forEach { before ->
            val after = protected.findEntry(task.id, before.date)
            assertNotNull("条目 ${before.date.value} 不应被删除", after)
            assertEquals("id 被改变: ${before.date.value}", before.id, after!!.id)
            assertEquals("status 被覆盖: ${before.date.value}", before.status, after.status)
            assertEquals("sets 被覆盖: ${before.date.value}", before.sets, after.sets)
            assertEquals("pointer 被覆盖: ${before.date.value}", before.pointer, after.pointer)
            assertEquals("setStarted 被覆盖: ${before.date.value}", before.setStarted, after.setStarted)
        }
    }

    @Test
    fun `同一天同一任务不会重复生成`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val tasks = listOf(dailyTask(), weeklyTuesdayTask(start = "2026-03-15"))

        repeat(5) {
            scheduler.ensureSchedule(tasks, viewWeekEnd = DateKey("2027-01-01"))
            scheduler.ensureSchedule(tasks, viewWeekEnd = DateKey("2025-11-01"))
        }

        val keys = store.allEntries().map { it.taskId to it.date.value }
        assertEquals("出现重复的 (taskId, date): ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}", keys.size, keys.toSet().size)
    }

    @Test
    fun `重复调用是幂等的`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = dailyTask()

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-04-01"))
        val snapshotIds = store.allEntries().map { it.id to it.date.value }.toSet()

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-04-01"))
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2026-04-01"))

        val afterIds = store.allEntries().map { it.id to it.date.value }.toSet()
        assertEquals("重复调用后条目集合发生变化", snapshotIds, afterIds)
    }

    // ---------------- 生成范围 ----------------

    @Test
    fun `结束日期之后不再生成`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = dailyTask(start = "2026-03-15", end = "2026-04-30")

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2027-06-01"))

        val afterEnd = store.allEntries().filter { it.date > DateKey("2026-04-30") }
        assertTrue("结束日之后生成了: ${afterEnd.map { it.date.value }}", afterEnd.isEmpty())
        assertNotNull(store.findEntry(task.id, DateKey("2026-04-30")))
        assertNull(store.findEntry(task.id, DateKey("2026-05-01")))
    }

    @Test
    fun `结束日期为空时持续滚动生成`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = dailyTask()

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = null)

        // 今天起 365 天内应全部生成（含两端共 366 天）
        val count = store.allEntries().count { it.date >= today && it.date <= today.plusDays(365) }
        assertEquals(366, count)

        // 远期周视图仍能拿到安排
        val (scheduler2, _) = scheduler(store, startId = 5000)
        scheduler2.resetCursors()
        scheduler2.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2027-06-01"))
        assertNotNull(store.findEntry(task.id, DateKey("2027-06-01")))
    }

    @Test
    fun `向后回填下限为任务开始日与今天减365天中的较晚者`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        // 开始日远早于今天-365 天
        val task = dailyTask(start = "2025-01-01")

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = null)
        val floor = today.minusDays(365)

        val tooOld = store.allEntries().filter { it.date < floor }
        assertTrue("生成了早于下限(${floor.value})的日期: ${tooOld.map { it.date.value }.take(5)}", tooOld.isEmpty())

        // 翻到下限之后的历史周，缺失的日期应被补齐
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2025-06-15"))
        assertNotNull(store.findEntry(task.id, DateKey("2025-06-15")))
    }

    @Test
    fun `开始日早于下限时不会生成下限之前的历史`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = dailyTask(start = "2025-01-01")

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2025-04-01"))

        val floor = today.minusDays(365)
        val beforeFloor = store.allEntries().filter { it.date < floor }
        assertTrue("不应生成下限之前的记录: ${beforeFloor.map { it.date.value }}", beforeFloor.isEmpty())
    }

    // ---------------- 游标与性能 ----------------

    @Test
    fun `每周任务遇到休息日也会推进前向游标`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        // 只在周一做 → 其余 6 天都是「休息日」
        val task = weeklyTuesdayTask(start = "2026-03-15", pattern = mapOf(1 to listOf(0)))

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = today.plusDays(30))
        val cursorAfterFirst = scheduler.cursorsOf(task.id).forward
        assertNotNull(cursorAfterFirst)
        assertTrue(
            "前向游标应推进到扫描结束日之后，实际 ${cursorAfterFirst?.value}",
            cursorAfterFirst!! > today.plusDays(30),
        )

        // 再次调用不应改变游标
        scheduler.ensureSchedule(listOf(task), viewWeekEnd = today.plusDays(30))
        assertEquals(cursorAfterFirst, scheduler.cursorsOf(task.id).forward)
    }

    @Test
    fun `连续翻 400 周不会重复扫描整段区间且耗时可控`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val tasks = listOf(dailyTask(), weeklyTuesdayTask(start = "2026-03-15", pattern = mapOf(1 to listOf(0), 3 to listOf(0))))

        val started = System.nanoTime()
        var week = 0
        while (week < 400) {
            val viewEnd = today.plusDays((week + 1) * 7L)
            scheduler.ensureSchedule(tasks, viewWeekEnd = viewEnd)
            week++
        }
        val forwardMs = (System.nanoTime() - started) / 1_000_000

        val startedBack = System.nanoTime()
        week = 400
        while (week > 0) {
            val viewEnd = today.plusDays(week * 7L)
            scheduler.ensureSchedule(tasks, viewWeekEnd = viewEnd)
            week--
        }
        val backwardMs = (System.nanoTime() - startedBack) / 1_000_000

        val keys = store.allEntries().map { it.taskId to it.date.value }
        assertEquals("翻周过程中出现重复条目", keys.size, keys.toSet().size)
        assertTrue("前向 400 周耗时过长: ${forwardMs}ms", forwardMs < 20_000)
        assertTrue("后向 400 周耗时过长: ${backwardMs}ms", backwardMs < 20_000)
    }

    @Test
    fun `一次性任务只生成开始日当天且不参与滚动`() {
        val store = InMemoryScheduleStore()
        val (scheduler, _) = scheduler(store)
        val task = TaskDef(
            id = "t-once",
            name = "一次性",
            repeat = RepeatMode.ONCE,
            startDate = DateKey("2026-03-18"),
            endDate = DateKey("2026-03-18"),
            steps = listOf(Step("A", 2, 10)),
        )

        scheduler.ensureSchedule(listOf(task), viewWeekEnd = DateKey("2027-01-01"))

        assertEquals(
            "一次性任务不应由滚动生成产生日程（由「添加任务到指定日期」入口创建）",
            0, store.allEntries().size,
        )
    }
}
