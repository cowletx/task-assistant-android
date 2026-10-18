# 阶段 1：原型分析与移植设计

> 目标：先把 `jihua2.html` 读明白，再动手移植；每移植一个模块就同步修掉该模块的已知问题。

---

## 1. 原型总览

`jihua2.html` 是一个单文件网页原型（约 2550 行）：

- **结构**：`<style>`（约 440 行）+ 静态页面骨架（约 135 行，`.phone > .status-bar / .content > .page* / .bottom-nav`）+ `<script>`（约 1970 行）。
- **页面**：首页、计划（任务列表 + 周视图）、任务详情、任务编辑、每周步骤分配、添加任务、训练页、历史、历史详情、堆积任务处理、设置 —— 共 11 个 `.page` div，用 `showPage(id)` 切 `.active`。
- **导航**：底部 4 个 tab（首页/计划/历史/设置）→ `switchTab` → `showPage`。
- **状态**：一个全局 `state` 对象 + 若干全局变量（`currentWorkout`、`draftPlan`、`weekAssignPattern`、`rollCursors`、`backlogStep` 等）。
- **持久化**：`localStorage` 单键 `task_assistant_proto_v10`，整体 JSON 序列化；写入经过 500 ms 合并（`saveState` / `flushSaveState`）。

### 1.1 数据模型

```js
state = {
  tasks: [ {
    id, name, orderType: 'rotate'|'traditional',
    steps: [ { name, sets, reps } ],
    repeat: 'once'|'daily'|'weekly',
    startDate: 'yyyy-MM-dd', endDate: 'yyyy-MM-dd'|'',
    weeklyPattern: { 1: [0], 3: [1,2], 5: [0,2] },   // ISO 星期 → 步骤下标
    restEnabled, restSeconds
  } ],
  schedule: { 'yyyy-MM-dd': [ entry ] },
  history: [ { id, date, entryId, taskName, steps:[{name,sets,targetReps,actual:[]}], completed } ],
  simulatedDate, weekOffset, weekViewEnd
}

entry = {
  id, taskId, stepIndexes: [0,1], status: 'pending'|'completed'|'skipped'|'inProgress'|'incomplete',
  session: null, sets: [ { stepIndex, setIndex, targetReps, actualReps, status:'done'|'skipped' } ],
  pointer: 0, setStarted: false
}
```

### 1.2 核心函数与依赖

| 函数 | 作用 | 依赖 |
|---|---|---|
| `isoDay(date)` | ISO 星期 1~7 | Date |
| `stepIndexesForDate(task,date)` | 某天该做哪些步骤 | weeklyPattern |
| `generateEntriesForTask` | 区间内生成条目（仅供检查用） | stepIndexesForDate |
| `ensureSchedule(viewWeekEnd)` | **滚动生成主入口** | ensureTaskCoverage + 清理 |
| `ensureTaskCoverage(task,today,viewEnd)` | 前向滚动 + A 段连续回填 + B 段视图周补齐 | rollCursors、fillMissingInRange |
| `fillMissingInRange(task,from,to,force)` | 区间补缺 | taskEntryOn |
| `taskEntryOn` / `isTouchedEntry` / `isStaleAfterEndDate` | 去重与保护判断 | — |
| `getWeekDays(offset)` | 周一到周日 | simulatedDate |
| `renderHome` / `renderWeek` / `renderPlans` / `renderHistory` / `renderBacklog` … | 各页面渲染 | state |
| `startWorkout` / `completeSet` / `skipSet` / `undoLast` / `finishWorkout` | 训练流程 | currentWorkout、buildSequence |
| `buildSequence(task,stepIndexes)` | 生成组序列（rotate/traditional） | — |
| `changeWeek` / `changeDate` / `jumpToDate` / `jumpToToday` | 日期导航（都触发生成） | ensureSchedule |
| `backlogAction` / `backlogSkip` | 堆积任务处理（顺延/覆盖/一起做） | schedule |
| `savePlan` / `saveWeekAssign` / `confirmAddTask` / `deletePlan` | 任务增删改 | ensureSchedule、resetRollCursors |

