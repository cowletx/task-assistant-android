# 网页功能 → Android 实现 对照清单

这份清单用于回归时逐项核对：网页原型 `jihua2.html` 的每个用户可见功能，在 Android 端是否都有对应实现，
以及实现位置。状态含义：

- **已实现**：Android 端有对应代码，逻辑与原型一致（或按明确要求修正）。
- **已修正**：原型存在 Bug，Android 端按要求修好。
- **部分实现**：主流程可用，但某些细节未移植（原因见备注）。
- **未实现**：尚未移植。

> ⚠️ 重要前提：本机无 Android SDK / Gradle / JDK17+ / 网络，**项目未编译、未安装、未在设备上运行**。
> 「已实现」表示代码已写好，不代表已通过编译或真机验证。详见 `README.md` 第六节。

---

## 1. 任务管理

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 任务列表（名称、步骤数、总组数、步骤摘要） | 已实现 | `PlansScreen` |
| 新建任务 | 已实现 | `PlanEditScreen` + `MainViewModel.savePlan` |
| 编辑任务（含步骤池增删改） | 已实现 | `PlanEditScreen` + `updateDraftStep` / `addDraftStep` / `removeDraftStep` |
| 删除任务（级联删除步骤/分配/日程/组记录，保留历史） | 已实现 | `PlanDetailScreen` 的 `AlertDialog` → `deletePlan`；外键 `CASCADE` |
| 步骤：名称 / 组数 / 目标次数 | 已实现 | `Step` + `PlanEditScreen` 输入框 |
| 顺序模式：轮换式 / 传统式 | 已实现 | `OrderType` + `WorkoutEngine.buildSequence` |
| 组间计时开关 + 休息秒数 | 已实现 | `TaskDef.restEnabled/restSeconds` + `WorkoutScreen` 倒计时 |
| 任务详情页（顺序/步骤数/总组数/组间计时 + 步骤列表） | 已实现 | `PlanDetailScreen` |

## 2. 重复与每周分配

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 仅一次（指定日期） | 已实现 | `RepeatMode.ONCE`；保存后只在该日建一条独立日程 |
| 每天重复（开始/结束，结束可为空=永久） | 已实现 | `RepeatMode.DAILY` + `Scheduler` |
| 每周重复（开始/结束） | 已实现 | `RepeatMode.WEEKLY` |
| 每周步骤分配页：周一~周日逐个勾选步骤 | 已实现 | `WeekAssignScreen` + `WeekAssignDraft` |
| 分配页「全选 / 全不选」 | 已实现 | `selectAllWeekAssignSteps` |
| 分配页显示哪几天已有内容 | 已实现 | 星期按钮下方小圆点 |
| **每周任务不在开始日期之前生成**（Bug 1） | **已修正** | `StepRule.stepIndexesFor` 先判 `isDateInRange`；`Scheduler.fillViewWeek` 的 `from` 取 `max(视图周一, 365天下限, 开始日)` |
| **每周任务严格按 weeklyPattern 生成**（Bug 3） | **已修正** | `StepRule` 为唯一入口，`WEEKLY` 只取 `normalizedPattern()[isoDay]`，空/缺失即不生成 |
| **日程生成不覆盖已有训练数据**（Bug 2） | **已修正** | `ScheduleStore` 只有 `insert`；`isTouched` 预检；`@Insert(IGNORE)` |

## 3. 日程生成

| 原型功能 / 约束 | 状态 | Android 实现 |
|---|---|---|
| 前向范围 = `max(今天+365天, 当前视图周末)` | 已实现 | `Scheduler.forwardScanEnd` |
| 受任务 `endDate` 收窄 | 已实现 | `minOf(forwardViewEnd, endDate)` |
| 只有往前翻周才向后补 | 已实现 | `if (viewWeekEnd != null && viewWeekEnd < today)` |
| 后向下限 = `max(任务开始日, 今天-365天)` | 已实现 | `continuousBackfill` 的 `backLimit` |
| A 段连续回填 + 游标推进 | 已实现 | `continuousBackfill`（到下限即整段跳过，不重扫） |
| B 段视图周定点补齐 | 已实现 | `fillViewWeek`（宽度最多 7 天） |
| 同一天同一任务不重复 | 已实现 | 内存去重 + 数据库唯一索引 `(taskId,date)` |
| 只补缺失、不重置状态与训练数据 | 已实现 | 生成器无 update 能力 + `IGNORE` |
| 清理越界待做安排（结束日改早后） | 已实现 | `TaskRepository.saveTask` → `deleteStalePendingAfter`（只删今天之后、未动过的） |
| 清理空壳安排（步骤全被取消勾选） | 已实现 | `TaskRepository.pruneEmptyShells`（只删无步骤、无组记录、未开始的） |
| 生成游标跨重启保留 | 已实现（改进） | `roll_cursors` 表（原型放内存，重启即丢） |

