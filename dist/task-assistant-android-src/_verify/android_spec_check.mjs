// android_spec_check.mjs
// ---------------------------------------------------------------------------
// 为什么需要这个脚本？
//   本机没有 Android SDK、Gradle、JDK 17+，也没有任何网络（TLS 凭证不可用），
//   因此无法编译 android/ 工程，也无法运行 core-schedule 的 Kotlin 单元测试。
//   为了让「Bug 1~4 是否真的修好」有可执行的证据，这里把 android/core-schedule
//   里的 StepRule / Scheduler / WorkoutEngine **逐行等义地**重写为 JavaScript，
//   用 Node 跑同一批断言（android/core-schedule/src/test/.../*.kt 的镜像）。
//
// 这不是 Android 构建，也不能替代 Kotlin 测试；它验证的是算法与不变量。
// 如果以后能编译 Kotlin，应当以 :core-schedule:test 的结果为准。
// ---------------------------------------------------------------------------

let passCount = 0;
let failCount = 0;
const failures = [];

function check(name, condition, detail) {
  if (condition) {
    passCount++;
    console.log(`  PASS  ${name}`);
  } else {
    failCount++;
    failures.push(name);
    console.log(`  FAIL  ${name}${detail ? '  ->  ' + detail : ''}`);
  }
}

function eq(actual, expected) {
  return JSON.stringify(actual) === JSON.stringify(expected);
}

// ===================== DateKey 工具（对应 DateKey.kt） =====================
const MS_PER_DAY = 86400000;

function parseDate(value) {
  const [y, m, d] = value.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
}

