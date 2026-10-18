package com.example.taskassistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 训练流程与「提前结束」规则的测试（Bug 4）。
 *
 * 原型规则：只有「所有计划组都真实做完、且没有任何一组被跳过」才算完成，
 * 否则记为未完成（incomplete），界面用红色「提前结束」展示。
 * 这些用例锁定该规则，防止移植过程中被误改成「一律算完成」或「一律算未完成」。
 */
class WorkoutEngineTest {

    private fun task(
        orderType: OrderType = OrderType.ROTATE,
        steps: List<Step> = listOf(Step("俯卧撑", 2, 10), Step("深蹲", 1, 5)),
        restEnabled: Boolean = false,
        restSeconds: Int = 60,
    ) = TaskDef(
        id = "t1",
        name = "训练任务",
        orderType = orderType,
        steps = steps,
        repeat = RepeatMode.ONCE,
        startDate = DateKey("2026-03-15"),
        endDate = DateKey("2026-03-15"),
        restEnabled = restEnabled,
        restSeconds = restSeconds,
    )

    private fun entry(stepIndexes: List<Int> = listOf(0, 1)) = ScheduleEntry(
        id = "entry1",
        taskId = "t1",
        date = DateKey("2026-03-15"),
        stepIndexes = stepIndexes,
    )

    // ---------------- 序列构造 ----------------

    @Test
    fun `轮换式按 A1-B1-C1-A2 顺序展开`() {
        val t = task(orderType = OrderType.ROTATE)
        val seq = WorkoutEngine.buildSequence(t, listOf(0, 1))
        // 俯卧撑 2 组、深蹲 1 组 → 轮换: 俯1, 深1, 俯2
        assertEquals(3, seq.size)
        assertEquals(SetSlot(0, 0, 10), seq[0])
        assertEquals(SetSlot(1, 0, 5), seq[1])
        assertEquals(SetSlot(0, 1, 10), seq[2])
    }

    @Test
    fun `传统式按 A1-A2-A3-B1 顺序展开`() {
        val t = task(orderType = OrderType.TRADITIONAL)
        val seq = WorkoutEngine.buildSequence(t, listOf(0, 1))
        assertEquals(3, seq.size)
        assertEquals(SetSlot(0, 0, 10), seq[0])
        assertEquals(SetSlot(0, 1, 10), seq[1])
        assertEquals(SetSlot(1, 0, 5), seq[2])
    }

    @Test
    fun `越界的步骤索引被忽略`() {
        val t = task()
        val seq = WorkoutEngine.buildSequence(t, listOf(0, 99, -1))
        assertEquals(2, seq.size)
        assertTrue(seq.all { it.stepIndex == 0 })
    }

    // ---------------- 组记录 ----------------

    @Test
    fun `完成一组会写入实际次数并前进指针`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        assertEquals(0, session.pointer)

        session = WorkoutEngine.markSetStarted(session)
        assertTrue("开始本组后 setStarted 应为 true", session.setStarted)

