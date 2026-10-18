// Real-browser test suite for jihua2.html, driven over the Chrome DevTools Protocol.
// Run unconfined:  node _test/run_tests.mjs      (Chrome needs named pipes)
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const APP_FILE = 'E:\\dsh\\duanlian\\jihua2.html';
const PROFILE = 'E:\\dsh\\duanlian\\_test\\profile';
const PORT = 9444;
const HTTP_PORT = 8123;

// 用 http:// 伺服页面：file:// 下 localStorage 不可用，而本应用依赖它持久化
const server = createServer((req, res) => {
  try {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(readFileSync(APP_FILE));
  } catch (e) { res.writeHead(500); res.end(String(e)); }
});
await new Promise(r => server.listen(HTTP_PORT, '127.0.0.1', r));
const APP_URL = `http://127.0.0.1:${HTTP_PORT}/jihua2.html`;

mkdirSync(PROFILE, { recursive: true });

const child = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--disable-crash-reporter', '--disable-breakpad',
  'about:blank'
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));

let browserWs = null;
for (let i = 0; i < 80; i++) {
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
    const j = await r.json();
    if (j.webSocketDebuggerUrl) { browserWs = j.webSocketDebuggerUrl; break; }
  } catch {}
  await sleep(250);
}
if (!browserWs) { console.error('FATAL: devtools endpoint not ready'); child.kill(); process.exit(2); }

const ws = new WebSocket(browserWs);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

let msgId = 0;
const pending = new Map();
const events = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
    return;
  }
  if (m.method) events.push(m);
};
function send(method, params = {}, sessionId) {
  const id = ++msgId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
  });
}

const { targetInfos } = await send('Target.getTargets');
const targetId = targetInfos.find(t => t.type === 'page').targetId;
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
await send('Runtime.enable', {}, sessionId);
await send('Page.enable', {}, sessionId);
await send('Log.enable', {}, sessionId);

// ---------- 在任何应用代码之前注入：假系统日期 + 错误收集 ----------
const FAKE_TODAY = new Date(2026, 2, 15, 10, 0, 0);   // 2026-03-15 周日
const BOOTSTRAP = `
(() => {
  const RealDate = Date;
  window.__fake = { on: true, base: new RealDate(${FAKE_TODAY.getTime()}), off: 0 };
  window.__nowMs = () => window.__fake.base.getTime() + (window.__fake.off || 0);
  window.Date = new Proxy(RealDate, {
    construct(t, args, nt) {
      if (window.__fake.on) return Reflect.construct(RealDate, args.length ? args : [window.__nowMs()], nt);
      return Reflect.construct(t, args, nt);
    },
    apply(t, thisArg, args) {
      if (window.__fake.on && args.length === 0) return new RealDate(window.__nowMs()).toString();
      return Reflect.apply(t, thisArg, args);
    },
    get(t, p) {
      if (p === 'now') return () => window.__fake.on ? window.__nowMs() : RealDate.now();
      const v = t[p];
      return typeof v === 'function' && p !== 'prototype' ? v.bind(t) : v;
    }
  });
  window.RealDate = RealDate;
  window.__errors = [];
  window.addEventListener('error', e => window.__errors.push(String(e.message)));
  const oe = console.error;
  console.error = (...a) => { window.__errors.push(a.map(String).join(' ')); oe(...a); };
  // 把系统时间往前推 n 天，用来模拟页面跨过午夜
  window.__d = (n) => { window.__fake.off = n * 86400000; };
})();
`;

async function evaluate(expr, { awaitPromise = true } = {}) {
  const r = await send('Runtime.evaluate', {
    expression: `(() => { ${expr} })()`, returnByValue: true, awaitPromise, userGesture: true
  }, sessionId);
  if (r.exceptionDetails) {
    throw new Error('页面异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  }
  return r.result.value;
}

async function loadApp() {
  await send('Page.navigate', { url: APP_URL }, sessionId);
  await sleep(400);
  await evaluate(`try { localStorage.clear(); } catch (e) {} return 1;`);
  await send('Page.addScriptToEvaluateOnNewDocument', { source: BOOTSTRAP }, sessionId);
  await send('Page.navigate', { url: APP_URL }, sessionId);
  await sleep(900);
  const ok = await evaluate(`return typeof state === 'object' && typeof ensureSchedule === 'function';`);
  if (!ok) throw new Error('应用未能初始化');
}

const PAGE_HELPERS = `
window.__reset = () => { state.tasks = []; state.schedule = {}; state.history = []; resetRollCursors(); };
window.__mk = (o) => {
  const t = Object.assign({
    id: 'task_' + o.name, name: o.name, orderType: 'rotate',
    steps: [{ name: 'A', sets: 3, reps: 10 }],
    restEnabled: false, restSeconds: 60,
    repeat: 'daily', startDate: '2026-03-15', endDate: '', weeklyPattern: null
  }, o);
  state.tasks.push(t); resetRollCursors(); return t;
};
window.__entryOn = (taskId, date) => {
  const e = (state.schedule[date] || []).find(x => x.taskId === taskId);
  return e ? { date, id: e.id, status: e.status, stepIndexes: [...e.stepIndexes],
               sets: (e.sets || []).length, pointer: e.pointer, setStarted: !!e.setStarted } : null;
};
window.__dups = () => {
  const bad = [];
  Object.keys(state.schedule).forEach(d => {
    const seen = {};
    (state.schedule[d] || []).forEach(e => {
      if (seen[e.taskId]) bad.push(d + ':' + e.taskId);
      seen[e.taskId] = 1;
    });
  });
  return bad;
};
// 逐日核对每条安排是否等于 weeklyPattern 算出来的分配
window.__patternCheck = (taskId) => {
  const task = getTask(taskId); const errs = [];
  Object.keys(state.schedule).forEach(d => {
    (state.schedule[d] || []).forEach(e => {
      if (e.taskId !== taskId) return;
      const want = stepIndexesForDate(task, d).join(',');
      const got = [...e.stepIndexes].sort((a, b) => a - b).join(',');
      if (want !== got) errs.push(d + ' 期望[' + want + '] 实际[' + got + ']');
    });
  });
  return errs;
};
window.__countBetween = (taskId, a, b) => {
  let n = 0, d = a;
  while (d <= b) { if (__entryOn(taskId, d)) n++; d = addDays(d, 1); }
  return n;
};
// 让 getWeekDays(offset) 正好落在 date 所在的那一周（周一到周日）
window.__weekOffsetTo = (anchor, date) => {
  const a = dateToObj(anchor), t = dateToObj(date);
  const aDow = a.getDay(), tDow = t.getDay();
  const aMon = new Date(a); aMon.setDate(a.getDate() + (aDow === 0 ? -6 : 1 - aDow));
  const tMon = new Date(t); tMon.setDate(t.getDate() + (tDow === 0 ? -6 : 1 - tDow));
  return Math.round((tMon - aMon) / (7 * 86400000));
};
window.__activate = (id) => {
  document.querySelectorAll('.page').forEach(el => el.classList.remove('active'));
  document.getElementById('page-' + id).classList.add('active');
  currentPage = id;
};
true;
`;

const results = [];
function record(id, name, status, detail, extra) {
  results.push({ id, name, status, detail, extra });
  console.log(`[${status}] ${id} ${name}\n        ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`);
}
async function test(id, name, fn) {
  try {
    // 每个用例开始前把假系统时钟拨回基准日，避免跨用例相互影响
    if (id !== 'T10' && id !== 'T1') await evaluate(`if (typeof __d === 'function') __d(0); return 1;`);
    record(id, name, 'PASS', await fn());
  } catch (e) { record(id, name, 'FAIL', String(e && e.message || e)); }
}
const assert = (cond, msg) => { if (!cond) throw new Error(msg); };
// Node 侧的日期加减（用于断言里计算期望日期）
function addDaysHelper(dateStr, n) {
  const p = dateStr.split('-').map(Number);
  const d = new Date(p[0], p[1] - 1, p[2]);
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// ================= 测试 =================
await loadApp();
await evaluate(PAGE_HELPERS);

await test('T1', '添加任务页：任务列表为空时显示提示', async () => {
  const r = await evaluate(`
    __reset(); renderPlans(); openAddTaskPage('2026-03-15');
    return document.getElementById('add-task-content').innerText.trim();
  `);
  assert(/还没有任务/.test(r), '未显示空提示，实际=' + JSON.stringify(r));
  return 'innerText=' + JSON.stringify(r);
});

await test('T2', '添加任务页：任务选择 / 步骤列表 / 全选 / 全不选', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '读书', steps: [{name:'A',sets:1,reps:1},{name:'B',sets:2,reps:2},{name:'C',sets:3,reps:3}] });
    renderPlans(); openAddTaskPage('2026-03-15');
    const opts = document.querySelectorAll('#at-task option').length;
    const rows = document.querySelectorAll('#at-steps-list .step-checkbox').length;
    const defaultChecked = document.querySelectorAll('#at-steps-list .step-checkbox.checked').length;
    document.querySelector('button[onclick*="selectAllAtSteps(false)"]').click();
    const none = document.querySelectorAll('#at-steps-list .step-checkbox.checked').length;
    document.querySelector('button[onclick*="selectAllAtSteps(true)"]').click();
    const all = document.querySelectorAll('#at-steps-list .step-checkbox.checked').length;
    document.querySelectorAll('#at-steps-list .step-checkbox')[1].click();
    const afterOne = document.querySelectorAll('#at-steps-list .step-checkbox.checked').length;
    // 切任务下拉
    const sel = document.getElementById('at-task'); sel.value = 'task_读书';
    onAddTaskTaskChange();
    const afterSwitch = document.querySelectorAll('#at-steps-list .step-checkbox.checked').length;
    return { opts, rows, defaultChecked, none, all, afterOne, afterSwitch };
  `);
  assert(r.opts === 1, '任务下拉项数=' + r.opts);
  assert(r.rows === 3, '步骤行数=' + r.rows);
  assert(r.defaultChecked === 3, '默认应全选，实际=' + r.defaultChecked);
  assert(r.none === 0, '全不选后仍有勾选=' + r.none);
  assert(r.all === 3, '全选后勾选数=' + r.all);
  assert(r.afterOne === 2, '点击取消一个后勾选数=' + r.afterOne);
  assert(r.afterSwitch === 3, '切换任务后未重置勾选=' + r.afterSwitch);
  return JSON.stringify(r);
});

await test('T2b', '添加任务页：勾选步骤后点「添加」→ 返回并生成安排', async () => {
  const r = await evaluate(`
    openAddTaskPage('2026-03-15');
    selectAllAtSteps(false);
    document.querySelectorAll('#at-steps-list .step-checkbox')[0].click();
    document.getElementById('at-date').value = '2026-03-20';
    confirmAddTask();
    return { entry: __entryOn('task_读书', '2026-03-20'), page: currentPage,
             activePage: document.querySelector('.page.active').id };
  `);
  assert(r.entry && r.entry.stepIndexes.join(',') === '0', '安排不对: ' + JSON.stringify(r.entry));
  assert(r.page === 'home' && r.activePage === 'page-home', '添加后未返回首页: ' + r.page + '/' + r.activePage);
  return 'entry=' + JSON.stringify(r.entry) + ' 返回页面=' + r.activePage;
});

await test('T2c', '添加任务页：同一天重复添加被拦截', async () => {
  const r = await evaluate(`
    openAddTaskPage('2026-03-15');
    document.getElementById('at-date').value = '2026-03-20';
    confirmAddTask();
    return { toast: document.getElementById('toast').textContent,
             count: (state.schedule['2026-03-20'] || []).filter(x => x.taskId === 'task_读书').length };
  `);
  assert(r.count === 1, '重复添加后条目数=' + r.count);
  return '提示=' + JSON.stringify(r.toast) + ' 条目数=' + r.count;
});

await test('T3', '新建任务后返回并显示安排', async () => {
  const r = await evaluate(`
    openNewPlanPage();
    draftPlan.name = '健身';
    draftPlan.steps = [{ name: '俯卧撑', sets: 2, reps: 10 }];
    onNewRepeatChange('once');
    newPlanForm.onceDate = '2026-03-18';
    savePlan();
    const savedId = state.tasks[state.tasks.length - 1].id;
    return { page: currentPage, activePage: document.querySelector('.page.active').id,
             onPlans: document.querySelectorAll('#plans-list .card').length,
             entry: __entryOn(savedId, '2026-03-18'),
             plansText: document.getElementById('plans-list').innerText.replace(/\\n/g, ' / ') };
  `);
  assert(r.page === 'plans' && r.activePage === 'page-plans', '未返回计划页: ' + r.activePage);
  assert(r.entry !== null, '2026-03-18 未生成安排');
  assert(/健身/.test(r.plansText), '计划列表未显示新任务: ' + r.plansText);
  return '计划页卡片=' + r.onPlans + ' 安排=' + JSON.stringify(r.entry);
});

// —— 修复一的核心回归 ——
await test('T4', '每天重复：远期周视图（超过 365 天）能正确获得安排', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '每日A', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    const horizonEnd = addDays(getRealToday(), 365);
    const atHorizon = __countBetween('task_每日A', '2026-03-15', horizonEnd);
    // 连续 53 次「下一周」→ 进入第 54 周（约 371 天后）
    for (let i = 0; i < 53; i++) changeWeek(1);
    __activate('plans');
    renderWeek();
    const days53 = getWeekDays(state.weekOffset);
    const covered53 = days53.filter(d => __entryOn('task_每日A', d)).length;
    const grid53 = document.getElementById('week-grid').innerText;
    // 继续到第 100 周（约 700 天后）
    while (state.weekOffset < 100) changeWeek(1);
    const days100 = getWeekDays(state.weekOffset);
    const covered100 = days100.filter(d => __entryOn('task_每日A', d)).length;
    return { horizonEnd, atHorizon, week54: days53[0] + '~' + days53[6], covered53,
             grid54: (grid53.match(/每日A/g) || []).length,
             week101: days100[0] + '~' + days100[6], covered100,
             uniqueIds: new Set(Object.keys(state.schedule).flatMap(d => state.schedule[d].map(e => e.id))).size,
             totalEntries: Object.keys(state.schedule).reduce((s,d)=>s+state.schedule[d].length,0),
             dups: __dups().length };
  `);
  assert(r.atHorizon === 366, '今天起 366 天内的条目数=' + r.atHorizon);
  assert(r.covered53 === 7, `第 54 周（${r.week54}）只有 ${r.covered53}/7 天有安排`);
  assert(r.grid54 === 7, `第 54 周视图只渲染了 ${r.grid54} 个任务`);
  assert(r.covered100 === 7, `第 101 周（${r.week101}）只有 ${r.covered100}/7 天有安排`);
  assert(r.dups === 0, '出现重复安排 ' + r.dups + ' 处');
  return `第54周=${r.week54}(${r.covered53}/7) 第101周=${r.week101}(${r.covered100}/7) 条目=${r.totalEntries} 无重复`;
});