function toKey(ms) {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

const plusDays = (key, n) => toKey(parseDate(key) + n * MS_PER_DAY);
const minusDays = (key, n) => plusDays(key, -n);
const minDate = (a, b) => (a <= b ? a : b);
const maxDate = (a, b) => (a >= b ? a : b);

/** ISO 星期：1=周一 … 7=周日（对应原型 isoDay()） */
function isoDayOfWeek(key) {
  const wd = new Date(parseDate(key)).getUTCDay(); // 0=周日
  return wd === 0 ? 7 : wd;
}

function* dateRange(from, to) {
  let d = from;
  while (d <= to) {
    yield d;
    d = plusDays(d, 1);
  }
}

// ===================== 领域模型（对应 TaskDef.kt / ScheduleModels.kt） =====
const RepeatMode = { ONCE: 'once', DAILY: 'daily', WEEKLY: 'weekly' };
const OrderType = { ROTATE: 'rotate', TRADITIONAL: 'traditional' };
const EntryStatus = {
  PENDING: 'pending',
  COMPLETED: 'completed',
  SKIPPED: 'skipped',
  IN_PROGRESS: 'inProgress',
  INCOMPLETE: 'incomplete',
};

function makeTask(spec) {
  return {
    id: spec.id,
    name: spec.name ?? spec.id,
    orderType: spec.orderType ?? OrderType.ROTATE,
    steps: spec.steps ?? [{ name: 'A', sets: 3, reps: 10 }],
    repeat: spec.repeat ?? RepeatMode.ONCE,
    startDate: spec.startDate,
    endDate: spec.endDate ?? null,
    stepIndexesByIsoDay: spec.stepIndexesByIsoDay ?? {},
    restEnabled: spec.restEnabled ?? false,
    restSeconds: spec.restSeconds ?? 60,
  };
}

function isDateInRange(task, date) {
  if (date < task.startDate) return false;
  if (task.endDate && date > task.endDate) return false;
  return true;
}

function normalizedPattern(task) {
  if (task.repeat !== RepeatMode.WEEKLY) return {};
  const max = task.steps.length;
  const out = {};
  Object.keys(task.stepIndexesByIsoDay).forEach(k => {
    const dow = Number(k);
    if (!(dow >= 1 && dow <= 7)) return;
    const list = [...new Set(task.stepIndexesByIsoDay[k].filter(i => i >= 0 && i < max))].sort((a, b) => a - b);
    if (list.length > 0) out[dow] = list;
  });
  return out;
}

function isTouchedEntry(entry) {
  return entry.status !== EntryStatus.PENDING
    || entry.sets.length > 0
    || entry.pointer !== 0
    || entry.setStarted;
}

// ===================== StepRule（对应 StepRule object） =====================
// 三条规则集中在一处：任务有效日期范围 + 重复方式 + 结果非空。
function stepIndexesFor(task, date) {
  if (!isDateInRange(task, date)) return [];           // Bug 1 的根治点
  if (task.steps.length === 0) return [];
  if (task.repeat === RepeatMode.ONCE || task.repeat === RepeatMode.DAILY) {
    return task.steps.map((_, i) => i);
  }
  return normalizedPattern(task)[isoDayOfWeek(date)] ?? []; // Bug 3 的根治点
}

// ===================== ScheduleStore（对应 ScheduleStore / InMemoryScheduleStore） =====
function makeStore(initial = []) {
  const map = new Map();
  initial.forEach(e => map.set(e.taskId + '@' + e.date, e));
  return {
    map,
    findEntry(taskId, date) { return map.get(taskId + '@' + date) ?? null; },
    allEntries() { return [...map.values()]; },
    insert(entry) {
      const key = entry.taskId + '@' + entry.date;
      if (map.has(key)) throw new Error('重复插入: ' + key);
      map.set(key, entry);
    },
  };
}

function makeEntry(spec) {
  return {
    id: spec.id,
    taskId: spec.taskId,
    date: spec.date,
    stepIndexes: spec.stepIndexes ?? [],
    status: spec.status ?? EntryStatus.PENDING,
    sets: spec.sets ?? [],
    pointer: spec.pointer ?? 0,
    setStarted: spec.setStarted ?? false,
  };
}

// ===================== Scheduler（对应 Scheduler.kt） =====================
const HORIZON_DAYS = 365;
const BACKFILL_DAYS = 365;

function makeScheduler(store, today, idPrefix = 'e') {
  const cursors = new Map();
  let counter = 0;
  const nextId = () => `${idPrefix}${++counter}`;
  const cursorsOf = id => cursors.get(id) ?? { forward: null, backward: null };
  const setCursors = (id, value) => cursors.set(id, value);

  function insertIfMissing(task, date) {
    if (store.findEntry(task.id, date) !== null) return [];        // Bug 2：已存在一律不动
    const idx = stepIndexesFor(task, date);
    if (idx.length === 0) return [];                               // 不做就不生成空安排
    const entry = makeEntry({ id: nextId(), taskId: task.id, date, stepIndexes: idx });
    store.insert(entry);
    return [entry];
  }

  function insertMissing(task, from, to) {
    if (from > to) return [];
    const out = [];
    for (const d of dateRange(from, to)) out.push(...insertIfMissing(task, d));
    return out;
  }

  function resolveForwardStart(task, floor) {
    let d = today;
    while (d >= floor) {
      if (store.findEntry(task.id, d) === null) return d;
      d = minusDays(d, 1);
    }
    return today;
  }

  function continuousBackfill(task, backwardScanEnd, backwardFloor) {
    const backLimit = maxDate(backwardFloor, task.startDate);
    if (backwardScanEnd < backLimit) return [];
    const cached = cursorsOf(task.id).backward;
    if (cached !== null && cached < backLimit) return [];          // 已到下限 → 整段跳过
    let d = minDate(cached ?? backwardScanEnd, backwardScanEnd);
    if (d < backLimit) d = backLimit;
    const out = [];
    while (d >= backLimit) {
      if (d >= task.startDate) out.push(...insertIfMissing(task, d));
      setCursors(task.id, { ...cursorsOf(task.id), backward: minusDays(d, 1) });
      d = minusDays(d, 1);
    }
    return out;
  }

  function fillViewWeek(task, viewWeekEnd, backwardFloor, forwardScanEnd) {
    if (viewWeekEnd < backwardFloor) return [];
    const weekStart = minusDays(viewWeekEnd, 6);
    const from = maxDate(weekStart, maxDate(backwardFloor, task.startDate)); // 同时夹住下限与开始日
    const to = minDate(viewWeekEnd, forwardScanEnd);
    if (from > to) return [];
    return insertMissing(task, from, to);
  }

  function ensureTaskCoverage(task, viewWeekEnd) {
    const out = [];
    const horizonEnd = plusDays(today, HORIZON_DAYS);
    const backwardFloor = minusDays(today, BACKFILL_DAYS);

    const forwardViewEnd = viewWeekEnd && viewWeekEnd > horizonEnd ? viewWeekEnd : horizonEnd;
    const forwardScanEnd = task.endDate ? minDate(forwardViewEnd, task.endDate) : forwardViewEnd;
    const backwardScanEnd = task.endDate ? minDate(today, task.endDate) : today;

    let forward = cursorsOf(task.id).forward;
    if (forward === null) forward = resolveForwardStart(task, backwardFloor);
    const rawForwardStart = forward;
    let fillFrom = maxDate(maxDate(rawForwardStart, task.startDate), backwardFloor);
    if (fillFrom <= forwardScanEnd) {
      out.push(...insertMissing(task, fillFrom, forwardScanEnd));
      setCursors(task.id, { ...cursorsOf(task.id), forward: plusDays(forwardScanEnd, 1) });
    } else if (fillFrom > rawForwardStart) {
      setCursors(task.id, { ...cursorsOf(task.id), forward: minDate(fillFrom, plusDays(forwardScanEnd, 1)) });
    }

    if (viewWeekEnd && viewWeekEnd < today) {
      out.push(...continuousBackfill(task, backwardScanEnd, backwardFloor));
      out.push(...fillViewWeek(task, viewWeekEnd, backwardFloor, forwardScanEnd));
    }
    return out;
  }

  return {
    cursorsOf,
    resetCursors() { cursors.clear(); },
    ensureTaskCoverage,
    ensureSchedule(tasks, viewWeekEnd = null) {
      const out = [];
      tasks.forEach(t => out.push(...ensureTaskCoverage(t, viewWeekEnd)));
      return out;
    },
  };
}

// ===================== WorkoutEngine（对应 WorkoutEngine.kt） =====================
function buildSequence(task, stepIndexes) {
  const valid = [...new Set(stepIndexes.filter(i => i >= 0 && i < task.steps.length))].sort((a, b) => a - b);
  if (valid.length === 0) return [];
  const slots = [];
  if (task.orderType === OrderType.ROTATE) {
    const maxSets = Math.max(...valid.map(i => task.steps[i].sets));
    for (let setIdx = 0; setIdx < maxSets; setIdx++) {
      valid.forEach(stepIndex => {
        const step = task.steps[stepIndex];
        if (setIdx < step.sets) slots.push({ stepIndex, setIndex: setIdx, targetReps: step.reps });
      });
    }
  } else {
    valid.forEach(stepIndex => {
      const step = task.steps[stepIndex];
      for (let setIdx = 0; setIdx < step.sets; setIdx++) {
        slots.push({ stepIndex, setIndex: setIdx, targetReps: step.reps });
      }
    });
  }
  return slots;
}

function workoutStart(task, entry) {
  const sequence = buildSequence(task, entry.stepIndexes);
  return {
    entryId: entry.id,
    taskId: task.id,
    date: entry.date,
    sequence,
    pointer: Math.min(Math.max(entry.pointer, 0), sequence.length),
    records: [...entry.sets],
    setStarted: entry.setStarted,
    restSecondsLeft: 0,
  };
}

function completeSet(session, task, actualReps) {
  const slot = session.sequence[session.pointer];
  if (!slot) return null;
  const record = {
    stepIndex: slot.stepIndex, setIndex: slot.setIndex,
    targetReps: slot.targetReps, actualReps: Math.max(actualReps, 0), skipped: false,
  };
  const nextPointer = session.pointer + 1;
  const rest = task.restEnabled && nextPointer < session.sequence.length ? task.restSeconds : 0;
  return { ...session, pointer: nextPointer, records: [...session.records, record], setStarted: false, restSecondsLeft: rest };
}

function skipSet(session) {
  const slot = session.sequence[session.pointer];
  if (!slot) return null;
  const record = {
    stepIndex: slot.stepIndex, setIndex: slot.setIndex,
    targetReps: slot.targetReps, actualReps: 0, skipped: true,
  };
  return { ...session, pointer: session.pointer + 1, records: [...session.records, record], setStarted: false, restSecondsLeft: 0 };
}

function undoLast(session) {
  if (session.records.length === 0) return null;
  return {
    ...session,
    pointer: Math.max(session.pointer - 1, 0),
    records: session.records.slice(0, -1),
    setStarted: false,
    restSecondsLeft: 0,
  };
}

const isAllRealDone = s => s.records.length === s.sequence.length && s.records.every(r => !r.skipped);
const finalStatus = s => (isAllRealDone(s) ? EntryStatus.COMPLETED : EntryStatus.INCOMPLETE);

function buildHistory(id, task, session, entryStepIndexes) {
  const byStep = new Map();
  session.records.forEach(r => {
    if (!byStep.has(r.stepIndex)) byStep.set(r.stepIndex, []);
    byStep.get(r.stepIndex).push(r.skipped ? 0 : r.actualReps);
  });
  const planned = [...new Set([...entryStepIndexes, ...byStep.keys()])].sort((a, b) => a - b);
  const steps = planned.filter(i => i < task.steps.length).map(i => ({
    name: task.steps[i].name,
    plannedSets: task.steps[i].sets,
    targetReps: task.steps[i].reps,
    actualReps: byStep.get(i) ?? [],
  }));
  return { id, date: session.date, entryId: session.entryId, taskName: task.name, steps, completed: isAllRealDone(session) };
}

// ===================== 断言套件 =====================
const TODAY = '2026-03-15';

console.log('=== Bug 1：每周任务开始日期边界 ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({
    id: 'w1', name: '每周任务', repeat: RepeatMode.WEEKLY,
    startDate: '2026-10-13', steps: [{ name: 'A', sets: 2, reps: 10 }],
    stepIndexesByIsoDay: { 2: [0] },   // 2026-10-13 是周二
  });
  sched.ensureSchedule([task], '2026-10-18');

  check('开始日前一天 2026-10-12 不生成', store.findEntry('w1', '2026-10-12') === null);
  check('开始日当天 2026-10-13 生成（周二符合规则）', store.findEntry('w1', '2026-10-13') !== null);
  check('后续周二 2026-10-20 生成', store.findEntry('w1', '2026-10-20') !== null);
  check('非周二 2026-10-14 不生成', store.findEntry('w1', '2026-10-14') === null);
  const leaked = store.allEntries().filter(e => e.date < task.startDate);
  check('开始日之前没有任何安排', leaked.length === 0, leaked.map(e => e.date).join(','));
}
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({ id: 'd1', name: '每天任务', repeat: RepeatMode.DAILY, startDate: '2026-10-13' });
  sched.ensureSchedule([task], '2026-10-18');
  const before = store.allEntries().filter(e => e.date < task.startDate).map(e => e.date);
  check('每天任务在开始日之前也不生成', before.length === 0, before.join(','));
  check('每天任务开始日当天生成', store.findEntry('d1', '2026-10-13') !== null);
}
{
  // 翻到开始日之前的历史周，也不得补出该任务
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({
    id: 'w2', name: '每周任务2', repeat: RepeatMode.WEEKLY,
    startDate: '2026-10-13', steps: [{ name: 'A', sets: 1, reps: 1 }],
    stepIndexesByIsoDay: { 2: [0] },
  });
  sched.ensureSchedule([task], '2026-10-18');
  sched.ensureSchedule([task], '2026-10-11');   // 视图在开始日之前
  const leaked = store.allEntries().filter(e => e.date < '2026-10-13').map(e => e.date);
  check('翻到开始日之前的历史周仍不生成', leaked.length === 0, leaked.join(','));
}