**关键调用链**：
`changeWeek` → `ensureSchedule(getWeekEndDate(offset))` → `ensureTaskCoverage` → `fillMissingInRange` → `taskEntryOn`
→ 若新增条目则 `saveState()`；渲染层再读 `state.schedule`。

### 1.3 日程生成的三条硬约束（原型注释里已写明，移植时必须保留）

1. 前向范围 = `max(今天 + 365 天, 当前查看周的结束日期)`；
2. 只有往前翻周时才向过去补，下限 = `max(任务开始日, 今天 − 365 天)`；
3. 同一天同一任务不重复；已存在条目（任何状态）不被覆盖。

---

## 2. 功能清单（移植范围）

| 模块 | 原型功能 | Android 对应 |
|---|---|---|
| 任务管理 | 列表、新建、编辑、删除、步骤池增删、组数/次数、顺序模式、组间计时 | `PlansScreen` / `PlanEditScreen` / `PlanDetailScreen` |
| 重复设置 | 仅一次 / 每天（起止）/ 每周（起止 + 星期分配） | `PlanEditScreen` 的 `RepeatSelector` + `WeekAssignScreen` |
| 添加任务 | 指定日期 + 勾选步骤 + 同日去重 | `AddTaskScreen` |
| 首页 | 日期浏览、当天安排、状态、开始/继续/跳过/完成、堆积入口 | `HomeScreen` |
| 周视图 | 一周 7 天、今天高亮、任务状态、前后翻周 | `PlansScreen` 下半部 |
| 训练 | 当前步骤/组号/目标、实际次数 ±、开始本组→完成本组两阶段、跳过、撤销、休息倒计时、结束训练 | `WorkoutScreen` + `WorkoutEngine` |
| 历史 | 按日期分组、每步骤 done/sets 与每组次数、提前结束红标 | `HistoryScreen` / `HistoryDetailScreen` |
| 堆积任务 | 按任务分组、展开日期、做/跳过/顺延/覆盖/一起做 | `BacklogScreen` |
| 设置 | 通知权限、常驻通知、震动、数据导出、关于 | `SettingsScreen` + `WorkoutTimerService` |

---

## 3. 需要重新实现的浏览器专属能力

| 原型的做法 | 问题 | Android 方案 |
|---|---|---|
| `localStorage` 整体 JSON | 全量序列化，数据量大后每次写盘都很贵；无事务、无并发保护 | **Room**：10 张表 + 事务；`@Insert(IGNORE)` 保证不覆盖；唯一索引 `(taskId,date)` |
| `setTimeout` 做 500 ms 合并写盘 | 进程随时可能被杀，丢数据 | Room 事务即时提交（训练每一步都落库） |
| `setInterval` 做休息倒计时 | 页面切到后台会被节流甚至暂停 | Compose `LaunchedEffect` + 前台服务通知（Android 规范做法） |
| `confirm()` / `alert()` | 浏览器对话框 | Compose `AlertDialog` / Snackbar |
| 全局可变 `state` 对象 | 任何函数都能改，难以追踪（Bug 2 的温床） | 单一 `MainViewModel` + `StateFlow<UiState>`，只有 ViewModel 能改状态 |
| 全局 `rollCursors` | 重启即丢，开局重扫一整年 | `roll_cursors` 表持久化 |
| DOM 全量 `innerHTML` 重绘 | 每次改动重建整棵子树 | Compose 按状态做最小重组 |
| 字符串比较日期 | 依赖 `yyyy-MM-dd` 字典序（在本项目里成立，但脆弱） | `DateKey` 值类型 + `java.time.LocalDate`；仍对外用 `yyyy-MM-dd` 存储以保持兼容 |

---

## 4. 原型里已确认的 Bug（Android 版本必须修掉）