await test('T4b', '每天重复：往前翻周视图能看到早期安排（回填有下限）', async () => {
  const r = await evaluate(`
    const before = Object.keys(state.schedule).length;
    for (let i = 0; i < 10; i++) changeWeek(-1);
    const days = getWeekDays(state.weekOffset);
    const covered = days.filter(d => __entryOn('task_每日A', d)).length;
    return { week: days[0] + '~' + days[6], covered, before, after: Object.keys(state.schedule).length };
  `);
  assert(r.covered === 7, `往前翻到的周（${r.week}）只有 ${r.covered}/7 天有安排`);
  return `周=${r.week} ${r.covered}/7 天有安排`;
});

await test('T5', '每周重复：普通周与远期周都按 weeklyPattern 显示', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '每周', repeat: 'weekly', startDate: '2026-03-15', endDate: '',
           steps: [{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1},{name:'C',sets:1,reps:1}],
           weeklyPattern: { 1: [0], 3: [1,2], 5: [0,2] } });
    ensureSchedule();
    state.simulatedDate = '2026-03-15'; state.weekOffset = 0; state.weekViewEnd = getWeekEndDate(0);
    switchTab('plans');   // 让周视图真正可见，innerText 才有内容
    const grab = (off) => getWeekDays(off).map(d => ({ d, dow: isoDay(d),
      idx: (__entryOn('task_每周', d) || { stepIndexes: null }).stepIndexes }));
    const normal = grab(0);
    // 本周（3-09~3-15）里唯一的安排日是任务开始日 3-15（周日），实际不该有任何安排
    __activate('plans'); renderWeek();
    const normalGrid = document.getElementById('week-grid').innerText;
    const normalCount = (normalGrid.match(/每周/g) || []).length;
    // 下一周（3-16~3-22）包含周一/三/五，应当渲染出 3 条
    changeWeek(1);
    __activate('plans'); renderWeek();
    const nextGrid = document.getElementById('week-grid').innerText;
    const nextWeek = getWeekDays(1).join(',');
    const nextCount = (nextGrid.match(/每周/g) || []).length;
    // 再推到约 420 天以后
    for (let i = 0; i < 59; i++) changeWeek(1);
    const far = grab(state.weekOffset);
    __activate('plans'); renderWeek();
    const farGrid = document.getElementById('week-grid').innerText;
    return { normal, far, farRange: document.getElementById('week-range-display').innerText,
             page: document.querySelector('.page.active').id, normalGrid, nextGrid, nextWeek,
             normalCount, nextCount, farCount: (farGrid.match(/每周/g) || []).length,
             patternErrs: __patternCheck('task_每周'), dups: __dups().length };
  `);
  const want = { 1: [0], 3: [1, 2], 5: [0, 2] };
  [['当前周', r.normal], ['远期周', r.far]].forEach(([label, days]) => {
    days.forEach(x => {
      // 任务开始日(2026-03-15)之前的日期本就不该有安排
      if (x.d < '2026-03-15') {
        assert(x.idx === null, `${label} ${x.d} 早于任务开始日，却有安排 ${JSON.stringify(x.idx)}`);
        return;
      }
      const expect = want[x.dow] || null;
      assert(JSON.stringify(x.idx) === JSON.stringify(expect),
        `${label} ${x.d}（周${x.dow}）期望 ${JSON.stringify(expect)} 实际 ${JSON.stringify(x.idx)}`);
    });
  });
  assert(r.normalCount === 0, `当前周（3-09~3-15，早于任务开始日）本不该有安排，实际渲染 ${r.normalCount} 条`);
  assert(r.nextCount === 3, `下一周（${r.nextWeek}）只渲染了 ${r.nextCount} 条，期望 3 条。网格=${JSON.stringify(r.nextGrid)}`);
  assert(r.farCount === 3, `远期周只渲染了 ${r.farCount} 条，期望 3 条`);
  assert(r.patternErrs.length === 0, 'weeklyPattern 与安排不一致: ' + JSON.stringify(r.patternErrs));
  assert(r.dups === 0, '出现重复安排');
  return `当前周无安排（正确），下一周 3 条，远期周=${r.farRange} 同样 3 条，分配与 weeklyPattern 完全一致`;
});

await test('T6', '编辑每周任务增加步骤：各星期分配仍正确', async () => {
  const r = await evaluate(`
    const task = getTask('task_每周');
    const before = JSON.parse(JSON.stringify(task.weeklyPattern));
    const snap = () => Object.keys(state.schedule).sort()
      .map(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_每周')
        .map(e => d + ':' + [...e.stepIndexes].sort((a,b)=>a-b).join('.') + ':' + e.id).join('')).join('|');
    const beforeSnap = snap();
    openEditPlan('task_每周');
    draftPlan.steps.push({ name: 'D', sets: 1, reps: 1 });
    savePlan();
    const after = getTask('task_每周').weeklyPattern;
    const afterSnap = snap();
    return { before, after, baseline: '{"1":[0],"3":[1,2],"5":[0,2]}',
             patternErrs: __patternCheck('task_每周'), dups: __dups().length,
             page: currentPage, entryCount: afterSnap.split('|').filter(Boolean).length,
             changedDates: afterSnap.split('|').filter((s, i) => s !== beforeSnap.split('|')[i]).length };
  `);
  const b = JSON.parse(r.baseline), a = r.after;
  assert(JSON.stringify(a['1']) === JSON.stringify([0, 3]), '周一（原 [0]）变成 ' + JSON.stringify(a['1']));
  assert(JSON.stringify(a['3']) === JSON.stringify([1, 2, 3]), '周三（原 [1,2]）变成 ' + JSON.stringify(a['3']));
  assert(JSON.stringify(a['5']) === JSON.stringify([0, 2, 3]), '周五（原 [0,2]）变成 ' + JSON.stringify(a['5']));
  [2, 4, 6, 7].forEach(d => {
    assert(a[d] === undefined || (Array.isArray(a[d]) && a[d].length === 0),
      `周${d} 原本没有分配，却变成了 ${JSON.stringify(a[d])}`);
  });
  assert(r.patternErrs.length === 0, 'weeklyPattern 与安排不一致: ' + JSON.stringify(r.patternErrs));
  assert(r.dups === 0, '出现重复安排');
  assert(r.page === 'plan-detail', '保存后未回到任务详情: ' + r.page);
  return '编辑后 pattern=' + JSON.stringify(a) + '，无分配的日子保持为空，全部安排与 pattern 一致';
});

await test('T6b', '编辑每天任务增加步骤：未来安排补上新步骤，历史记录不动', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '每日B', repeat: 'daily', startDate: '2026-03-15', endDate: '',
           steps: [{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1}] });
    ensureSchedule();
    const old = __entryOn('task_每日B', '2026-03-16');
    const hist = __entryOn('task_每日B', '2026-03-15');
    openEditPlan('task_每日B');
    draftPlan.steps.push({ name: 'C', sets: 1, reps: 1 });
    savePlan();
    return { oldEntry: old, now: __entryOn('task_每日B', '2026-03-16'),
             past: __entryOn('task_每日B', '2026-03-15'), pastIdSame: hist && __entryOn('task_每日B','2026-03-15').id === hist.id };
  `);
  assert(r.now.stepIndexes.join(',') === '0,1,2', '今天之后的安排未补上新步骤: ' + JSON.stringify(r.now.stepIndexes));
  assert(r.pastIdSame, '过去的安排被重建');
  return '未来安排=' + JSON.stringify(r.now.stepIndexes) + ' 过去安排 id 保持不变';
});

