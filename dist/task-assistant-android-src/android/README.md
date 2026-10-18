# 任务助手 · Android 原生版

把网页原型 `jihua2.html`（训练计划 / 日程 / 训练记录工具）移植为 Android 原生应用。
**Kotlin + Jetpack Compose(Material 3) + Room，minSdk 26。**

---

## 一、⚠️ 重要：本仓库未包含 APK，且未在本机编译过

本次开发所在的环境**不具备 Android 构建条件**，实测结果如下：

| 依赖 | 需要 | 本机实际情况 |
|---|---|---|
| Android SDK | 必需（platform 35 + build-tools） | **不存在**（`ANDROID_HOME` 为空，`%LOCALAPPDATA%\Android\Sdk` 不存在，无 Android Studio） |
| Gradle | 必需 | **不存在**（`gradle` 命令未找到；wrapper 已提供但需联网下载发行包） |
| JDK | ≥ 17（AGP 8.x 要求） | **只有 JDK 8**（Temurin 1.8.0_492），版本过低 |
| 网络 | 必需（下载 AGP/Compose/Room 依赖） | **完全不可用**：`repo1.maven.org`、`dl.google.com`、`services.gradle.org` 全部失败；`curl` 报 `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS` |
| Kotlin 编译器 | 用来跑单元测试 | **不存在**（`kotlinc` 未找到） |

因此：**没有生成 APK，也无法声称构建成功**。源码是按可编译目标编写的，但**尚未经过编译器验证**。
请在有 Android SDK 的机器上用 Android Studio 打开本目录并构建（见第三节）。

为了让「已知 Bug 是否真的修好」有可执行证据，仓库里提供了两个不依赖 Android 的验证脚本
（见第五节），它们已经实际运行并通过。

---

## 二、模块结构

```
android/
├── settings.gradle.kts            模块声明
├── build.gradle.kts               插件版本（AGP 8.7.3 / Kotlin 2.0.21 / KSP）
├── gradle.properties
├── gradle/libs.versions.toml      依赖版本集中管理
├── gradle/wrapper/…               Gradle wrapper 配置（8.9）
│
├── core-schedule/                 ★ 纯 Kotlin/JVM 模块，零 Android 依赖
│   └── src/main/kotlin/…/core/
│       ├── DateKey.kt             日期值类型（yyyy-MM-dd，ISO 星期）
│       ├── TaskDef.kt             任务定义 + 重复方式 + 每周分配
│       ├── ScheduleModels.kt      日程条目 / 组记录 / 历史 / 状态枚举
│       ├── Scheduler.kt           ★ 日程生成（Bug 1/2/3 的修复点）
│       ├── WorkoutEngine.kt       ★ 训练流程与提前结束判定（Bug 4）
│       └── DomainExtras.kt        派生计算（总组数等）
│   └── src/test/kotlin/…/core/
│       ├── SchedulerTest.kt       日程生成回归测试（含 Bug 1/2/3 用例）
│       └── WorkoutEngineTest.kt   训练流程回归测试（含提前结束用例）
│
└── app/                           Android 应用模块
    ├── src/main/AndroidManifest.xml
    ├── src/main/res/…             中文字符串、深色主题、备份规则
    └── src/main/java/com/example/taskassistant/
        ├── TaskAssistantApp.kt     手动依赖装配（无 DI 框架）
        ├── MainActivity.kt         顶层容器（状态驱动的页面切换）
        ├── data/
        │   ├── db/                  Room：实体 / DAO / 数据库 / TypeConverter
        │   ├── mapper/Mappers.kt    实体 ⇄ 领域模型
        │   └── TaskRepository.kt   ★ 生成编排 + 只插入不覆盖 + 即时保存
        ├── ui/
        │   ├── MainViewModel.kt    ★ 全应用唯一状态源（对应原型全局 state）
        │   ├── StatusUi.kt         状态文案/颜色映射
        │   ├── theme/Theme.kt      与原型一致的深色配色
        │   ├── components/Common.kt 卡片/状态标签/勾选行等复用组件
        │   └── screens/            Home / Plans / PlanEdit / PlanDetail /
        │                           WeekAssign / AddTask / Workout / History /
        │                           HistoryDetail / Backlog / Settings
        └── workout/WorkoutTimerService.kt  训练前台服务（通知 + 计时）
```