console.log('\n=== Bug 3：每周规则严格生效 ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({
    id: 'w3', name: '多步骤每周', repeat: RepeatMode.WEEKLY, startDate: '2026-10-01',
    steps: [{ name: 'A', sets: 1, reps: 1 }, { name: 'B', sets: 1, reps: 1 }, { name: 'C', sets: 1, reps: 1 }],
    stepIndexesByIsoDay: { 1: [0], 3: [1, 2], 5: [0, 2] },
  });
  sched.ensureSchedule([task], '2026-10-31');
  check('周一 2026-10-05 → [0]', eq(store.findEntry('w3', '2026-10-05')?.stepIndexes, [0]));
  check('周三 2026-10-07 → [1,2]', eq(store.findEntry('w3', '2026-10-07')?.stepIndexes, [1, 2]));
  check('周五 2026-10-09 → [0,2]', eq(store.findEntry('w3', '2026-10-09')?.stepIndexes, [0, 2]));
  check('周二 2026-10-06 无安排', store.findEntry('w3', '2026-10-06') === null);
  const wrongDow = store.allEntries().filter(e => ![1, 3, 5].includes(isoDayOfWeek(e.date)));
  check('所有生成日都属于分配星期', wrongDow.length === 0, wrongDow.map(e => e.date).join(','));
}
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({
    id: 'w4', name: '空数组星期', repeat: RepeatMode.WEEKLY, startDate: '2026-10-01',
    steps: [{ name: 'A', sets: 1, reps: 1 }],
    stepIndexesByIsoDay: { 2: [0], 3: [] },
  });
  sched.ensureSchedule([task], '2026-10-31');
  check('pattern 为空数组的周三 2026-10-14 不生成', store.findEntry('w4', '2026-10-14') === null);
  check('pattern 有内容的周二 2026-10-13 生成', store.findEntry('w4', '2026-10-13') !== null);
}