await test('T7', '已完成/已跳过/提前结束/进行中的安排不被循环生成覆盖', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '状态', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    const put = (date, status, extra) => {
      const e = (state.schedule[date] || []).find(x => x.taskId === 'task_状态');
      e.status = status; Object.assign(e, extra || {}); return e.id;
    };
    const ids = {
      done: put('2026-04-01', 'completed', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 }),
      skip: put('2026-04-02', 'skipped'),
      early: put('2026-04-03', 'incomplete', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:4,status:'done'}], pointer: 1 }),
      prog: put('2026-04-04', 'inProgress', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:6,status:'done'}], pointer: 1, setStarted: true })
    };
    const snap = () => ['2026-04-01','2026-04-02','2026-04-03','2026-04-04'].map(d => {
      const e = __entryOn('task_状态', d);
      return e ? [e.id, e.status, e.stepIndexes.join(','), e.sets, e.pointer, e.setStarted].join('|') : 'NULL';
    });
    const before = snap();
    for (let i = 0; i < 80; i++) changeWeek(1);
    for (let i = 0; i < 80; i++) changeWeek(-1);
    resetRollCursors(); ensureSchedule('2027-06-01'); ensureSchedule();
    const after = snap();
    const idsAfter = ['2026-04-01','2026-04-02','2026-04-03','2026-04-04'].map(d => (__entryOn('task_状态', d) || {}).id);
    return { before, after, ids, idsAfter, dups: __dups().length,
             allUnchanged: JSON.stringify(before) === JSON.stringify(after),
             idsUnchanged: Object.values(ids).join() === idsAfter.join() };
  `);
  assert(r.allUnchanged, '记录被改动:\n前=' + JSON.stringify(r.before) + '\n后=' + JSON.stringify(r.after));
  assert(r.idsUnchanged, '安排 id 被替换');
  assert(r.dups === 0, '出现重复安排');
  return '4 条记录 id/状态/组数/进度全部不变，且无重复';
});

await test('T8', '连续切换 400 周：耗时与页面响应', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '每日A', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    __mk({ name: '每周', repeat: 'weekly', startDate: '2026-03-15', endDate: '',
           steps: [{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1},{name:'C',sets:1,reps:1}],
           weeklyPattern: { 1: [0], 3: [1,2], 5: [0,2] } });
    ensureSchedule();
    const entries = () => Object.keys(state.schedule).reduce((s,d)=>s+state.schedule[d].length,0);
    const e0 = entries();
    const t0 = performance.now();
    for (let i = 0; i < 400; i++) changeWeek(1);
    const forward = performance.now() - t0;
    const e1 = entries();
    const t1 = performance.now();
    for (let i = 0; i < 400; i++) changeWeek(-1);
    const back = performance.now() - t1;
    const t2 = performance.now();
    state.simulatedDate = '2026-03-15';
    for (let i = 0; i < 400; i++) { state.weekOffset = i + 1; state.weekViewEnd = getWeekEndDate(state.weekOffset); ensureSchedule(state.weekViewEnd); }
    const repeatForward = performance.now() - t2;
    return { forward: Math.round(forward), back: Math.round(back), repeatForward: Math.round(repeatForward),
             e0, e1, e2: entries(), dups: __dups().length, offset: state.weekOffset,
             range: document.getElementById('week-range-display').innerText,
             errors: window.__errors.slice(0, 3) };
  `);
  assert(r.dups === 0, '切换过程中出现重复安排 ' + r.dups + ' 处');
  assert(r.errors.length === 0, '页面报错: ' + JSON.stringify(r.errors));
  return `首次连点 400 周 ${r.forward}ms（条目 ${r.e0}→${r.e1}），再连点 400 周 ${r.back}ms，重复走同样 400 周 ${r.repeatForward}ms（条目 ${r.e2}），结束周=${r.range}，无重复`;
});

await test('T9', '刷新页面后任务与日历安排仍然保留', async () => {
  const before = await evaluate(`
    __reset();
    __mk({ name: '持久A', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    const e = (state.schedule['2026-03-20'] || []).find(x => x.taskId === 'task_持久A');
    e.status = 'completed'; e.sets = [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}]; e.pointer = 1;
    state.history.push({ id: 'h1', date: '2026-03-20', entryId: e.id, taskName: '持久A', steps: [], completed: true });
    saveState(); flushSaveState();
    return JSON.stringify({ tasks: state.tasks.map(t => t.name).sort(),
      dates: Object.keys(state.schedule).length,
      entries: Object.keys(state.schedule).reduce((s,d)=>s+state.schedule[d].length,0),
      history: state.history.length, sample: __entryOn('task_持久A', '2026-03-20'),
      localStorageKeys: Object.keys(localStorage).length });
  `);
  await send('Page.navigate', { url: APP_URL }, sessionId);
  await sleep(900);
  await evaluate(PAGE_HELPERS);
  const after = await evaluate(`
    return JSON.stringify({ tasks: state.tasks.map(t => t.name).sort(),
      dates: Object.keys(state.schedule).length,
      entries: Object.keys(state.schedule).reduce((s,d)=>s+state.schedule[d].length,0),
      history: state.history.length, sample: __entryOn('task_持久A', '2026-03-20'),
      plansCards: (renderPlans(), document.querySelectorAll('#plans-list .card').length),
      homeText: (document.getElementById('current-date-display').textContent + ' | ' + document.getElementById('home-tasks').innerText.replace(/\\n/g,' ')).slice(0, 90) });
  `);
  const b = JSON.parse(before), a = JSON.parse(after);
  assert(JSON.stringify(b.tasks) === JSON.stringify(a.tasks), '任务列表变化:\n' + before + '\n' + after);
  assert(b.dates === a.dates && b.entries === a.entries, '安排数量变化:\n' + before + '\n' + after);
  assert(JSON.stringify(b.sample) === JSON.stringify(a.sample), '样本安排变化:\n' + JSON.stringify(b.sample) + ' vs ' + JSON.stringify(a.sample));
  assert(a.history === b.history, '历史记录数变化');
  assert(a.plansCards === b.tasks.length, '计划页卡片数=' + a.plansCards);
  return '刷新前=' + before + '\n        刷新后=' + after;
});

await test('T10', '点击「今天」会重新读取系统日期（模拟跨午夜）', async () => {
  const r = await evaluate(`
    __d(0);
    state.simulatedDate = '2020-01-01'; realToday = '2020-01-01';   // 模拟页面停在旧日期
    const staleNow = realToday;
    __d(2);                                                         // 系统时间跨过午夜
    const sysNow = getRealToday();
    jumpToToday();
    return { staleNow, sysNow, simulatedAfter: state.simulatedDate, realTodayAfter: realToday,
             displayed: document.getElementById('current-date-display').textContent };
  `);
  assert(r.sysNow === '2026-03-17', '假时钟未生效: ' + r.sysNow);
  assert(r.realTodayAfter === '2026-03-17', 'realToday 未刷新=' + r.realTodayAfter);
  assert(r.simulatedAfter === '2026-03-17', '点今天后 simulatedDate=' + r.simulatedAfter);
  assert(/2026-03-17/.test(r.displayed), '首页显示的日期未更新: ' + r.displayed);
  return JSON.stringify(r);
});