**为什么要拆出 `core-schedule`**：日程生成与训练流程是最容易出错、也最需要测试的部分。
把它们做成零 Android 依赖的纯 JVM 模块后，可以用普通 JUnit（`./gradlew :core-schedule:test`）
直接跑，不需要模拟器；同时从结构上避免「生成逻辑里偷偷调用 UI/数据库导致不可测」。

---

## 三、构建与运行

### 3.1 环境要求

- Android Studio Ladybug (2024.2) 或更新版本
- JDK 17（Android Studio 自带 JBR 即可）
- Android SDK：compileSdk 35、build-tools 35.x
- 首次构建需要网络（下载依赖）

### 3.2 构建命令

```bash
cd android

# 运行核心逻辑单元测试（不需要模拟器，最快）
./gradlew :core-schedule:test

# 编译 Debug APK
./gradlew :app:assembleDebug

# 编译并安装到已连接设备
./gradlew :app:installDebug
```

产物路径：`android/app/build/outputs/apk/debug/app-debug.apk`

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

### 3.3 用 Android Studio 打开

1. `File → Open`，选择 `android` 目录（**不是**仓库根目录）。
2. 等待 Gradle Sync 完成（会自动下载依赖）。
3. 直接点 Run 即可安装到模拟器或真机。

### 3.4 Room schema 说明

`exportSchema = false`：本项目不需要 schema 迁移校验文件，避免构建时产生额外目录。
如果以后要做版本迁移，改成 `true` 并配置 `room.schemaLocation`。

---

## 四、与网页原型的对应关系

| 原型 | Android | 说明 |
|---|---|---|
| `localStorage`（`task_assistant_proto_v10`） | Room 数据库 `task_assistant.db` | 10 张表：tasks / task_steps / task_weekly_pattern / schedule_entries / set_records / history_records / history_steps / history_sets / app_settings / roll_cursors |
| `state.schedule[date] = [entry…]` | `schedule_entries` 表（`date` 建索引，`(taskId,date)` 唯一） | 唯一索引从存储层保证「同一天同一任务不重复」 |
| `entry.sets[i]` | `set_records` 表（`(entryId, recordIndex)` 主键） | 逐组插入，支持即时保存与撤销 |
| `state.history` | `history_records` + `history_steps` + `history_sets` | 未做的步骤不写 set 行，界面显示「未记录」 |
| `rollCursors`（内存） | `roll_cursors` 表 | 原型重启即丢；Android 端持久化，重启后不重扫一整年 |
| `switchTab` / `showPage` | `MainViewModel.screen` + Compose `when` | 状态驱动，不引入 Navigation 图（页面是同一数据集的不同视图） |
| `renderXxx()` | Compose 可组合函数 | 由 `StateFlow<UiState>` 驱动重组 |
| `state.simulatedDate` | `viewDate` | 保留日期浏览能力；去掉了「仅供原型测试」的警告文案 |
| `showToast` | Snackbar | |
| `confirm()` | 直接执行 + Snackbar | 删除任务是唯一破坏性操作，已保留确认语义（见「尚未完成」） |
| 通知/设置项（静态文案） | 前台服务 + 通知渠道 + 设置页 | 按要求做系统适配 |

---

## 五、已知 Bug 的修复方式与验证

### Bug 1：每周任务在开始日期之前被生成

**原型原因**：`ensureTaskCoverage()` 的后向回填 B 段只把范围夹在 `backFloor`（今天 − 365 天），
随后用 `fillMissingInRange(task, vFrom, vTo, true)` 补齐，`force=true` 允许生成任务 `startDate` 之前的日期；
`vFrom` 没有夹 `task.startDate`。于是开始日 `2026-10-13` 的任务会在 `2026-10-12` 出现。

**Android 修复**：
1. 所有生成路径统一调用 `StepRule.stepIndexesFor(task, date)`，它第一件事就是判断
   `task.isDateInRange(date)`（`startDate ≤ date ≤ endDate`，`endDate` 为空表示不设上限），
   不在范围内直接返回空列表 → 不生成任何条目。
2. `Scheduler.fillViewWeek()` 的 `from` 同时取 `max(视图周一, 365 天下限, 任务开始日)`。
3. 不存在 `force` 这类"绕过开始日"的开关，从结构上消除该类漏洞。

