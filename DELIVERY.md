# 交付说明（DELIVERY）

本文件说明这次交付包含什么、哪些是真实执行过的、哪些没有，以及接手方需要做什么。
配套文档：`android/README.md`（构建运行说明）、`android/docs/01-prototype-analysis.md`（原型分析）、
`android/docs/02-feature-parity-checklist.md`（网页功能 → Android 实现逐项对照）。

---

## 1. 交付物清单

| 路径 | 内容 | 状态 |
|---|---|---|
| `android/` | 完整 Android Studio 工程（Kotlin + Compose + Room，minSdk 26） | ✅ 齐全（见第 2 节审计） |
| `android/README.md` | 构建、运行、测试命令与环境要求 | ✅ |
| `android/docs/` | 原型分析 + 功能对照清单 | ✅ |
| `.github/workflows/android-ci.yml` | GitHub Actions：跑单测 + 编译 Debug APK + 上传产物 | ✅ 已写（**未在 GitHub 上实际执行**） |
| `android/tools/generate-wrapper.sh` | 生成真实 Gradle Wrapper 的脚本 | ✅ 已写（**未执行**，见第 4 节） |
| `_verify/android_spec_check.mjs` | Bug 1~4 的可执行逻辑验证（45 条断言） | ✅ 已实际运行，45 PASS / 0 FAIL |
| `_verify/kotlin_ref_check.mjs` | Kotlin 同项目符号引用检查 | ✅ 已实际运行，0 处问题 |
| `_verify/brace_check.mjs` | Kotlin 括号平衡（逐字符状态机） | ✅ 已实际运行，27 文件全平衡 |
| `_verify/kotlin_api_risk_check.mjs` | Compose/Room 高风险写法扫描 | ✅ 已实际运行，2 处已人工确认为误报 |
| `_verify/android_resource_check.mjs` | Android 资源引用 + Manifest 组件注册校验 | ✅ 已实际运行，未发现问题 |
| `_verify/yaml_check.mjs` | 工作流结构校验 | ✅ 已实际运行，未发现问题 |
| `jihua2.html` | 原型（移植参照，未修改） | ✅ |
| `dist/task-assistant-android-src.zip` | 可独立交付的源码包 | ✅ 已生成 |

一键跑完全部检查：

```bash
node _verify/android_spec_check.mjs
node _verify/kotlin_ref_check.mjs
node _verify/brace_check.mjs
node _verify/kotlin_api_risk_check.mjs
node _verify/android_resource_check.mjs
node _verify/yaml_check.mjs
```

---

## 2. 项目文件齐全性（已审计）

`android/` 共 50 个文件（不含 build 产物）。审计确认以下均存在：

- 根：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`
- Wrapper：`gradle/wrapper/gradle-wrapper.properties`（**jar 与 gradlew 脚本缺失，见第 4 节**）
- `app/`：`build.gradle.kts`、`proguard-rules.pro`、`src/main/AndroidManifest.xml`
- `core-schedule/`：`build.gradle.kts` + 6 个主源文件 + 2 个测试文件
- 资源：`values/{strings,colors,themes}.xml`、`xml/{backup_rules,data_extraction_rules}.xml`、
  `drawable/ic_launcher_{foreground,background}.xml`、6 个 `mipmap-*` 密度目录（含 `anydpi-v26` 自适应图标）

本轮补齐的缺失项（原本会导致构建失败）：

| 原本缺失 | 影响 | 已补 |
|---|---|---|
| `app/proguard-rules.pro` | release 构建引用它 → 配置阶段失败 | ✅ |
| `mipmap-*/ic_launcher*.xml` | Manifest 引用 `@mipmap/ic_launcher` → 资源链接失败 | ✅ 自适应图标 + 各密度回退 |
| `local.properties`（CI 用） | AGP 找不到 SDK | ✅ 工作流里生成 |

同时修正了两处工程配置问题：

- `core-schedule/build.gradle.kts` 重复应用了同一个 Kotlin JVM 插件（`id("org.jetbrains.kotlin.jvm")` 与 `kotlin("jvm")`）→ 已去掉重复；
- `app/build.gradle.kts` 引入了 `navigation-compose` 但界面用状态驱动切换、并未使用 → 已移除该依赖。

---

## 3. 实际执行过的验证（真实结果）

```
$ node _verify/android_spec_check.mjs
  SUMMARY pass=45 fail=0            ← Bug 1/2/3/4 + 生成范围 + 去重 + 400 周性能

$ node _verify/kotlin_ref_check.mjs
  检查文件 27 个，发现疑似问题 0 处

$ node _verify/brace_check.mjs
  共 27 个 Kotlin 文件，0 个括号不平衡

$ node _verify/kotlin_api_risk_check.mjs
  命中 2 处（均为误报：一处在注释里提到 Context、一处的 weight 位于 Row 内）

$ node _verify/yaml_check.mjs
  工作流结构校验：未发现问题