await test('T12', '训练流程：开始/记录/提前结束/历史红色标记仍然可用', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '训练A', repeat: 'once', startDate: '2026-03-15', endDate: '2026-03-15',
           steps: [{ name: '俯卧撑', sets: 2, reps: 10 }, { name: '深蹲', sets: 1, reps: 5 }] });
    state.simulatedDate = '2026-03-15';
    state.schedule['2026-03-15'] = [makeEntry('task_训练A', [0, 1])];
    renderHome();
    const entryId = state.schedule['2026-03-15'][0].id;
    startWorkout('2026-03-15', entryId);
    const s0 = { page: currentPage, pointer: currentWorkout.pointer, seqLen: currentWorkout.sequence.length,
                 firstName: document.querySelector('#workout-content .action-name').innerText,
                 btn: document.querySelector('#workout-content .action-buttons button').innerText };
    // 第一组：开始本组 → 完成本组
    handleSetButton();                       // 开始本组
    const startedFlag = currentWorkout.setStarted;
    window._actual = 10;
    handleSetButton();                       // 完成本组
    const afterOne = { pointer: currentWorkout.pointer, sets: currentWorkout.sets.length,
                       entryStatus: (state.schedule['2026-03-15'][0] || {}).status };
    // 第二组：跳过
    skipSet();
    // 第三组（深蹲）：提前结束
    finishWorkout();
    const entry = state.schedule['2026-03-15'][0];
    const h = state.history.find(x => x.entryId === entryId);
    switchTab('history');
    const historyHtml = document.getElementById('history-list').innerHTML;
    return { s0, startedFlag, afterOne, finalStatus: entry.status, sets: (entry.sets||[]).length,
             historyCompleted: h ? h.completed : null, historySteps: h ? h.steps.map(x => x.name + ':' + x.actual.join('/')) : null,
             redMark: /提前结束/.test(historyHtml), page: currentPage,
             sampleRow: (document.getElementById('history-list').innerText || '').split('\\n').slice(0, 6) };
  `);
  assert(r.s0.page === 'workout', '未进入训练页: ' + r.s0.page);
  assert(r.s0.seqLen === 3, '轮换式序列长度=' + r.s0.seqLen);
  assert(r.s0.firstName === '俯卧撑', '第一项=' + r.s0.firstName);
  assert(r.startedFlag === true, '「开始本组」未生效');
  assert(r.afterOne.pointer === 1 && r.afterOne.sets === 1, '完成一组后进度不对: ' + JSON.stringify(r.afterOne));
  assert(r.finalStatus === 'incomplete', '提前结束后状态=' + r.finalStatus + '（应为 incomplete）');
  assert(r.historyCompleted === false, '历史 completed=' + r.historyCompleted);
  assert(r.redMark, '历史列表没有「提前结束」标记');
  return `状态=${r.finalStatus} 历史completed=${r.historyCompleted} 红色标记=${r.redMark} 步骤=${JSON.stringify(r.historySteps)}`;
});

await test('T13', '进行中的训练：记录保留且滚动生成不会覆盖', async () => {
  const r = await evaluate(`
    __reset();
    state.simulatedDate = '2026-03-15'; state.weekOffset = 0;
    __mk({ name: '训练B', repeat: 'daily', startDate: '2026-03-15', endDate: '',
           steps: [{ name: 'A', sets: 3, reps: 8 }] });
    state.simulatedDate = '2026-03-15';
    ensureSchedule();
    const debug = { ids: state.tasks.map(x => x.id), keys: Object.keys(state.schedule).length,
                    d0315: (state.schedule['2026-03-15'] || []).map(e => e.taskId),
                    found: !!(state.schedule['2026-03-15'] || []).find(x => x.taskId === 'task_训练B') };
    const entryId = ((state.schedule['2026-03-15'] || []).find(x => x.taskId === 'task_训练B') || {}).id;
    if (!entryId) return { debug, error: '当天没有该任务的安排' };
    startWorkout('2026-03-15', entryId);
    handleSetButton(); window._actual = 8; handleSetButton();   // 完成第 1 组
    handleSetButton();                                          // 第 2 组开始但未完成
    switchTab('home');
    const ent = () => state.schedule['2026-03-15'].find(x => x.taskId === 'task_训练B') || {};
    const mid = { status: ent().status, sets: (ent().sets || []).length, pointer: ent().pointer,
                  setStarted: ent().setStarted,
                  homeShowsInProgress: /进行中/.test(document.getElementById('home-tasks').innerText) };
    resetRollCursors(); ensureSchedule('2027-06-01');
    for (let i = 0; i < 30; i++) changeWeek(1);
    for (let i = 0; i < 30; i++) changeWeek(-1);
    const e = state.schedule['2026-03-15'].find(x => x.taskId === 'task_训练B') || {};
    const after = { status: e.status, sets: (e.sets || []).length, pointer: e.pointer, setStarted: e.setStarted, id: e.id === entryId };
    resumeWorkout(entryId);
    const resumed = { pointer: currentWorkout.pointer, sets: currentWorkout.sets.length,
                      firstName: document.querySelector('#workout-content .action-name').innerText };
    return { mid, after, resumed, debug, dups: __dups().length };
  `);
  assert(r.mid.status === 'inProgress' && r.mid.sets === 1 && r.mid.pointer === 1 && r.mid.setStarted,
    '训练中的记录不对: ' + JSON.stringify(r.mid));
  assert(r.mid.homeShowsInProgress, '首页未显示「进行中」');
  assert(r.after.status === 'inProgress' && r.after.sets === 1 && r.after.pointer === 1 && r.after.setStarted && r.after.id,
    '滚动生成覆盖了进行中的训练: ' + JSON.stringify(r.after));
  assert(r.resumed.pointer === 1 && r.resumed.sets === 1, '继续训练未恢复到原进度: ' + JSON.stringify(r.resumed));
  return '进行中记录在 60 次翻周 + 重算后保持不变，继续训练恢复到第 2 组';
});

await test('T14', '回归：连续切到远期周后，之前看过的周依然有安排（原缺陷现象）', async () => {
  const r = await evaluate(`
    __reset();
    state.simulatedDate = '2026-03-15'; state.weekOffset = 0; state.weekViewEnd = getWeekEndDate(0);
    const t = __mk({ name: '回归每日', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    // 先记录第 2 周的覆盖情况，再往后翻
    const w2 = getWeekDays(1);
    const w2Missing = w2.filter(d => !__entryOn('task_回归每日', d));
    const w2Before = w2.length - w2Missing.length;
    for (let i = 0; i < 61; i++) changeWeek(1);   // 一路点到约 430 天后
    const far = getWeekDays(state.weekOffset);
    const farCovered = far.filter(d => __entryOn('task_回归每日', d)).length;
    for (let i = 0; i < 62; i++) changeWeek(-1);  // 再点回来（会经过第 2 周）
    const w2After = w2.filter(d => __entryOn('task_回归每日', d)).length;
    state.weekOffset = 1;                          // 停在「第 2 周」再渲染一次
    __activate('plans'); renderWeek();
    const gridCount = (document.getElementById('week-grid').innerText.match(/回归每日/g) || []).length;
    return { w2: w2.join(','), w2Missing, w2Before, w2After, farRange: far[0] + '~' + far[6], farCovered,
             keysHead: Object.keys(state.schedule).sort().slice(0, 5), gridCount, dups: __dups().length };
  `);
  assert(r.w2Before === 7, `第 2 周初始只有 ${r.w2Before}/7 天，缺 ${JSON.stringify(r.w2Missing)}`);
  assert(r.farCovered === 7, `远期周 ${r.farRange} 只有 ${r.farCovered}/7 天（原缺陷现象）`);
  assert(r.w2After === 7, '回到第 2 周后只有 ' + r.w2After + '/7 天');
  assert(r.gridCount === 7, '回到第 2 周后网格只渲染 ' + r.gridCount + ' 条');
  assert(r.dups === 0, '出现重复安排');
  return `第2周 7/7，远期周 ${r.farRange} 7/7，返回后仍 7/7，无重复`;
});

// ================= 本轮三个修复的回归测试 =================

await test('T15', '修复一：每天重复任务不在结束日期之后生成安排', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '限日每日', repeat: 'daily', startDate: '2026-03-15', endDate: '2026-04-30' });
    ensureSchedule('2027-06-01');            // 视图推到很远，结束日之外也不该生成
    const last = Object.keys(state.schedule).sort().pop();
    const afterCount = __countBetween('task_限日每日', '2026-05-01', '2027-06-01');
    const inRangeCount = __countBetween('task_限日每日', '2026-03-15', '2026-04-30');
    // 结束日之后同一任务的"完成/进行中"记录不能被清理
    state.schedule['2026-05-10'] = [Object.assign(makeEntry('task_限日每日', [0]),
      { status: 'completed', sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 })];
    state.schedule['2026-05-11'] = [Object.assign(makeEntry('task_限日每日', [0]),
      { status: 'inProgress', sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:4,status:'done'}], pointer: 1, setStarted: true })];
    scheduleNeedsPrune = true;
    ensureSchedule('2027-06-01');
    const keptDone = __entryOn('task_限日每日', '2026-05-10');
    const keptProg = __entryOn('task_限日每日', '2026-05-11');
    // 结束日之后残留的"未动过"待做安排应被清掉
    state.schedule['2026-05-12'] = [makeEntry('task_限日每日', [0])];
    scheduleNeedsPrune = true;
    ensureSchedule('2027-06-01');
    const staleGone = __entryOn('task_限日每日', '2026-05-12');
    return { last, afterCount, inRangeCount, keptDone, keptProg, staleGone, dups: __dups().length };
  `);
  assert(r.inRangeCount === 47, '2026-03-15~04-30 内的条目数=' + r.inRangeCount + '（应为 47）');
  assert(r.afterCount === 0, '结束日之后仍生成了 ' + r.afterCount + ' 条安排');
  assert(r.last <= '2026-05-11', '日程里最后一个日期=' + r.last + '，越过了结束日');
  assert(r.keptDone && r.keptDone.status === 'completed' && r.keptDone.sets === 1, '结束日之后的已完成记录被删除: ' + JSON.stringify(r.keptDone));
  assert(r.keptProg && r.keptProg.status === 'inProgress' && r.keptProg.pointer === 1, '结束日之后的进行中记录被删除: ' + JSON.stringify(r.keptProg));
  assert(r.staleGone === null, '结束日之后未动过的待做安排没被清掉');
  assert(r.dups === 0, '出现重复安排');
  return `inRange=${r.inRangeCount} afterEnd=${r.afterCount} 完成记录保留=${!!r.keptDone} 进行中保留=${!!r.keptProg} 待做已清=${r.staleGone === null}`;
});

await test('T15b', '修复一：结束日期为空时仍持续滚动生成', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '无限每日', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    const horizon = __countBetween('task_无限每日', '2026-03-15', addDays(getRealToday(), 365));
    ensureSchedule('2027-06-01');   // 远期周视图
    const far = __entryOn('task_无限每日', '2027-06-01');
    const last = Object.keys(state.schedule).sort().pop();
    return { horizon, far: !!far, last, dups: __dups().length };
  `);
  assert(r.horizon === 366, '今天起 366 天内的条目数=' + r.horizon);
  assert(r.far, '结束日为空却没有滚到视图日 2027-06-01');
  assert(r.dups === 0, '出现重复安排');
  return `今天起 366 天全部生成，视图日 2027-06-01 也有安排，最后日期=${r.last}`;
});

await test('T16', '修复一：每周重复任务不在结束日期之后生成安排', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '限日每周', repeat: 'weekly', startDate: '2026-03-15', endDate: '2026-06-30',
           steps: [{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1}],
           weeklyPattern: { 1: [0], 3: [0,1], 5: [1] } });
    ensureSchedule('2027-12-31');
    const entries = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_限日每周').map(e => d))
      .sort();
    const last = entries[entries.length - 1];
    const after = entries.filter(d => d > '2026-06-30');
    // 恰好落在结束日 2026-06-30（周二）之前的最后一个安排日是 6-29（周一）
    const expectedLast = '2026-06-29';
    const onPattern = entries.every(d => [1,3,5].includes(isoDay(d)));
    return { count: entries.length, last, expectedLast, after, onPattern, dups: __dups().length };
  `);
  assert(r.after.length === 0, '结束日之后仍有 ' + r.after.length + ' 条：' + JSON.stringify(r.after.slice(0, 5)));
  assert(r.last === r.expectedLast, '最后一个安排日=' + r.last + '，期望=' + r.expectedLast);
  assert(r.onPattern, '生成的日期里出现了非分配星期');
  assert(r.dups === 0, '出现重复安排');
  return `共 ${r.count} 条，最后一天 ${r.last}（结束日 2026-06-30 之后的 0 条），全部落在周一/三/五`;
});