console.log('\n=== Bug 2：已有训练数据不被覆盖 ===');
{
  const task = makeTask({ id: 'p1', name: '保护', repeat: RepeatMode.DAILY, startDate: '2026-03-15' });
  const seed = makeStore();
  const seedSched = makeScheduler(seed, TODAY);
  seedSched.ensureSchedule([task], '2026-03-21');

  const probe = ['2026-03-16', '2026-03-17', '2026-03-18', '2026-03-19', '2026-03-20', '2026-03-21'];
  const statuses = [EntryStatus.COMPLETED, EntryStatus.SKIPPED, EntryStatus.INCOMPLETE, EntryStatus.IN_PROGRESS, EntryStatus.PENDING, EntryStatus.PENDING];
  const touched = probe.map((date, i) => {
    const original = seed.findEntry('p1', date);
    return makeEntry({
      ...original,
      status: statuses[i],
      sets: [{ stepIndex: 0, setIndex: 0, targetReps: 10, actualReps: 7 + i, skipped: false }],
      pointer: i,
      setStarted: i % 2 === 0,
    });
  });

  const store = makeStore(touched);
  const sched = makeScheduler(store, TODAY, 'x');
  for (let i = 0; i < 3; i++) {
    sched.ensureSchedule([task], '2027-06-01');
    sched.ensureSchedule([task], '2026-03-21');
    sched.ensureSchedule([task], '2026-01-05');
  }

  let allOk = true;
  const problems = [];
  touched.forEach(before => {
    const after = store.findEntry('p1', before.date);
    if (!after) { allOk = false; problems.push(before.date + ' 被删除'); return; }
    if (after.id !== before.id) { allOk = false; problems.push(before.date + ' id 变化'); }
    if (after.status !== before.status) { allOk = false; problems.push(before.date + ' status 被覆盖'); }
    if (!eq(after.sets, before.sets)) { allOk = false; problems.push(before.date + ' sets 被覆盖'); }
    if (after.pointer !== before.pointer) { allOk = false; problems.push(before.date + ' pointer 被覆盖'); }
    if (after.setStarted !== before.setStarted) { allOk = false; problems.push(before.date + ' setStarted 被覆盖'); }
  });
  check('完成/跳过/未完成/进行中/有组数/已开始的记录全部原样保留', allOk, problems.join('; '));
}