## 4. 首页 / 周视图 / 添加任务

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 首页当天安排、步骤摘要、总组数 | 已实现 | `HomeScreen` + `TaskDef.totalSetsOf` |
| 日期浏览（前一天/后一天/今天） | 已实现 | `changeViewDate` / `jumpToToday` |
| 状态展示与操作：待做→开始/跳过 | 已实现 | `HomeScreen` 的 `HomeEntryCard` |
| 进行中→继续/完成/跳过 | 已实现 | 同上 |
| 已完成 / 未完成（提前结束）/ 已跳过 文案与颜色 | 已实现 | `StatusUi` + `StatusChip`（红=跳过/未完成） |
| 周视图：一周 7 天 + 今天高亮 + 每天任务与状态 | 已实现 | `PlansScreen` 下半部 |
| 周视图前后翻周 | 已实现 | `changeWeek` |
| 周视图日期卡片点击进入该日「添加任务」 | 已实现 | `WeekDayCard` 的 `onClick` → `Screen.AddTask(day)` |
| 添加任务页：选任务 + 选日期 + 勾选步骤 + 全选/全不选 | 已实现 | `AddTaskScreen` |
| 同一天同一任务重复添加被拦截 | 已实现 | `addTaskToDate` 返回 false → Snackbar「这一天已经有该任务」 |
| 堆积任务入口提示（首页） | 已实现 | `HomeScreen` 底部橙色卡片 |

## 5. 训练流程

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 轮换式 / 传统式组序列 | 已实现 | `WorkoutEngine.buildSequence` |
| 当前步骤名、第几组/共几组、目标次数 | 已实现 | `WorkoutScreen` |
| 实际次数 ±1、按目标变色（低=红/高=绿/等=白） | 已实现 | `adjustActualReps` + 颜色判断 |
| 两阶段按钮：开始本组 → 完成本组 | 已实现 | `handleSetButton` + `WorkoutEngine.markSetStarted/completeSet` |
| 「本组已开始」提示 | 已实现 | `WorkoutScreen` |
| 跳过本组 | 已实现 | `WorkoutEngine.skipSet` |
| 撤销上一组 | 已实现 | `WorkoutEngine.undoLast` |
| 组间休息倒计时 + 跳过休息 | 已实现 | `tickRest` / `skipRest` + `LaunchedEffect` |
| 已完成组列表（每组 实际/目标，跳过显示「跳过」） | 已实现 | `WorkoutScreen` 底部 |
| 进度条 + 进度 x/y + 下一项 | 已实现 | `WorkoutScreen` |
| 全部做完后的「完成 🎉 / 结束并保存」 | 已实现 | `WorkoutScreen` + `finishWorkout` |
| 结束训练（提前结束） | 已实现 | `finishWorkout` → `incomplete` |
| **提前结束的完成判定规则**（Bug 4） | **已修正/锁定** | `WorkoutEngine.finalStatus`：全组真实完成且无跳过 → `completed`，否则 `incomplete` |
| **训练进度即时持久化**（退出/重启不丢） | **已实现（原型只在内存）** | 每次操作 `persistWorkout` 写指针/组记录/`setStarted`；重进用 `WorkoutEngine.start` 恢复 |
| 训练期间通知（原型用页面内计时） | 已实现 | `WorkoutTimerService` 前台服务 + 通知渠道 + Android 13+ 权限 + Android 14 `specialUse` |