await test('T17', '修复三：每周任务有休息日时游标持续前进，不重复扫描已处理区间', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '轮转每周', repeat: 'weekly', startDate: '2026-03-15', endDate: '',
           steps: [{name:'A',sets:1,reps:1}],
           weeklyPattern: { 1: [0] } });     // 只有周一有安排，周二~周日是"休息日"
    state.weekViewEnd = addDays(getRealToday(), 30);
    window.__taskEntryOnCalls = 0;
    ensureSchedule(state.weekViewEnd);
    const first = window.__taskEntryOnCalls;
    const cursorAfterFirst = JSON.stringify(rollCursors['task_轮转每周']);
    const keysAfterFirst = Object.keys(state.schedule).length;
    // 同样的视图范围再调 6 次：已经处理过的区间不该再被扫描
    const repeats = [];
    for (let i = 0; i < 6; i++) {
      window.__taskEntryOnCalls = 0;
      state.weekViewEnd = addDays(getRealToday(), 30);
      ensureSchedule(state.weekViewEnd);
      repeats.push(window.__taskEntryOnCalls);
    }
    // 再向远处的周推进，游标必须继续前进且只扫新增的那几天
    window.__taskEntryOnCalls = 0;
    for (let i = 0; i < 10; i++) changeWeek(1);
    const scanForNewWeek = window.__taskEntryOnCalls;
    const entries = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_轮转每周').map(e => d));
    const onlyMonday = entries.every(d => isoDay(d) === 1);
    const advanced = rollCursors['task_轮转每周'].forward > '2026-03-16';
    // 再调一次，确认游标已越过已处理区间（不前进就说明修复三失效）
    const cursorBeforeIdle = JSON.stringify(rollCursors['task_轮转每周']);
    ensureSchedule(state.weekViewEnd);
    const cursorAfterIdle = JSON.stringify(rollCursors['task_轮转每周']);
    return { first, cursorAfterFirst, keysAfterFirst, repeats, scanForNewWeek,
             cursorBeforeIdle, cursorAfterIdle, entryCount: entries.length, onlyMonday, advanced,
             noDuplicate: entries.length === new Set(entries).size, dups: __dups().length };
  `);
  assert(r.advanced, '前向游标没有前进: ' + r.cursorAfterFirst);
  assert(r.onlyMonday, '只有周一有分配，却生成了其它星期的安排');
  assert(r.noDuplicate, '同一天同一任务出现重复安排');
  assert(r.repeats.every(n => n === 0), '重复调用仍扫描了已处理区间，taskEntryOn 调用次数=' + JSON.stringify(r.repeats));
  assert(r.cursorBeforeIdle === r.cursorAfterIdle, '再次调用后游标又变化了: ' + r.cursorBeforeIdle + ' -> ' + r.cursorAfterIdle);
  assert(r.scanForNewWeek < 400, '推进到新的一周扫描次数异常=' + r.scanForNewWeek);
  assert(r.dups === 0, '出现重复安排');
  return `首次扫描 ${r.first} 次，重复调用 ${JSON.stringify(r.repeats)} 次，推 10 周只扫 ${r.scanForNewWeek} 次，${r.entryCount} 条全在周一`;
});

await test('T18', '修复三：重复调用游标稳定、条目数不变、不重复写入', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '稳定每周', repeat: 'weekly', startDate: '2026-03-15', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 1: [0] } });
    resetRollCursors();
    state.weekViewEnd = addDays(getRealToday(), 14);
    ensureSchedule(state.weekViewEnd);
    const c1 = JSON.stringify(rollCursors['task_稳定每周']);
    const entries1 = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_稳定每周').map(e => d + '#' + e.id));
    const dates1 = Object.keys(state.schedule).length;
    ensureSchedule(state.weekViewEnd);
    ensureSchedule(state.weekViewEnd);
    const c2 = JSON.stringify(rollCursors['task_稳定每周']);
    const entries2 = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_稳定每周').map(e => d + '#' + e.id));
    const dates2 = Object.keys(state.schedule).length;
    return { c1, c2, dates1, dates2,
             sameEntries: JSON.stringify(entries1) === JSON.stringify(entries2),
             entryCount: entries1.length, dups: __dups().length };
  `);
  assert(r.c1 === r.c2, '重复调用后游标发生变化: ' + r.c1 + ' -> ' + r.c2);
  assert(r.sameEntries, '重复调用后条目（含 entry id）发生变化');
  assert(r.dates1 === r.dates2, '重复调用后日程天数变化: ' + r.dates1 + ' -> ' + r.dates2);
  assert(r.dups === 0, '出现重复安排');
  return `游标稳定 ${r.c1}，${r.entryCount} 条安排（含 id）不变，日程天数 ${r.dates1} 不变`;
});

await test('T19', '修复二：历史周里缺失的安排能被补齐', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '补历史每日', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule(addDays(getRealToday(), 30));
    resetRollCursors();
    jumpToDate('2025-12-25');                 // 模拟用户已经翻过一次历史
    const cursorAfterFirstVisit = JSON.stringify(rollCursors['task_补历史每日']);
    // 按周视图的方式往回翻，找到包含 2025-10-15 的那一周
    let steps = 0, week = null;
    while (steps < 80) {
      const days = getWeekDays(state.weekOffset);
      if (days[0] <= '2025-10-15' && '2025-10-15' <= days[6]) { week = days; break; }
      if (days[6] < '2025-10-15') break;
      changeWeek(-1); steps++;
    }
    if (!week) return { err: '没有翻到目标周' };
    // 挖掉整周，模拟"这一周还没生成"
    week.forEach(d => { delete state.schedule[d]; });
    const before = week.filter(d => __entryOn('task_补历史每日', d)).length;
    ensureSchedule(state.weekViewEnd);        // 停在那一周时的补生成
    const after = week.filter(d => __entryOn('task_补历史每日', d)).length;
    __activate('plans'); renderWeek();
    const grid = document.getElementById('week-grid').innerText;
    return { before, after, steps, weekShown: week.join(','), cursorAfterFirstVisit,
             gridCount: (grid.match(/补历史每日/g) || []).length, dups: __dups().length };
  `);
  assert(!r.err, r.err || '');
  assert(r.before === 0, '测试前提不成立：挖掉的周本来就有 ' + r.before + ' 条安排');
  assert(r.after === 7, `翻到该周后只补齐了 ${r.after}/7 天（周=${r.weekShown}）`);
  assert(r.gridCount === 7, '周视图只渲染了 ' + r.gridCount + ' 条');
  assert(r.dups === 0, '出现重复安排');
  return `${r.steps} 次翻周后停在 ${r.weekShown}，挖掉 7 天 → 补齐 ${r.after}/7，网格 ${r.gridCount} 条`;
});

await test('T19b', '修复二：回填游标已到下限后，仍能补齐历史周里缺的日期', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '定点每日', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule(addDays(getRealToday(), 30));
    resetRollCursors();
    jumpToDate('2025-04-01');                 // 这一跳会把回填游标一路推到下限
    const cursorAtFloor = JSON.stringify(rollCursors['task_定点每日']);
    // 再翻到 2025-09-15 那一周（在下限之内，但已经"看过"过区间之外）
    let steps = 0, week = null;
    while (steps < 80) {
      const days = getWeekDays(state.weekOffset);
      if (days[0] <= '2025-09-15' && '2025-09-15' <= days[6]) { week = days; break; }
      changeWeek(1); steps++;
    }
    if (!week) return { err: '没有翻到目标周' };
    week.forEach(d => { delete state.schedule[d]; });
    const before = week.filter(d => __entryOn('task_定点每日', d)).length;
    ensureSchedule(state.weekViewEnd);
    const after = week.filter(d => __entryOn('task_定点每日', d)).length;
    return { cursorAtFloor, cursorAfter: JSON.stringify(rollCursors['task_定点每日']),
             before, after, weekShown: week.join(','), dups: __dups().length };
  `);
  assert(!r.err, r.err || '');
  assert(r.before === 0, '测试前提不成立：挖掉的周本来就有 ' + r.before + ' 条安排');
  assert(r.after === 7, `游标已在下限时只补齐了 ${r.after}/7 天（周=${r.weekShown}）`);
  assert(r.dups === 0, '出现重复安排');
  return `回填游标=${r.cursorAtFloor} 时翻到 ${r.weekShown}，补齐 ${r.after}/7 天（定点补齐生效）`;
});