console.log('\n=== 去重与幂等 ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const tasks = [
    makeTask({ id: 'dup1', repeat: RepeatMode.DAILY, startDate: '2026-03-15' }),
    makeTask({ id: 'dup2', repeat: RepeatMode.WEEKLY, startDate: '2026-03-15', stepIndexesByIsoDay: { 2: [0] } }),
  ];
  for (let i = 0; i < 5; i++) {
    sched.ensureSchedule(tasks, '2027-01-01');
    sched.ensureSchedule(tasks, '2025-11-01');
  }
  const keys = store.allEntries().map(e => e.taskId + '@' + e.date);
  check('同一天同一任务不重复生成', keys.length === new Set(keys).size, '总数 ' + keys.length);

  const snapshot = keys.slice().sort().join(',');
  sched.ensureSchedule(tasks, '2027-01-01');
  const after = store.allEntries().map(e => e.taskId + '@' + e.date).sort().join(',');
  check('重复调用是幂等的', snapshot === after);
}

console.log('\n=== 生成范围与下限 ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({ id: 'r1', repeat: RepeatMode.DAILY, startDate: '2026-03-15', endDate: '2026-04-30' });
  sched.ensureSchedule([task], '2027-06-01');
  const afterEnd = store.allEntries().filter(e => e.date > '2026-04-30').map(e => e.date);
  check('结束日之后不生成', afterEnd.length === 0, afterEnd.slice(0, 5).join(','));
  check('结束日当天生成', store.findEntry('r1', '2026-04-30') !== null);
  check('结束日次日不生成', store.findEntry('r1', '2026-05-01') === null);
}
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({ id: 'r2', repeat: RepeatMode.DAILY, startDate: '2026-03-15' });
  sched.ensureSchedule([task], null);
  const count = store.allEntries().filter(e => e.date >= TODAY && e.date <= plusDays(TODAY, 365)).length;
  check('结束日为空时今天起 366 天全部生成', count === 366, '实际 ' + count);

  const sched2 = makeScheduler(store, TODAY, 'z');
  sched2.ensureSchedule([task], '2027-06-01');
  check('远期周视图仍能拿到安排', store.findEntry('r2', '2027-06-01') !== null);
}
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({ id: 'r3', repeat: RepeatMode.DAILY, startDate: '2025-01-01' });
  sched.ensureSchedule([task], null);
  const floor = minusDays(TODAY, 365);
  const tooOld = store.allEntries().filter(e => e.date < floor).map(e => e.date);
  check('不生成早于 max(开始日, 今天-365) 的日期', tooOld.length === 0, tooOld.slice(0, 5).join(','));
  sched.ensureSchedule([task], '2025-06-15');
  check('下限之内的历史周可被补齐', store.findEntry('r3', '2025-06-15') !== null);
}