**验证**：`SchedulerTest.每周任务在开始日期的前一天不生成安排`、
`开始日之前的整周都不生成安排`、`查看开始日之前的历史周也不会补出该任务`；
可执行脚本 `_verify/android_spec_check.mjs` 对应 4 条断言（全部 PASS）。

### Bug 2：日程生成覆盖已有训练数据

**Android 修复**（三重保证）：
1. `Scheduler` 只依赖 `ScheduleStore.insert()`，**没有 update/delete 能力**——类型层面就不可能覆盖；
2. `ScheduleEntry.isTouched`（完成/跳过/未完成/进行中/有组数/已开始）在生成前被检查，
   已存在即跳过；
3. 写库用 `@Insert(onConflict = IGNORE)`，并发重复也只忽略。

**验证**：`SchedulerTest.生成过程不覆盖任何已有训练数据`（对 5 条被人为改成各种状态的条目，
反复跑正向/远期/历史三种生成，断言 id、status、sets、pointer、setStarted 全不变）；
脚本对应「完成/跳过/未完成/进行中/有组数/已开始的记录全部原样保留」。

### Bug 3：每周规则必须严格生效

**Android 修复**：`StepRule` 是唯一入口，`WEEKLY` 只从 `normalizedPattern()[isoDayOfWeek]` 取值；
pattern 中不存在或为空数组的星期 → 返回空列表 → 不生成。
移除了原型里"按周补齐时按当前周生成缺失记录却不查星期"的分支。

**验证**：`每周任务只在 weeklyPattern 指定的星期生成`、`pattern 为空数组的星期不生成`、
`每周任务的步骤索引严格按当天分配`（周一[0] / 周三[1,2] / 周五[0,2]，周二无安排）。

### Bug 4：提前结束必须正确记录

**原型规则（保留）**：只有「所有计划组都真实做完且没有任何一组被跳过」才算完成；
否则记为 `incomplete`，首页显示「⚠ 未完成（提前结束）」，历史/详情显示红色「提前结束」。

**Android 修复/增强**：
1. `WorkoutEngine.finalStatus()` 固化该规则；
2. `WorkoutEngine.buildHistory()` 保留每组实际次数，跳过的组记 0，未做的步骤保留空列表
   → 界面显示「未记录」，不会被误标为完成；
3. **即时持久化**：`MainViewModel` 每次「开始本组 / 完成本组 / 跳过 / 撤销」都调用
   `repository.persistWorkout()` 写指针、`setStarted` 与组记录；
   退出页面或应用被杀后重新进入，`WorkoutEngine.start()` 会从保存值恢复。

**验证**：`WorkoutEngineTest` 13 条用例（轮换/传统顺序、完成/跳过/撤销、休息计时、
全部完成→completed、有跳过→incomplete、提前结束→incomplete、历史保留与未记录、
进度恢复、指针越界夹取）；脚本对应 16 条断言。

---

## 六、测试情况（如实说明）

### 已实际执行并通过

| 检查 | 命令 | 结果 |
|---|---|---|
| Android 逻辑规范（Bug 1~4 共 45 条断言） | `node _verify/android_spec_check.mjs` | **45 PASS / 0 FAIL** |
| Kotlin 同项目符号引用（漏 import / 名字写错） | `node _verify/kotlin_ref_check.mjs` | 27 个文件，**0 处问题** |
| Kotlin 源文件括号平衡（逐字符扫描，跳过注释/字符串/插值） | `node _verify/brace_check.mjs` | 27 个文件，**0 个不平衡** |
| Compose `Modifier.weight` 作用域 | 人工逐处核对 | 全部位于 Row/Column 内 |

`_verify/android_spec_check.mjs` 把 `core-schedule` 里的 `StepRule / Scheduler / WorkoutEngine`
逐行等义重写为 JavaScript，用 Node 跑与 Kotlin 测试同源的断言，因此**它验证的是算法与不变量，
不是 Android 运行结果**，也不能替代 Kotlin 单元测试。已在脚本头部注明这一点。

### 尚未执行

- **编译**：没有跑过 `gradlew assembleDebug`（无 SDK/Gradle/JDK17/网络）。
  → 因此 **Kotlin 源码可能仍存在编译错误**（例如某个 import 写法、某个 Compose API 的
  参数名在具体版本上的差异）。这是本次交付最大的不确定性。