await test('T20', '修复二：历史回填下限 = max(任务开始日, 今天-365天)', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '下限每日', repeat: 'daily', startDate: '2025-01-01', endDate: '' });
    ensureSchedule();
    const fnBody = ensureTaskCoverage.toString();
    const codeVersion = { clamp: fnBody.includes('fillFrom < backwardBound'),
                          range: fnBody.includes('fillMissingInRange'),
                          targeted: fnBody.includes('const vTo = viewEnd > backTarget') };
    const floor = addDays(getRealToday(), -365);
    const earliestAfterForward = Object.keys(state.schedule).sort()[0];
    // 下限之前挖掉一整周（2025-03-11 周二 ~ 2025-03-15 周六）
    const beforeWeek = ['2025-03-11','2025-03-12','2025-03-13','2025-03-14','2025-03-15'];
    beforeWeek.forEach(d => { delete state.schedule[d]; });
    // 下限之后挖掉一整周（2025-04-07 周一 ~ 2025-04-13 周日）
    const afterWeek = ['2025-04-07','2025-04-08','2025-04-09','2025-04-10','2025-04-11','2025-04-12','2025-04-13'];
    afterWeek.forEach(d => { delete state.schedule[d]; });
    resetRollCursors();
    jumpToDate('2025-04-09');                 // 跳到下限之后的历史周，触发回填
    const weekShown = getWeekDays(state.weekOffset).join(',');
    const earliestAfterJump = Object.keys(state.schedule).sort()[0];
    let earliestDuringWalk = earliestAfterJump;
    for (let i = 0; i < 60; i++) {
      changeWeek(-1);
      const e = Object.keys(state.schedule).sort()[0];
      if (e < earliestDuringWalk) earliestDuringWalk = e;
    }
    return {
      floor, floorFromTask: state.tasks[0].startDate, weekShown, earliestAfterForward, codeVersion,
      earliestAfterJump, earliestDuringWalk,
      earliestGenerated: Object.keys(state.schedule).sort()[0],
      belowFloorList: Object.keys(state.schedule).filter(d => d < floor).sort().slice(0, 8),
      beforeWeekFilled: beforeWeek.filter(d => d < floor && __entryOn('task_下限每日', d)).length,
      afterWeekFilled: afterWeek.filter(d => __entryOn('task_下限每日', d)).length,
      cursor: JSON.stringify(rollCursors['task_下限每日']),
      belowFloorCount: Object.keys(state.schedule).filter(d => d < floor).length,
      finalRange: getWeekDays(state.weekOffset).join(','), dups: __dups().length
    };
  `);
  assert(r.earliestAfterForward === r.floor,
    `首次生成的最早日期=${r.earliestAfterForward}，应为下限 ${r.floor}`);
  assert(r.beforeWeekFilled === 0,
    `低于下限（${r.floor}）的 ${r.beforeWeekFilled} 天被生成了，earliest=${r.earliestGenerated}，越界日期=${JSON.stringify(r.belowFloorList)}，jump后最早=${r.earliestAfterJump}`);
  assert(r.belowFloorCount === 0, `日程里有 ${r.belowFloorCount} 天早于下限`);
  assert(r.afterWeekFilled === 7, `下限之上的历史周只补齐了 ${r.afterWeekFilled}/7 天（周=${r.weekShown}）`);
  assert(r.earliestGenerated >= r.floor,
    `生成了早于下限的日期: earliest=${r.earliestGenerated}, floor=${r.floor}, 代码版本=${JSON.stringify(r.codeVersion)}`);
  assert(r.dups === 0, '出现重复安排');
  return `下限=${r.floor}，日程最早=${r.earliestGenerated}；下限前 0/5、下限后 ${r.afterWeekFilled}/7；翻到 ${r.finalRange}`;
});

await test('T21', '修复二：回填不覆盖已完成/已跳过/提前结束/进行中的记录', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '保护每日', repeat: 'daily', startDate: '2026-03-15', endDate: '' });
    ensureSchedule();
    // 先把 2025-11-03~06 补出来（模拟这些日期此前已经生成过）
    resetRollCursors();
    jumpToDate('2025-11-05');
    const points = ['2025-11-03','2025-11-04','2025-11-05','2025-11-06'];
    const missing = points.filter(d => !__entryOn('task_保护每日', d));
    if (missing.length) return { err: '这 4 天没有被补出来: ' + missing.join(',') };
    const put = (date, status, extra) => {
      const e = (state.schedule[date] || []).find(x => x.taskId === 'task_保护每日');
      if (!e) throw new Error(date + ' 没有该任务的安排');
      e.status = status; Object.assign(e, extra || {}); return e.id;
    };
    const ids = {
      done: put('2025-11-03', 'completed', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 }),
      skip: put('2025-11-04', 'skipped'),
      early: put('2025-11-05', 'incomplete', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:3,status:'done'}], pointer: 1 }),
      prog: put('2025-11-06', 'inProgress', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:5,status:'done'}], pointer: 1, setStarted: true })
    };
    const snap = () => ['2025-11-03','2025-11-04','2025-11-05','2025-11-06'].map(d => {
      const e = __entryOn('task_保护每日', d);
      return e ? [e.id, e.status, e.stepIndexes.join(','), e.sets, e.pointer, e.setStarted].join('|') : 'NULL';
    });
    const before = snap();
    // 挖掉同一周内、但不属于那 4 条受保护记录的日期（都落在 jumpToDate('2025-11-05')
    // 会补的那一周 11-03~11-09 内），再翻到那一周触发回填
    const holes = ['2025-11-07','2025-11-08','2025-11-09'];
    holes.forEach(d => { delete state.schedule[d]; });
    resetRollCursors();
    jumpToDate('2025-11-05');
    const after = snap();
    return { before, after, ids, week: '2025-11-03~2025-11-09',
             filledOthers: holes.filter(d => __entryOn('task_保护每日', d)).length, dups: __dups().length };
  `);
  assert(!r.err, r.err || '');
  assert(JSON.stringify(r.before) === JSON.stringify(r.after),
    '回填改动了已有记录:\n前=' + JSON.stringify(r.before) + '\n后=' + JSON.stringify(r.after));
  assert(r.filledOthers === 3, `同周被挖掉的 3 天只补齐了 ${r.filledOthers} 天（周=${r.week}）`);
  assert(r.dups === 0, '出现重复安排');
  return '4 条记录（完成/跳过/提前结束/进行中）id、状态、组数、进度全部不变，同周挖掉的 3 天被补齐';
});

// ================= A 段连续回填的重复扫描修复 =================

await test('T22', 'A段首次回填：游标从空推进到下限', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段基本', repeat: 'daily', startDate: '2025-01-01', endDate: '' });
    ensureSchedule();
    resetRollCursors();                       // 回到「还没有任何回填」的状态
    const floor = addDays(getRealToday(), -365);
    const cursorBefore = JSON.stringify(rollCursors['task_A段基本']);
    jumpToDate('2025-06-15');                 // 第一次往前跳 → 触发 A 段连续回填
    const cursorAfter = JSON.stringify(rollCursors['task_A段基本']);
    return { floor, cursorBefore, cursorAfter, taskStart: getTask('task_A段基本').startDate,
             earliest: Object.keys(state.schedule).sort()[0],
             cursorDay: __entryOn('task_A段基本', cursorAfter ? JSON.parse(cursorAfter).backward : 'x'),
             belowFloor: Object.keys(state.schedule).filter(d => d < floor).length,
             dups: __dups().length };
  `);
  assert(!r.cursorBefore, '测试前提不成立：清空后游标不是空，实际=' + r.cursorBefore);
  const back = r.cursorAfter ? JSON.parse(r.cursorAfter).backward : null;
  assert(back, 'A段跑完后游标仍为空');
  // 游标记录的是「下一个要处理的日期」，走到下限时它就是下限的前一天
  assert(back < r.floor, `A段游标 ${back} 没有走过下限 ${r.floor}`);
  assert(back >= addDaysHelper(r.floor, -1), `A段游标 ${back} 越过了下限一天以上`);
  assert(r.earliest === r.floor, `日程最早日期=${r.earliest}，应为下限 ${r.floor}`);
  assert(r.belowFloor === 0, `生成了 ${r.belowFloor} 天低于下限的日期`);
  assert(r.dups === 0, '出现重复安排');
  return `游标 null → ${back}（下限 ${r.floor}，即再往前一天），日程最早=${r.earliest}，下限之前 0 天`;
});

await test('T23', 'A段到达下限后连续翻 20 个历史周，不再重复扫描整段 A 段', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段效率', repeat: 'daily', startDate: '2025-01-01', endDate: '' });
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2025-06-15');                 // 先让 A 段跑到下限
    const floor = addDays(getRealToday(), -365);
    const cursorAtFloor = JSON.stringify(rollCursors['task_A段效率']);
    // 用 addDays 调用次数衡量 A 段工作量：A 段每处理一天调一次，
    // B 段最多 7 天（约 14 次），A 段若重扫则每周会有 365 次以上
    const realAdd = addDays;
    const perWeek = [];
    for (let i = 0; i < 20; i++) {
      let calls = 0;
      window.addDays = (d, n) => { calls++; return realAdd(d, n); };
      changeWeek(-1);
      window.addDays = realAdd;
      perWeek.push(calls);
    }
    return { floor, cursorAtFloor, cursorAfter: JSON.stringify(rollCursors['task_A段效率']),
             perWeek, maxWeek: Math.max.apply(null, perWeek), total: perWeek.reduce((a, b) => a + b, 0),
             belowFloor: Object.keys(state.schedule).filter(d => d < floor).length, dups: __dups().length };
  `);
  assert(r.maxWeek < 150,
    `某一周 addDays 调用 ${r.maxWeek} 次，说明 A 段被整段重扫了（A 段一次约 365 次）。逐周=${JSON.stringify(r.perWeek)}`);
  assert(r.cursorAfter === r.cursorAtFloor, `游标被重新推动：${r.cursorAtFloor} → ${r.cursorAfter}`);
  assert(r.belowFloor === 0, `生成了 ${r.belowFloor} 天低于下限的日期`);
  assert(r.dups === 0, '出现重复安排');
  return `A段已在 ${r.cursorAtFloor}，翻 20 周逐周 addDays=${JSON.stringify(r.perWeek)}（最多 ${r.maxWeek} 次，A 段已整段跳过）`;
});

await test('T24', 'A段已到下限后，B段仍能补齐被人为删除的历史周', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段后B段', repeat: 'daily', startDate: '2025-01-01', endDate: '' });
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2025-06-01');                 // A 段先跑到下限
    const cursorAtFloor = JSON.stringify(rollCursors['task_A段后B段']);
    // 往回翻到包含 2025-04-16 的那一周（晚于任务开始日、仍在下限之内）
    let steps = 0, found = null;
    while (steps < 120) {
      const days = getWeekDays(state.weekOffset);
      if (days[0] <= '2025-04-16' && '2025-04-16' <= days[6]) { found = days; break; }
      if (days[0] < '2025-04-16') break;
      changeWeek(-1); steps++;
    }
    if (!found) return { err: '没有翻到目标周' };
    found.forEach(d => { delete state.schedule[d]; });   // 人为删除整周
    const before = found.filter(d => __entryOn('task_A段后B段', d)).length;
    ensureSchedule(state.weekViewEnd);        // A 段已到下限，只能靠 B 段定点补齐
    const after = found.filter(d => __entryOn('task_A段后B段', d)).length;
    return { cursorAtFloor, cursorAfter: JSON.stringify(rollCursors['task_A段后B段']),
             before, after, week: found.join(','), steps, dups: __dups().length };
  `);
  assert(!r.err, r.err || '');
  assert(r.cursorAtFloor && JSON.parse(r.cursorAtFloor).backward, 'A段没有产生游标');
  assert(r.before === 0, '测试前提不成立：该周本来就有 ' + r.before + ' 条安排');
  assert(r.after === 7, `A段到下限后，B段只补齐了 ${r.after}/7 天（周=${r.week}）`);
  assert(r.dups === 0, '出现重复安排');
  return `A段游标=${r.cursorAtFloor} 已到下限，往回翻 ${r.steps} 周到 ${r.week}，删除整周后 B 段补齐 ${r.after}/7 天`;
});

await test('T25', '每周休息日不阻止 A 段游标推进', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段每周', repeat: 'weekly', startDate: '2025-06-01', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 1: [0], 3: [0] } });   // 只做周一/周三
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2025-12-01');                 // 触发 A 段（任务开始日 2025-06-01 在下限之后）
    const cursorAfterFirst = JSON.parse(JSON.stringify(rollCursors['task_A段每周']));
    const back1 = cursorAfterFirst.backward;
    // 再翻 10 周，确认休息日不会让游标原地踏步（A 段应已跳过）
    const realAdd = addDays;
    const perWeek = [];
    for (let i = 0; i < 10; i++) {
      let calls = 0;
      window.addDays = (d, n) => { calls++; return realAdd(d, n); };
      changeWeek(-1);
      window.addDays = realAdd;
      perWeek.push(calls);
    }
    const entries = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_A段每周').map(e => d));
    return { cursorAfterFirst, cursorAfter: JSON.stringify(rollCursors['task_A段每周']),
             back1, perWeek, maxWeek: Math.max.apply(null, perWeek),
             onlyMonWed: entries.every(d => [1,3].includes(isoDay(d))),
             count: entries.length, dups: __dups().length };
  `);
  const back2 = JSON.parse(r.cursorAfter).backward;
  assert(r.onlyMonWed, '只有周一/周三有分配，却生成了其它星期的安排');
  // 任务开始日 2025-06-01 晚于下限 2025-03-15，A 段应停在任务开始日之前一天
  assert(r.back1 === '2025-05-31', `A段游标停在 ${r.back1}，应为任务开始日的前一天 2025-05-31`);
  assert(back2 === r.back1, `休息日导致游标继续变化：${r.back1} → ${back2}`);
  assert(r.maxWeek < 150, `某一周 addDays 调用 ${r.maxWeek} 次，说明 A 段被重扫。逐周=${JSON.stringify(r.perWeek)}`);
  assert(r.dups === 0, '出现重复安排');
  return `周一/周三任务：A段游标推进到 ${r.back1}（任务开始日前一天）后不再重复扫描，逐周 addDays=${JSON.stringify(r.perWeek)}，${r.count} 条全在周一/周三`;
});