## 6. 历史记录

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 按日期分组的记录列表 | 已实现 | `HistoryScreen` |
| 每条记录显示每个步骤的 done/组数 与每组次数 | 已实现 | `HistoryStepRow` |
| 跳过的组显示「跳过」 | 已实现 | `HistorySetEntity.skipped` |
| 未记录的步骤显示「未记录」 | 已实现 | 没有 set 行 → 空列表 |
| 提前结束的红色「提前结束」标记 | 已实现 | `EarlyFinishTag`（首页/历史/详情一致） |
| 历史详情页 | 已实现 | `HistoryDetailScreen` |
| 同一 entryId 的历史被覆盖而非追加 | 已实现 | `HistoryDao.replaceForEntry` |

## 7. 堆积（逾期）任务

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 按任务分组，显示「N 天未处理」 | 已实现 | `getBacklogGroups` + `BacklogScreen` |
| 展开查看各天日期与状态 | 已实现 | `BacklogTaskCard`（直接平铺，未做折叠动画） |
| 「跳过」所有堆积条目 | 已实现 | `backlogSkip` |
| 「顺延」到次日 | 已实现 | `backlogAction("顺延")` |
| 「覆盖」到今天 | 已实现 | `backlogAction("覆盖")` |
| 「一起做」合并到今天 | 已实现 | `backlogAction("一起做")` |
| **两级流程**：先选「做 / 跳过」，点「做」后进入步骤选择 | 已实现 | `backlogDo(task)` 切换 `backlogStepSelectionTaskId`；界面按该字段渲染两个阶段 |
| **「选择要保留的步骤」筛选层** | 已实现 | 第二阶段用 `StepCheckRow` 逐步骤勾选 + 全选/全不选；`toggleBacklogStep` / `selectAllBacklogSteps` |
| 「返回」回到第一阶段 | 已实现 | `backlogReset()` |
| 筛选只影响新安排，不改历史记录 | 已实现 | 筛选结果只用于 `addTaskToDate(..., kept)`；原有条目只置为 skipped |

## 8. 设置

| 原型功能 | 状态 | Android 实现 |
|---|---|---|
| 通知权限状态显示 | 已实现（真实） | `SettingsScreen` 显示授权状态；`MainActivity` 启动时请求（API 33+） |
| 常驻通知开关 | 已实现（真实生效） | `setPersistentNotification` → 存 `app_settings` + 控制前台服务 |
| 震动提醒开关 | 已实现（真实生效） | 已持久化并可切换；休息归零时 `AndroidWorkoutServiceController.vibrateShort()` 触发震动（`VibrationEffect`，API 31+ 走 `VibratorManager`） |
| 数据导出 | 已实现 | 设置页「导出 JSON」→ SAF `CreateDocument` → 写出完整 JSON（任务/步骤/每周分配/日程/组记录/历史）；不需要存储权限 |
| 关于/版本信息 | 已实现 | `SettingsScreen` |
| 状态色说明 | 已实现 | `StatusLegend` |
| 重置生成游标（原型无此按钮，排障用） | 已实现（新增） | `resetCursors`，不删数据 |

---

## 9. 与原型的刻意差异（都是有意的，不是遗漏）

| 差异 | 原因 |
|---|---|
| 去掉了「⚠ 日期切换仅供原型测试」提示 | Android 端日期浏览是正常功能，不是测试钩子 |
| 日期选择用「−1天/+1天」按钮而不是原生 DatePicker | 避免额外依赖与状态同步问题；当前实现足以覆盖原型的日期调整能力 |
| 页面切换用状态驱动而非 Navigation 图 | 这些页面共享同一份数据，不需要深链接与独立返回栈 |
| 数据存 Room 而不是 localStorage | 明确要求；同时获得事务、唯一索引、级联删除能力 |
| 删除了原型的 `session` 字段 | 原型里始终为 null，是无效字段 |
| 训练进度即时落库 | 原型只在内存 + 500ms 合并写盘，进程被杀会丢进度 |
| 生成游标持久化 | 原型重启后要重扫一整年 |

## 10. 剩余工作（按优先级）

1. **在有 Android SDK 的机器上编译并修复编译错误**（最高优先级，见 README 第三节）。
2. 真机验证：Room 建表/级联/唯一索引冲突、通知渠道、Android 13+ 权限流程、训练通知、震动。
3. 数据导入（导出已完成，导入未实现）。
4. 设置页补「通知权限」跳转系统设置的按钮（当前仅显示状态）。
5. 日期选择改用原生 Material3 `DatePicker`（当前是 −1天/+1天 按钮）。