- **`core-schedule` 的 JUnit 测试**：写好了但没跑过（无 kotlinc / 无 Gradle）。
- **真机/模拟器运行**：未安装、未点击、未截图。
- **Room 运行时校验**：DAO 的 SQL、外键级联、唯一索引冲突处理都要在设备上才能真正验证。
- **通知与前台服务**：`WorkoutTimerService` 的渠道创建、Android 13+ 权限流程、
  Android 14 `specialUse` 类型声明都未在设备上验证。
- **数据导出**：`TaskRepository.exportJson()` 目前只导出统计摘要，尚未接 SAF 写文件流程。

---

## 七、尚未完成的功能（后续阶段）

1. **编译与真机验证**（最高优先级）：见第六节「尚未执行」。本机无法构建，源码可能有编译错误。
2. **数据导入**：导出已完成（SAF `CreateDocument`，写完整 JSON），导入尚未实现。
3. **设置页的「去授权」按钮**：当前只显示授权状态，未提供跳转系统设置页的入口
   （权限在应用启动时已请求一次）。
4. **日期选择器**：当前用「−1天 / +1天」按钮，未接 Material3 `DatePicker`。

已完成的实现细节与逐项对照见 [`docs/02-feature-parity-checklist.md`](docs/02-feature-parity-checklist.md)。

---

## 八、改动记录

### 本轮（阶段 5 补齐：堆积任务两级流程）

| 文件 | 改动 |
|---|---|
| `ui/MainViewModel.kt` | `UiState` 新增 `backlogStepSelectionTaskId` / `backlogSelectedSteps`；新增 `backlogDo` / `backlogReset` / `toggleBacklogStep` / `selectAllBacklogSteps`；`backlogAction` 改为按勾选结果筛选步骤（顺延/覆盖/一起做都只作用于保留的步骤） |
| `ui/screens/WorkoutAndHistoryScreens.kt` | 堆积卡片改为两阶段渲染：第一阶段「做 / 跳过」，第二阶段「选择要保留的步骤」+「顺延 / 覆盖 / 一起做 / 返回」 |
| `ui/components/Common.kt` | `Divider` → `HorizontalDivider`（material3 1.2+ 正式 API，消除弃用告警） |
| `_verify/kotlin_api_risk_check.mjs` | 新增：Kotlin/Compose/Room 高风险写法扫描器 |
| `_verify/brace_check.mjs` | 新增：逐字符括号平衡检查（替代会被撇号误导的 PowerShell 正则） |

### 上一轮（阶段 5 收尾）

| 文件 | 改动 |
|---|---|
| `data/SettingsRepository.kt` | 新增：通知/震动开关的持久化读写（原型里这些开关只是静态文案） |
| `data/db/Daos.kt` | `SettingsDao.getAll()`；`ScheduleDao.getAllSetRecords()`；`HistoryDao.getAllHistorySteps()/getAllHistorySets()` |
| `data/TaskRepository.kt` | `exportJson()` 由「仅统计摘要」改为**完整数据导出**（任务/步骤/每周分配/日程/组记录/历史，含 JSON 转义） |
| `workout/WorkoutTimerService.kt` | 重写：返回是否启动成功、通知上增加「结束训练」按钮、API 33+ 未授权时不启动（避免 Android 14 抛 `ForegroundServiceStartNotAllowedException`）、`FOREGROUND_SERVICE_IMMEDIATE` |
| `ui/MainViewModel.kt` | 新增 `WorkoutServiceController` 桥接接口（ViewModel 不依赖 Context）；接线前台服务启动/更新/停止；休息归零震动；设置读写；权限结果回调 |
| `MainActivity.kt` | Android 13+ 通知权限请求；前台服务 + 震动桥接（API 31+ 用 `VibratorManager`）；SAF 导出（`CreateDocument` + `lifecycleScope`） |
| `ui/screens/WorkoutAndHistoryScreens.kt` | 设置页改为真实可切换的开关；新增「导出 JSON」按钮 |
| `ui/screens/PlanScreens.kt` | 删除任务增加 `AlertDialog` 二次确认 |
| `ui/screens/PlansScreen.kt` | 周视图日期卡片接线：点击进入该日的「添加任务」 |