await test('T26', 'A段已到下限后，5 类记录与 entry id 完全不变', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段保护', repeat: 'daily', startDate: '2025-01-01', endDate: '' });
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2025-09-20');                 // 让 A 段回填到下限（覆盖 2025-09 的日期）
    const dates = ['2025-09-01','2025-09-02','2025-09-03','2025-09-04','2025-09-05'];
    const missing = dates.filter(d => !__entryOn('task_A段保护', d));
    if (missing.length) return { err: '这些日期没被补出来: ' + missing.join(',') };
    const put = (date, status, extra) => {
      const e = (state.schedule[date] || []).find(x => x.taskId === 'task_A段保护');
      e.status = status; Object.assign(e, extra || {}); return e.id;
    };
    put('2025-09-01', 'completed', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 });
    put('2025-09-02', 'skipped');
    put('2025-09-03', 'incomplete', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:2,status:'done'}], pointer: 1 });
    put('2025-09-04', 'inProgress', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:5,status:'done'}], pointer: 1, setStarted: true });
    put('2025-09-05', 'pending', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:7,status:'done'}] });
    const snap = () => dates.map(d => {
      const e = __entryOn('task_A段保护', d);
      return e ? [e.id, e.status, e.stepIndexes.join(','), e.sets, e.pointer, e.setStarted].join('|') : 'NULL';
    });
    const before = snap();
    const cursorAtFloor = JSON.stringify(rollCursors['task_A段保护']);
    for (let i = 0; i < 15; i++) changeWeek(-1);   // A 段已到下限，反复往前翻历史周
    const afterNav = snap();
    resetRollCursors();                            // 任务定义变化时的机制
    jumpToDate('2025-09-05');
    ensureSchedule(state.weekViewEnd);
    const after = snap();
    return { before, after, afterNav, cursorAtFloor, dups: __dups().length };
  `);
  assert(!r.err, r.err || '');
  assert(JSON.stringify(r.before) === JSON.stringify(r.afterNav),
    '翻历史周改动了已有记录:\n前=' + JSON.stringify(r.before) + '\n后=' + JSON.stringify(r.afterNav));
  assert(JSON.stringify(r.before) === JSON.stringify(r.after),
    '重置游标并跳回后改动了已有记录:\n前=' + JSON.stringify(r.before) + '\n后=' + JSON.stringify(r.after));
  assert(r.dups === 0, '出现重复安排');
  return '完成/跳过/提前结束/进行中/带组数 5 条记录的 id、状态、步骤、组数、进度、setStarted 全部不变（翻周与重置游标两种路径）';
});

await test('T27', 'A段与B段都不会在任务结束日期之后生成待做安排', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: 'A段结束日', repeat: 'daily', startDate: '2025-06-01', endDate: '2025-12-31' });
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2026-02-01');                 // 视图在结束日之后
    for (let i = 0; i < 12; i++) changeWeek(-1);
    const gen = () => Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_A段结束日').map(e => d));
    const afterNav = gen();
    // 「今天及以后、结束日之后、还没被用户动过」的待做安排应被清理；
    // 已经过去的日期属于历史记录（即使晚于结束日）按规则保留；
    // 已完成的记录一律保留。
    const todayStr = getRealToday();
    const futureStale = addDays(todayStr, 40);          // 今天之后、且晚于结束日
    const pastAfterEnd = '2026-01-20';                  // 早于今天、晚于结束日 → 历史记录
    state.schedule[futureStale] = [makeEntry('task_A段结束日', [0])];
    state.schedule[pastAfterEnd] = [makeEntry('task_A段结束日', [0])];
    state.schedule[addDays(todayStr, 41)] = [Object.assign(makeEntry('task_A段结束日', [0]),
      { status: 'completed', sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 })];
    scheduleNeedsPrune = true;
    ensureSchedule(addDays(todayStr, 90));
    return { navCount: afterNav.length, navBeyond: afterNav.filter(d => d > '2025-12-31').length,
             futureStale, pastAfterEnd, todayStr,
             futureStaleGone: __entryOn('task_A段结束日', futureStale),
             pastKept: __entryOn('task_A段结束日', pastAfterEnd),
             keptDone: __entryOn('task_A段结束日', addDays(todayStr, 41)),
             taskEndDate: getTask('task_A段结束日').endDate, dups: __dups().length };
  `);
  assert(r.navBeyond === 0, `翻历史周时结束日之后生成了 ${r.navBeyond} 条`);
  assert(r.futureStaleGone === null,
    `今天(${r.todayStr})之后、结束日 ${r.taskEndDate} 之后、未动过的待做安排（${r.futureStale}）没被清掉`);
  assert(r.pastKept !== null,
    `过去的记录（${r.pastAfterEnd}，虽晚于结束日但早于今天）被误删`);
  assert(r.keptDone && r.keptDone.status === 'completed', '结束日之后的已完成记录被删除');
  assert(r.dups === 0, '出现重复安排');
  return `结束日 ${r.taskEndDate}：翻周未越界生成；今天之后越界待做（${r.futureStale}）被清、过去的记录（${r.pastAfterEnd}）与已完成记录保留`;
});

await test('T28', '游标为空 / 晚于区间 / 任务定义变化时的回填仍然正确', async () => {
  const r = await evaluate(`
    __reset();
    const t = __mk({ name: 'A段边界', repeat: 'daily', startDate: '2025-06-01', endDate: '' });
    ensureSchedule();
    const floor = addDays(getRealToday(), -365);
    // 任务开始日晚于下限，所以 A 段真正的停止线是任务开始日前一天
    const stopLine = addDays(t.startDate, -1);
    // 1) 游标为空：第一次回填应一路走到停止线
    delete rollCursors['task_A段边界'];
    jumpToDate('2025-10-01');
    const afterNullCursor = JSON.parse(JSON.stringify(rollCursors['task_A段边界']));
    // 2) 游标被人为设成晚于 backwardScanEnd（模拟结束日曾被改早）
    rollCursors['task_A段边界'].backward = addDays(getRealToday(), 30);
    changeWeek(-1);
    const afterFutureCursor = JSON.parse(JSON.stringify(rollCursors['task_A段边界']));
    // 3) 任务定义变化 → resetRollCursors 后应重新回填
    resetRollCursors();
    changeWeek(-1);
    const afterReset = JSON.parse(JSON.stringify(rollCursors['task_A段边界']));
    // 4) 把结束日改早（等价于用户在编辑页改短结束日并保存），
    //    今天之后、结束日之后、未动过的待做安排应被清掉，过去的记录保留
    const todayStr = getRealToday();
    t.endDate = '2025-09-30';
    const staleDate = addDays(todayStr, 50);
    state.schedule[staleDate] = [makeEntry('task_A段边界', [0])];
    resetRollCursors();
    scheduleNeedsPrune = true;                // savePlan 编辑分支就是这么做的
    jumpToDate('2026-01-15');
    changeWeek(-1);
    const entries = Object.keys(state.schedule).sort()
      .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === 'task_A段边界').map(e => d));
    return { floor, stopLine, afterNullCursor, afterFutureCursor, afterReset,
             staleDate, staleGone: __entryOn('task_A段边界', staleDate),
             beyondEndInPast: entries.filter(d => d > '2025-09-30' && d < todayStr).length,
             cursorEnd: JSON.parse(JSON.stringify(rollCursors['task_A段边界'])), dups: __dups().length };
  `);
  assert(r.afterNullCursor.backward === r.stopLine,
    `游标为空时 A 段停在 ${r.afterNullCursor.backward}，应为 ${r.stopLine}`);
  assert(r.afterFutureCursor.backward <= r.stopLine,
    `未来游标没有被收回：${JSON.stringify(r.afterFutureCursor)}`);
  assert(r.afterReset.backward <= r.stopLine,
    `resetRollCursors 后 A 段没有重新回填到停止线：${JSON.stringify(r.afterReset)}`);
  assert(r.staleGone === null,
    `结束日改早后，今天之后的越界待做安排（${r.staleDate}）没被清掉`);
  assert(r.dups === 0, '出现重复安排');
  return `空游标→${r.afterNullCursor.backward}；未来游标收回→${r.afterFutureCursor.backward}；reset后→${r.afterReset.backward}（停止线 ${r.stopLine}，下限 ${r.floor}）；结束日改早后今天之后的越界待做已清`;
});

// ================= 每周任务开始日边界（B 段不得补到 startDate 之前） =================