### Bug 1：每周任务在开始日期之前被生成
- **位置**：`ensureTaskCoverage()` 后向回填 B 段（原型约 850–890 行）
- **原因**：`vFrom` 只夹 `backFloor`，随后 `fillMissingInRange(task, vFrom, vTo, true)` 的
  `force=true` 明确允许生成 `task.startDate` 之前的日期。开始日 2026-10-13 的任务因此在 2026-10-12 出现。
- **影响**：用户看到不该存在的待办，可能被误做并写入训练记录。

### Bug 2：生成可能覆盖已有训练数据
- **位置**：`fillMissingInRange` 的写入分支、`ensureSchedule` 的清理分支
- **原因**：清理逻辑（`isStaleAfterEndDate`、空壳清理）判断条件与写入逻辑分散在多处，
  任何一处漏判；`schedule[date]` 是可被任意代码改写的普通对象。
- **影响**：已完成/进行中的进度、实际次数、指针可能被重置 —— 这是最严重的类型。

### Bug 3：每周规则未被严格执行
- **位置**：按周补齐的分支只按日期范围生成，未复查 `weeklyPattern`
- **原因**：`stepIndexesForDate` 之外还存在其它"按日期范围直接生成"的路径。
- **影响**：非分配星期也会出现该任务。

### Bug 4：提前结束的记录规则容易被误改
- **规则**：只有全部组真实做完且无跳过才算 `completed`，否则 `incomplete` + 红色「提前结束」。
- **风险**：移植时容易被简化成「结束即完成」或「一律未完成」。
- **影响**：历史数据失真，用户无法区分"真做完"和"中途放弃"。

### 其它已修的前端问题（本轮之前的迭代，Android 一并继承正确行为）
- 远期周（>365 天）拿不到循环安排 → 前向范围要包含视图周末；
- 真实日期跨午夜不更新 → Android 直接用系统时间，天然没有该问题；
- A 段连续回填被重复整段扫描 → Android 用游标 + 「到下限即整段跳过」；
- 每周任务遇到休息日前向游标不推进 → Android 中游标与"是否创建条目"解耦。

---

## 5. Android 端的设计决策与理由

1. **把领域逻辑抽成纯 Kotlin 模块**（`core-schedule`）：可测、无 Android 依赖，
   避免"生成逻辑散落在 UI 回调里"重演原型的问题。
2. **生成器只有 insert，没有 update**：用类型系统保证 Bug 2 不再出现。
   `ScheduleStore` 接口不暴露任何修改方法。
3. **规则单点化**：`StepRule.stepIndexesFor` 是"某天该做哪些步骤"的唯一实现，
   同时检查日期范围 / 重复方式 / 结果非空。所有生成路径都走它。
4. **唯一入口 + 唯一索引**：`(taskId, date)` 在数据库层唯一，双保险防重复。
5. **状态驱动 UI**：`Screen` sealed interface + `when`，不用 Navigation 图，
   因为页面之间是"同一数据集的不同视图"，共享状态，切页面不应重建返回栈。
6. **训练即时落库**：每个操作一次事务，指针/组记录/`setStarted` 同步写，
   解决"退出页面就丢进度"。
7. **不做账号/云同步/广告**：按要求只保证本地可靠。
8. **无 DI 框架**：`TaskAssistantApp` 里手动装配，依赖最少、构建最稳。

---

## 6. 阶段划分与状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 分析原型 + 搭建工程 + 数据模型 | ✅ 完成（本文档 + Gradle 配置 + Room 模型） |
| 2 | 任务管理 + 日程生成（修 Bug 1/2/3） | ✅ 代码完成 + 逻辑已验证 |
| 3 | 首页 / 周视图 / 详情 / 添加任务 | ✅ 代码完成 |
| 4 | 训练流程 + 历史（修 Bug 4） | ✅ 代码完成 + 逻辑已验证 |
| 5 | 堆积处理 / 通知 / 设置 | ⚠️ 部分完成（堆积处理与设置页已实现；前台服务类已写但未接线，导出未落盘） |
| 6 | 回归测试与交付 | ⚠️ 受环境限制：无法编译/安装，只有不依赖 Android 的逻辑验证 |

未能完成的验证项与原因见 `README.md` 第六、七节。