console.log('\n=== 游标推进（每周任务的休息日） ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const task = makeTask({
    id: 'c1', repeat: RepeatMode.WEEKLY, startDate: '2026-03-15',
    steps: [{ name: 'A', sets: 1, reps: 1 }], stepIndexesByIsoDay: { 1: [0] }, // 只在周一
  });
  sched.ensureSchedule([task], plusDays(TODAY, 30));
  const cursor = sched.cursorsOf('c1').forward;
  check('前向游标推进到扫描结束日之后', cursor !== null && cursor > plusDays(TODAY, 30), String(cursor));
  sched.ensureSchedule([task], plusDays(TODAY, 30));
  check('再次调用游标不变', sched.cursorsOf('c1').forward === cursor);
}

console.log('\n=== 性能：连续翻 400 周 ===');
{
  const store = makeStore();
  const sched = makeScheduler(store, TODAY);
  const tasks = [
    makeTask({ id: 'perf1', repeat: RepeatMode.DAILY, startDate: '2026-03-15' }),
    makeTask({ id: 'perf2', repeat: RepeatMode.WEEKLY, startDate: '2026-03-15', stepIndexesByIsoDay: { 1: [0], 3: [0] } }),
  ];
  let t0 = Date.now();
  for (let w = 1; w <= 400; w++) sched.ensureSchedule(tasks, plusDays(TODAY, w * 7));
  const forwardMs = Date.now() - t0;
  t0 = Date.now();
  for (let w = 400; w >= 1; w--) sched.ensureSchedule(tasks, plusDays(TODAY, w * 7));
  const backwardMs = Date.now() - t0;
  const keys = store.allEntries().map(e => e.taskId + '@' + e.date);
  check('翻 400 周无重复条目', keys.length === new Set(keys).size, '总数 ' + keys.length);
  check('前向 400 周耗时 < 20s', forwardMs < 20000, forwardMs + 'ms');
  check('后向 400 周耗时 < 20s', backwardMs < 20000, backwardMs + 'ms');
  console.log(`        前向 ${forwardMs}ms / 后向 ${backwardMs}ms，条目 ${keys.length} 条`);
}