await test('T29', '每周任务开始日边界：翻到开始日前一天不生成该任务', async () => {
  const r = await evaluate(`
    __reset();
    // 完全按复现步骤：每周任务，开始日期 2026-10-13（周二），只在周二做
    const t = __mk({ name: '边界每周', repeat: 'weekly', startDate: '2026-10-13', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 2: [0] } });
    const tid = t.id;
    ensureSchedule(addDays(getRealToday(), 30));
    resetRollCursors();
    // 翻看 2026-10-12（周一，开始日的前一天）
    jumpToDate('2026-10-12');
    const afterView = {
      sim: state.simulatedDate,
      startDay: __entryOn(tid, '2026-10-13'),
      nextTuesday: __entryOn(tid, '2026-10-20'),
      tuesdayAfter: __entryOn(tid, '2026-10-27'),
      beforeStart: __entryOn(tid, '2026-10-12')
    };
    // 再停在这一周跑一次 ensureSchedule（B 段定点补齐也要能进来）
    state.weekViewEnd = getWeekEndDate(0);
    ensureSchedule(state.weekViewEnd);
    return {
      startDate: (getTask(tid) || {}).startDate, tid, afterView,
      beforeStart: __entryOn(tid, '2026-10-12'),
      daysBefore: Object.keys(state.schedule).sort()
        .flatMap(d => (state.schedule[d] || []).filter(e => e.taskId === tid && d < '2026-10-13').map(e => d)),
      startDay: __entryOn(tid, '2026-10-13'),
      nextTuesday: __entryOn(tid, '2026-10-20'),
      tuesdayAfter: __entryOn(tid, '2026-10-27'),
      dup: (state.schedule['2026-10-13'] || []).filter(e => e.taskId === tid).length,
      dups: __dups().length
    };
  `);
  assert(r.startDate === '2026-10-13', '开始日期=' + r.startDate + '；当前任务列表=' + JSON.stringify(r.taskIds));
  assert(r.afterView.beforeStart === null,
    '翻看 2026-10-12 时出现了该任务的安排（复现到的缺陷）');
  assert(r.beforeStart === null, '再次 ensureSchedule 后 2026-10-12 又出现了该任务的安排');
  assert(r.daysBefore.length === 0,
    `开始日之前共生成了 ${r.daysBefore.length} 条：${JSON.stringify(r.daysBefore.slice(0, 8))}`);
  assert(r.afterView.startDay !== null, '开始日 2026-10-13（周二）没有生成');
  assert(r.startDay !== null, '再次 ensureSchedule 后开始日 2026-10-13 仍在');
  assert(r.afterView.startDay.stepIndexes.join(',') === '0',
    '开始日步骤分配=' + JSON.stringify(r.afterView.startDay.stepIndexes) + '（weeklyPattern 周二=[0]）');
  assert(r.nextTuesday !== null && r.tuesdayAfter !== null,
    '后续周二没有正常生成：10-20=' + JSON.stringify(r.nextTuesday) + ' 10-27=' + JSON.stringify(r.tuesdayAfter));
  assert(r.dup <= 1, '开始日出现重复安排 ' + r.dup + ' 条');
  assert(r.dups === 0, '出现重复安排');
  return `开始日 ${r.startDate}：10-12 无安排（早于开始日 0 条），10-13[0]/10-20[0]/10-27[0] 按 weeklyPattern 正常生成且不重复`;
});

await test('T30', '每周任务开始日边界：开始日之前的整周都不受 B 段影响', async () => {
  const r = await evaluate(`
    __reset();
    const t = __mk({ name: '边界周一', repeat: 'weekly', startDate: '2026-10-13', endDate: '',
           steps: [{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1}],
           weeklyPattern: { 1: [0], 2: [0,1], 5: [1] } });   // 周一/周二/周五
    const tid = t.id;
    ensureSchedule(addDays(getRealToday(), 30));
    resetRollCursors();
    // 停在开始日所在的那一周（2026-10-12 周一 ~ 10-18 周日）
    jumpToDate('2026-10-18');
    state.weekViewEnd = getWeekDays(0)[6];
    ensureSchedule(state.weekViewEnd);
    const week = getWeekDays(0);
    const perDay = {};
    week.forEach(d => { perDay[d] = (__entryOn(tid, d) || { stepIndexes: null }).stepIndexes; });
    // 逐日核对：早于开始日的一律为空，其余按 weeklyPattern
    const want = { 1: [0], 2: [0,1], 5: [1] };
    const violations = week.filter(d => {
      const got = perDay[d];
      if (d < '2026-10-13') return got !== null;
      return JSON.stringify(got) !== JSON.stringify(want[isoDay(d)] || null);
    });
    return { week: week.join(','), perDay, violations, startDate: getTask(tid).startDate, tid,
             patternErrs: __patternCheck(tid), dups: __dups().length };
  `);
  assert(r.violations.length === 0,
    `以下日期不符合规则：${JSON.stringify(r.violations)}；本周=${r.week}；实际=${JSON.stringify(r.perDay)}`);
  assert(r.patternErrs.length === 0, 'weeklyPattern 与安排不一致: ' + JSON.stringify(r.patternErrs));
  assert(r.dups === 0, '出现重复安排');
  return `本周 ${r.week}：10-12 为空，10-13[0,1]（周二）、10-16[1]（周五）按 weeklyPattern 生成，全部一致`;
});

await test('T31', '每周任务开始日边界：开始日之后的历史周仍能补齐缺失安排', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '边界补齐', repeat: 'weekly', startDate: '2025-06-01', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 1: [0] } });   // 只在周一
    ensureSchedule();
    resetRollCursors();
    // 先让回填游标走到下限
    jumpToDate('2025-08-01');
    const cursorAtFloor = JSON.parse(JSON.stringify(rollCursors['task_边界补齐']));
    // 人为挖掉开始日之后的一整周（2025-09-01 周一 ~ 2025-09-07 周日）
    const week = ['2025-09-01','2025-09-02','2025-09-03','2025-09-04','2025-09-05','2025-09-06','2025-09-07'];
    week.forEach(d => { delete state.schedule[d]; });
    const before = week.filter(d => __entryOn('task_边界补齐', d)).length;
    // 翻到那一周（B 段定点补齐）
    jumpToDate('2025-09-07');
    state.weekViewEnd = getWeekDays(0)[6];
    ensureSchedule(state.weekViewEnd);
    const after = week.filter(d => __entryOn('task_边界补齐', d)).length;
    const mon = __entryOn('task_边界补齐', '2025-09-01');
    // 同一周再放一个「开始日在未来」的任务：开始日 2026-11-10（周二）之前都不该有安排
    __mk({ name: '边界未来', repeat: 'weekly', startDate: '2026-11-10', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 1: [0], 2: [0] } });
    const futureWeek = ['2026-11-09','2026-11-10','2026-11-11','2026-11-16'];
    futureWeek.forEach(d => { delete state.schedule[d]; });   // 全部删掉，看谁能被补回来
    jumpToDate('2026-11-09');                                  // 视图落在开始日之前
    state.weekViewEnd = getWeekDays(0)[6];
    ensureSchedule(state.weekViewEnd);
    jumpToDate('2026-11-16');                                  // 视图落在开始日之后
    state.weekViewEnd = getWeekDays(0)[6];
    ensureSchedule(state.weekViewEnd);
    return { cursorAtFloor, before, after, mon, week: week.join(','),
             futureBeforeStart: __entryOn('task_边界未来', '2026-11-09'),
             futureOnStart: __entryOn('task_边界未来', '2026-11-10'),
             futureNextMon: __entryOn('task_边界未来', '2026-11-16'),
             dups: __dups().length };
  `);
  assert(r.before === 0, '测试前提不成立：被挖掉的周本来就有安排');
  assert(r.after === 1 && r.mon !== null,
    `B 段没有补齐开始日之后的历史周：该周补了 ${r.after} 条（应为 1 条周一）`);
  assert(r.futureBeforeStart === null,
    `开始日在未来（2026-11-10）的任务在 2026-11-09 被生成了安排（复现到的缺陷）`);
  assert(r.futureOnStart !== null, '开始日 2026-11-10（周二）没有被生成');
  assert(r.futureNextMon !== null, '开始日之后的周一 2026-11-16 没有被生成');
  assert(r.dups === 0, '出现重复安排');
  return `开始日之后的历史周：挖掉 ${r.week} 后 B 段补回周一（${r.after} 条）；未来开始日的任务 11-09 为空，11-10、11-16 正常生成`;
});

await test('T32', '每周任务开始日边界：不覆盖已完成/进行中等记录', async () => {
  const r = await evaluate(`
    __reset();
    __mk({ name: '边界保护', repeat: 'weekly', startDate: '2025-06-01', endDate: '',
           steps: [{name:'A',sets:1,reps:1}], weeklyPattern: { 1: [0] } });
    ensureSchedule();
    resetRollCursors();
    jumpToDate('2025-08-01');
    // 2025-09-01（周一）设成已完成并留下组数记录；2025-09-08（周一）设成进行中
    const d1 = '2025-09-01', d2 = '2025-09-08';
    const put = (date, status, extra) => {
      const e = (state.schedule[date] || []).find(x => x.taskId === 'task_边界保护');
      if (!e) throw new Error(date + ' 没有安排');
      e.status = status; Object.assign(e, extra || {}); return e.id;
    };
    put(d1, 'completed', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}], pointer: 1 });
    put(d2, 'inProgress', { sets: [{stepIndex:0,setIndex:0,targetReps:10,actualReps:4,status:'done'}], pointer: 1, setStarted: true });
    const snap = (date) => {
      const e = __entryOn('task_边界保护', date);
      return e ? [e.id, e.status, e.stepIndexes.join(','), e.sets, e.pointer, e.setStarted].join('|') : 'NULL';
    };
    const before = [snap(d1), snap(d2)];
    // 反复翻附近的历史周 + 重置游标重算
    jumpToDate('2025-09-07');
    for (let i = 0; i < 6; i++) changeWeek(-1);
    resetRollCursors();
    jumpToDate('2025-09-07');
    changeWeek(1); changeWeek(-1);
    const after = [snap(d1), snap(d2)];
    return { before, after, dups: __dups().length };
  `);
  assert(JSON.stringify(r.before) === JSON.stringify(r.after),
    '记录被改动:\n前=' + JSON.stringify(r.before) + '\n后=' + JSON.stringify(r.after));
  assert(r.dups === 0, '出现重复安排');
  return '已完成（含组数记录）与进行中两条安排的 id、状态、步骤、组数、进度、setStarted 全部不变';
});

await test('T11', '全程无控制台错误', async () => {
  const errs = await evaluate(`return JSON.stringify(window.__errors.slice(0, 5));`);
  const pageErrs = events.filter(e => e.method === 'Log.entryAdded' && e.params.entry.level === 'error')
    .map(e => e.params.entry.text);
  assert(JSON.parse(errs).length === 0, '页面错误: ' + errs);
  assert(pageErrs.length === 0, '浏览器日志错误: ' + pageErrs.join(' | '));
  return '无错误';
});

// ================= 汇总 =================
const pass = results.filter(r => r.status === 'PASS').length;
const fail = results.length - pass;
if (process.env.ONLY_BACKFILL) {
  const keep = results.filter(x => ['T19', 'T19b', 'T20', 'T21'].includes(x.id));
  writeFileSync('E:\\dsh\\duanlian\\_test\\backfill-only.json', JSON.stringify(keep, null, 2), 'utf8');
}
console.log('\n==================== 汇总 ====================');
console.log(`通过 ${pass} / ${results.length}，失败 ${fail}`);
results.filter(r => r.status === 'FAIL').forEach(r => console.log(`  FAIL ${r.id}: ${r.name}\n    ${r.detail}`));
writeFileSync('E:\\dsh\\duanlian\\_test\\results.json', JSON.stringify({ pass, fail, results }, null, 2), 'utf8');

ws.close(); child.kill(); server.close();
process.exit(fail > 0 ? 1 : 0);