```

说明：`android_spec_check.mjs` 是把 `core-schedule` 的逻辑**等义重写为 JavaScript** 后用 Node
验证算法与不变量（脚本头部已注明），**不等于** Kotlin 编译或 Android 运行结果。

---

## 4. Gradle Wrapper：**未真实生成**

现状：

| 文件 | 状态 |
|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | ✅ 存在，指向 Gradle 8.9 |
| `gradlew` / `gradlew.bat` | ❌ **不存在** |
| `gradle/wrapper/gradle-wrapper.jar` | ❌ **不存在**（约 43KB 二进制） |

为什么没有生成：生成 Wrapper 需要两件事——**Gradle 本体**和**网络**（下载发行包）。
本机既没有 Gradle，也没有网络（TLS 凭证不可用），全盘也搜不到任何 `gradle-launcher-*.jar`。
`gradle-wrapper.jar` 是二进制文件，**用文本伪造一个假 jar 是欺骗行为且必然不可用，所以我没有伪造**。

接手方生成方式（任选其一，一次即可）：

```bash
# 方式 A：本机已有 Gradle
cd android
gradle wrapper --gradle-version 8.9 --distribution-type bin

# 方式 B：用脚本（内部就是方式 A，并自动读取 properties 里的版本）
cd android
bash tools/generate-wrapper.sh
```

**注意：不需要 Wrapper 也能构建。** Android Studio 自带的 Gradle 会直接使用，
用已安装的 `gradle` 命令同样可以：

```bash
cd android
gradle :core-schedule:test
gradle :app:assembleDebug
```

CI 工作流里已经做了兼容：检测到缺少 Wrapper 时会用 runner 上的 gradle 先生成，再走 `./gradlew`。

---

## 5. 单元测试 / APK 构建 / APK 生成：**均未执行**

| 项目 | 是否执行 | 说明 |
|---|---|---|
| `:core-schedule:test`（Kotlin 单测，20 + 13 = 33 个用例） | ❌ 未执行 | 无 Kotlin 编译器、无 Gradle、无网络，依赖无法解析 |
| `:app:assembleDebug` | ❌ 未执行 | 无 Android SDK、无 AGP、无网络 |
| APK 是否生成 | ❌ **没有生成任何 APK** | 同上 |

我**不会**声称编译通过或 APK 已产出。

### 目前无法在本机验证的项目（诚实清单）

1. **Kotlin 源码是否能编译** —— 可能仍有编译错误（import 写法、Compose API 在具体版本的参数差异、KSP 生成错误）。
2. **Room 运行时行为** —— DAO 的 SQL 正确性、外键级联、`(taskId,date)` 唯一索引冲突处理、`@Transaction` 语义。
3. **Compose 界面** —— 排版、可组合函数的实际渲染、`Modifier.weight` 在真实布局中的表现。
4. **通知与前台服务** —— 通知渠道创建、Android 13+ 权限流程、Android 14 `specialUse` 类型声明是否被接受。
5. **震动、SAF 导出** —— 需要真机/模拟器。
6. **训练状态跨进程恢复** —— 需要真实数据库。

---

## 6. 接手方需要执行的操作（按顺序）

```bash
# 0) 解压 dist/task-assistant-android-src.zip（或直接使用 android/ 目录）

# 1) 生成 Wrapper（一次性，需要网络；也可以跳过，见第 4 节）
cd android
gradle wrapper --gradle-version 8.9 --distribution-type bin

# 2) 跑核心逻辑单元测试（不需要模拟器，最快）
./gradlew :core-schedule:test        # Windows: gradlew.bat :core-schedule:test

# 3) 编译 Debug APK
./gradlew :app:assembleDebug
#    产物：android/app/build/outputs/apk/debug/app-debug.apk

# 4) 安装到设备/模拟器
./gradlew :app:installDebug
```

用 Android Studio：`File → Open` 选择 `android/` 目录 → 等 Gradle Sync → Run。

**如果第 2 或第 3 步报错，请把完整报错原文（含首个 error 的前后 30 行）贴回来**，
我据此修 Kotlin / Compose / Room / KSP / Gradle 配置问题。这是消掉第 5 节全部不确定性的唯一途径。

云上构建（GitHub）：把仓库推上去后，工作流会跑 `:core-schedule:test` → `:app:assembleDebug`
→ 上传 `app-debug-apk` 与测试报告两个 artifact，也可以 `workflow_dispatch` 手动触发。

---

## 7. 当前重点业务逻辑的验证状态

| 业务规则 | 实现位置 | 逻辑验证 | 编译/真机验证 |
|---|---|---|---|
| 每周任务不早于开始日期生成 | `StepRule.stepIndexesFor` + `Scheduler.fillViewWeek` | ✅ 脚本 4 条断言 | ❌ |
| 每周规则严格控制（只看 weeklyPattern） | `StepRule` | ✅ 7 条断言 | ❌ |
| 补全不覆盖已有训练记录 | `ScheduleStore` 只读+插、`isTouched`、`INSERT IGNORE` | ✅ 6 条终态记录不变 | ❌ |
| 提前结束标记为未完成 | `WorkoutEngine.finalStatus` / `buildHistory` | ✅ 16 条断言 | ❌ |
| Room 持久化与训练状态恢复 | `TaskRepository.persistWorkout` + `WorkoutEngine.start` | ✅ 恢复逻辑已验证 | ❌ **Room 本身未验证** |
| 通知权限被拒时的降级 | `WorkoutTimerService.start` 返回 false → 界面内计时 | ⚠️ 代码有降级分支，**未在设备上验证** | ❌ |
| 完整 JSON 导出 | `TaskRepository.exportJson` | ⚠️ 结构与转义已写，**未实际写文件验证** | ❌ |