console.log('\n=== Bug 4：训练流程与提前结束 ===');
{
  const task = makeTask({
    id: 'k1', orderType: OrderType.ROTATE, repeat: RepeatMode.ONCE, startDate: '2026-03-15',
    steps: [{ name: '俯卧撑', sets: 2, reps: 10 }, { name: '深蹲', sets: 1, reps: 5 }],
  });
  const entry = makeEntry({ id: 'k1e', taskId: 'k1', date: '2026-03-15', stepIndexes: [0, 1] });

  const seq = buildSequence(task, [0, 1]);
  check('轮换式序列 = 俯1,深1,俯2', eq(seq, [
    { stepIndex: 0, setIndex: 0, targetReps: 10 },
    { stepIndex: 1, setIndex: 0, targetReps: 5 },
    { stepIndex: 0, setIndex: 1, targetReps: 10 },
  ]));

  const trad = buildSequence({ ...task, orderType: OrderType.TRADITIONAL }, [0, 1]);
  check('传统式序列 = 俯1,俯2,深1', eq(trad.map(s => [s.stepIndex, s.setIndex]),
    [[0, 0], [0, 1], [1, 0]]));

  // 全部做完 → 完成
  let s = workoutStart(task, entry);
  s = completeSet(s, task, 10);
  s = completeSet(s, task, 6);
  s = completeSet(s, task, 10);
  check('全部真实做完 → completed', finalStatus(s) === EntryStatus.COMPLETED);
  check('组数做齐但次数不足仍算完成', finalStatus(s) === EntryStatus.COMPLETED);

  // 有跳过 → 未完成
  let s2 = workoutStart(task, entry);
  s2 = completeSet(s2, task, 10);
  s2 = skipSet(s2);
  s2 = completeSet(s2, task, 10);
  check('存在跳过组 → incomplete', finalStatus(s2) === EntryStatus.INCOMPLETE);

  // 提前结束 → 未完成
  let s3 = workoutStart(task, entry);
  s3 = completeSet(s3, task, 10);
  check('只做一组就结束 → incomplete', finalStatus(s3) === EntryStatus.INCOMPLETE);

  // 历史记录
  const history = buildHistory('h1', task, s2, [0, 1]);
  check('历史 completed=false（提前结束/有跳过）', history.completed === false);
  const pushUp = history.steps.find(x => x.name === '俯卧撑');
  const squat = history.steps.find(x => x.name === '深蹲');
  // s2 = 完成 俯卧撑第1组(10) → 跳过 深蹲第1组 → 完成 俯卧撑第2组(10)：3 组做完但含跳过
  check('历史保留实际次数（跳过记为 0）', eq(pushUp.actualReps, [10, 10]), JSON.stringify(pushUp.actualReps));
  check('被跳过的步骤记录为 [0]', eq(squat.actualReps, [0]), JSON.stringify(squat.actualReps));

  // 未做的步骤应保留空数组
  const partial = buildHistory('h1b', task, s3, [0, 1]);
  const squatPartial = partial.steps.find(x => x.name === '深蹲');
  const pushUpPartial = partial.steps.find(x => x.name === '俯卧撑');
  check('未做的步骤保留空数组（界面显示未记录）', eq(squatPartial.actualReps, []), JSON.stringify(squatPartial.actualReps));
  check('已做一半的步骤只保留已记录的组', eq(pushUpPartial.actualReps, [10]), JSON.stringify(pushUpPartial.actualReps));

  // 撤销
  const undone = undoLast(s2);
  check('撤销回退指针并弹出记录', undone.pointer === s2.pointer - 1 && undone.records.length === s2.records.length - 1);

  // 休息
  const restTask = { ...task, restEnabled: true, restSeconds: 45 };
  let s4 = workoutStart(restTask, entry);
  s4 = completeSet(s4, restTask, 10);
  check('完成非最后一组后进入 45s 休息', s4.restSecondsLeft === 45);
  let s5 = workoutStart(restTask, makeEntry({ id: 'k1e', taskId: 'k1', date: '2026-03-15', stepIndexes: [0, 1] }));
  s5 = completeSet(s5, restTask, 10);   // 俯1（还有下一组）
  s5 = completeSet(s5, restTask, 5);    // 深1（还有下一组）
  s5 = completeSet(s5, restTask, 10);   // 俯2 是最后一组
  check('完成最后一组不再进入休息', s5.restSecondsLeft === 0 && s5.pointer === s5.sequence.length);

  // 进度恢复
  const saved = makeEntry({
    id: 'k1e', taskId: 'k1', date: '2026-03-15', stepIndexes: [0, 1],
    pointer: 1, sets: [{ stepIndex: 0, setIndex: 0, targetReps: 10, actualReps: 8, skipped: false }], setStarted: true,
  });
  const resumed = workoutStart(task, saved);
  check('保存的进度可恢复（指针/记录/已开始）',
    resumed.pointer === 1 && resumed.records.length === 1 && resumed.records[0].actualReps === 8 && resumed.setStarted === true);
}

// ===================== 汇总 =====================
console.log('\n========================================');
console.log(`SUMMARY pass=${passCount} fail=${failCount}`);
if (failCount > 0) {
  console.log('失败用例:');
  failures.forEach(f => console.log('  - ' + f));
}
process.exit(failCount > 0 ? 1 : 0);