        val after = WorkoutEngine.completeSet(session, t, actualReps = 12)!!
        assertEquals(1, after.pointer)
        assertEquals(1, after.records.size)
        assertEquals(12, after.records[0].actualReps)
        assertFalse(after.records[0].skipped)
        assertFalse("完成一组后应清除 setStarted", after.setStarted)
    }

    @Test
    fun `跳过一组记为零次且不算完成`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.markSetStarted(session)
        val after = WorkoutEngine.skipSet(session)!!

        assertEquals(1, after.records.size)
        assertTrue(after.records[0].skipped)
        assertEquals(0, after.records[0].actualReps)
        assertFalse(after.isAllRealDone)
    }

    @Test
    fun `撤销会弹回最后一组并回退指针`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.completeSet(session, t, 8)!!
        assertEquals(2, session.pointer)

        val undone = WorkoutEngine.undoLast(session)!!
        assertEquals(1, undone.pointer)
        assertEquals(1, undone.records.size)
        assertNull("无记录时撤销返回 null", WorkoutEngine.undoLast(WorkoutEngine.start(t, entry())))
    }

    @Test
    fun `休息计时只在开启组间计时且还有下一组时启动`() {
        val withRest = task(restEnabled = true, restSeconds = 45)
        var session = WorkoutEngine.start(withRest, entry())
        session = WorkoutEngine.completeSet(session, withRest, 10)!!
        assertEquals("完成非最后一组后应开始休息", 45, session.restSecondsLeft)

        val ticked = WorkoutEngine.tickRest(WorkoutEngine.tickRest(session))
        assertEquals(43, ticked.restSecondsLeft)
        assertEquals(0, WorkoutEngine.skipRest(ticked).restSecondsLeft)

        val noRest = task(restEnabled = false)
        var s2 = WorkoutEngine.start(noRest, entry())
        s2 = WorkoutEngine.completeSet(s2, noRest, 10)!!
        assertEquals(0, s2.restSecondsLeft)
    }

    @Test
    fun `完成最后一组后不再启动休息`() {
        val t = task(restEnabled = true, restSeconds = 30, steps = listOf(Step("A", 1, 10)))
        var session = WorkoutEngine.start(t, entry(listOf(0)))
        session = WorkoutEngine.completeSet(session, t, 10)!!
        assertTrue(session.isFinishedBySets)
        assertEquals("最后一组完成不该再进入休息", 0, session.restSecondsLeft)
    }

    // ---------------- Bug 4：提前结束 ----------------

    @Test
    fun `全部做完且无跳过判定为已完成`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.completeSet(session, t, 6)!!
        session = WorkoutEngine.completeSet(session, t, 10)!!

        assertTrue(session.isFinishedBySets)
        assertTrue(session.isAllRealDone)
        assertEquals(EntryStatus.COMPLETED, WorkoutEngine.finalStatus(session))
    }

    @Test
    fun `实际次数低于目标仍算完成组`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 3)!! // 目标 10，只做 3
        session = WorkoutEngine.completeSet(session, t, 3)!!
        session = WorkoutEngine.completeSet(session, t, 3)!!

        assertEquals(
            "组数做齐且没有跳过时，实际次数不足仍算已完成（原型规则）",
            EntryStatus.COMPLETED,
            WorkoutEngine.finalStatus(session),
        )
    }

    @Test
    fun `跳过任意一组后结束判定为未完成`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.skipSet(session)!!
        session = WorkoutEngine.completeSet(session, t, 10)!!

        assertFalse(session.isAllRealDone)
        assertEquals(EntryStatus.INCOMPLETE, WorkoutEngine.finalStatus(session))
    }

    @Test
    fun `提前结束且未做满时判定为未完成`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 10)!!
        // 直接结束
        assertFalse(session.isFinishedBySets)
        assertEquals(EntryStatus.INCOMPLETE, WorkoutEngine.finalStatus(session))
    }

    // ---------------- 历史记录 ----------------

    @Test
    fun `历史记录保留实际次数并给未做的步骤留空`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry(listOf(0, 1)))
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.skipSet(session)!!
        // 第三组（俯卧撑第 2 组）不做，直接结束

        val history = WorkoutEngine.buildHistory("h1", t, session, listOf(0, 1))

        assertEquals("训练任务", history.taskName)
        assertFalse("提前结束必须记为未完成", history.completed)

        val pushUp = history.steps.first { it.name == "俯卧撑" }
        assertEquals(2, pushUp.plannedSets)
        assertEquals("实际次数应保留：第1组10次、第2组跳过记0", listOf(10, 0), pushUp.actualReps)

        val squat = history.steps.first { it.name == "深蹲" }
        assertEquals("未做的步骤应保留为空列表（界面显示未记录）", emptyList<Int>(), squat.actualReps)

        assertEquals(1, WorkoutEngine.recordedSetsOf(pushUp))
        assertEquals(0, WorkoutEngine.recordedSetsOf(squat))
    }

    @Test
    fun `历史记录的完成标志与最终状态一致`() {
        val t = task()
        var session = WorkoutEngine.start(t, entry())
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.completeSet(session, t, 10)!!
        session = WorkoutEngine.completeSet(session, t, 5)!!

        val history = WorkoutEngine.buildHistory("h2", t, session, listOf(0, 1))
        assertTrue(history.completed)
        assertEquals(EntryStatus.COMPLETED, WorkoutEngine.finalStatus(session))
    }

    // ---------------- 进度恢复 ----------------

    @Test
    fun `已保存的进度可以被恢复`() {
        val t = task()
        val saved = entry().copy(
            pointer = 1,
            sets = listOf(SetRecord(0, 0, 10, 8)),
            setStarted = true,
            status = EntryStatus.IN_PROGRESS,
        )

        val session = WorkoutEngine.start(t, saved)
        assertEquals("指针应从保存值恢复", 1, session.pointer)
        assertEquals("已记录组数应从保存值恢复", 1, session.records.size)
        assertEquals(8, session.records[0].actualReps)
        assertTrue("已开始标记应恢复", session.setStarted)
        assertEquals("当前组应是序列第 2 项", SetSlot(1, 0, 5), session.currentSlot)
    }

    @Test
    fun `保存的指针越界时被夹到序列长度`() {
        val t = task()
        val saved = entry().copy(pointer = 99)
        val session = WorkoutEngine.start(t, saved)
        assertEquals(session.sequence.size, session.pointer)
        assertTrue(session.isFinishedBySets)
    }
}
