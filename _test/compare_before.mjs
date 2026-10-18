// 对照实验：把旧版实现注入页面，验证测试确实能捕捉到原缺陷
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const APP_FILE = 'E:\\dsh\\duanlian\\jihua2.html';
const PROFILE = 'E:\\dsh\\duanlian\\_test\\profile4';
const PORT = 9477, HTTP_PORT = 8126;
mkdirSync(PROFILE, { recursive: true });

const server = createServer((q, s) => {
  s.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  s.end(readFileSync(APP_FILE));
});
await new Promise(r => server.listen(HTTP_PORT, '127.0.0.1', r));
const URL = `http://127.0.0.1:${HTTP_PORT}/jihua2.html`;

const child = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--disable-crash-reporter', '--disable-breakpad', 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));
let bws = null;
for (let i = 0; i < 80; i++) { try { const j = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); if (j.webSocketDebuggerUrl) { bws = j.webSocketDebuggerUrl; break; } } catch {} await sleep(250); }
const ws = new WebSocket(bws);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0; const pending = new Map();
ws.onmessage = ev => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); } };
const send = (method, params = {}, sessionId) => { const i = ++id; return new Promise((resolve, reject) => { pending.set(i, { resolve, reject }); ws.send(JSON.stringify(sessionId ? { id: i, method, params, sessionId } : { id: i, method, params })); }); };
const { targetInfos } = await send('Target.getTargets');
const { sessionId } = await send('Target.attachToTarget', { targetId: targetInfos.find(t => t.type === 'page').targetId, flatten: true });
await send('Runtime.enable', {}, sessionId);
await send('Page.enable', {}, sessionId);
await send('Page.addScriptToEvaluateOnNewDocument', { source: `window.__fake={on:true,base:new Date(2026,2,15,10,0,0),off:0};const R=Date;window.Date=new Proxy(R,{construct(t,a,n){return window.__fake.on?Reflect.construct(R,a.length?a:[window.__fake.base.getTime()+(window.__fake.off||0)],n):Reflect.construct(t,a,n)},apply(t,th,a){return window.__fake.on&&a.length===0?new R(window.__fake.base.getTime()).toString():Reflect.apply(t,th,a)},get(t,p){if(p==='now')return()=>window.__fake.on?window.__fake.base.getTime()+(window.__fake.off||0):R.now();const v=t[p];return typeof v==='function'?v.bind(t):v}});` }, sessionId);

async function ev(expr, label) {
  process.stdout.write('  · ' + label + ' ... ');
  const r = await send('Runtime.evaluate', {
    expression: `(() => { ${expr} })()`, returnByValue: true, awaitPromise: true, timeout: 20000
  }, sessionId);
  console.log(r.exceptionDetails ? '异常' : '完成');
  if (r.exceptionDetails) return { ERR: r.exceptionDetails.exception?.description || r.exceptionDetails.text };
  return r.result.value;
}
await send('Page.navigate', { url: URL }, sessionId); await sleep(600);
await ev(`try{localStorage.clear();}catch(e){} return 1;`, '清理存储');
await send('Page.navigate', { url: URL }, sessionId); await sleep(800);

// ---- 旧版 ensureSchedule（以 state.simulatedDate 为锚点生成未来 365 天）----
const OLD_ENSURE = `
window.__oldEnsureSchedule = function () {
  const viewDate = state.simulatedDate;
  const horizonEnd = addDays(viewDate, ROLL_HORIZON_DAYS);
  state.tasks.forEach(task => {
    if (!task.repeat || task.repeat === 'once') return;
    if (!task.startDate) return;
    const fromDate = task.startDate > viewDate ? task.startDate : viewDate;
    const toDate = (task.endDate && task.endDate < horizonEnd) ? task.endDate : horizonEnd;
    if (toDate < fromDate) return;
    const entries = generateEntriesForTask(task, fromDate, toDate);
    entries.forEach(e => {
      if (!state.schedule[e.date]) state.schedule[e.date] = [];
      const exists = state.schedule[e.date].some(x => x.taskId === task.id);
      if (!exists) {
        state.schedule[e.date].push({ id: uid('entry'), taskId: task.id, stepIndexes: [...e.stepIndexes],
          status: 'pending', session: null, sets: [], pointer: 0, setStarted: false });
      }
    });
  });
  saveState();
};
return 1;`;

// ---- 旧版 savePlan 编辑分支：把新步骤塞进该任务所有已有安排 ----
const OLD_EDIT = `
window.__oldEditBehavior = function (taskId, newStepCount, oldStepCount) {
  Object.keys(state.schedule).forEach(date => {
    state.schedule[date].forEach(entry => {
      if (entry.taskId !== taskId) return;
      entry.stepIndexes = entry.stepIndexes.filter(i => i < newStepCount);
      if (newStepCount > oldStepCount) {
        for (let i = oldStepCount; i < newStepCount; i++) {
          if (!entry.stepIndexes.includes(i)) entry.stepIndexes.push(i);
        }
      }
      if (entry.stepIndexes.length === 0) entry.stepIndexes = state.tasks.find(t => t.id === taskId).steps.map((_, i) => i);
      entry.stepIndexes.sort((a, b) => a - b);
    });
  });
};
return 1;`;

await ev(OLD_ENSURE, '注入旧版 ensureSchedule');
await ev(OLD_EDIT, '注入旧版编辑同步逻辑');

console.log('\n=== 旧实现（修复前）在相同场景下的表现 ===');

console.log('\n[旧-1] 每天重复任务，周视图连续切到约 430 天后（旧 changeWeek 只改 weekOffset）：');
console.log(JSON.stringify(await ev(`
  state.tasks = []; state.schedule = {}; state.history = []; resetRollCursors();
  state.simulatedDate = '2026-03-15'; state.weekOffset = 0;
  state.tasks.push({ id:'old1', name:'旧每日', orderType:'rotate', steps:[{name:'A',sets:3,reps:10}],
    restEnabled:false, restSeconds:60, repeat:'daily', startDate:'2026-03-15', endDate:'', weeklyPattern:null });
  __oldEnsureSchedule();
  for (let i = 0; i < 61; i++) { state.weekOffset += 1; __oldEnsureSchedule(); }
  const far = getWeekDays(state.weekOffset);
  const covered = far.filter(d => (state.schedule[d]||[]).some(x => x.taskId === 'old1')).length;
  return { 远期周: far[0] + '~' + far[6], 有安排天数: covered + '/7',
           日程最后日期: Object.keys(state.schedule).sort().pop(),
           总天数: Object.keys(state.schedule).length };
`, '旧-1'), null, 1));

console.log('\n[旧-2] 编辑任务加步骤后，各天/各星期的步骤分配：');
console.log(JSON.stringify(await ev(`
  state.tasks = []; state.schedule = {}; resetRollCursors();
  state.simulatedDate = '2026-03-15'; state.weekOffset = 0;
  const w = { id:'oldW', name:'旧每周', orderType:'rotate',
    steps:[{name:'A',sets:1,reps:1},{name:'B',sets:1,reps:1},{name:'C',sets:1,reps:1}],
    restEnabled:false, restSeconds:60, repeat:'weekly', startDate:'2026-03-15', endDate:'',
    weeklyPattern:{1:[0],3:[1,2],5:[0,2]} };
  state.tasks.push(w);
  __oldEnsureSchedule();
  const monBefore = ((state.schedule['2026-03-16']||[]).find(x=>x.taskId==='oldW')||{}).stepIndexes;
  const wedBefore = ((state.schedule['2026-03-18']||[]).find(x=>x.taskId==='oldW')||{}).stepIndexes;
  w.steps.push({ name:'D', sets:1, reps:1 });
  __oldEditBehavior('oldW', 4, 3);
  const pickW = (d) => ((state.schedule[d]||[]).find(x => x.taskId === 'oldW') || {}).stepIndexes;
  const weekly = { 编辑前周一: monBefore, 编辑前周三: wedBefore,
                   编辑后周一_按规则应为0_3: pickW('2026-03-16'),
                   编辑后周三_按规则应为1_2_3: pickW('2026-03-18'),
                   编辑后周五_按规则应为0_2_3: pickW('2026-03-20') };

  const d = { id:'oldD', name:'旧每日', orderType:'rotate',
    steps:[{name:'A',sets:1,reps:1}], restEnabled:false, restSeconds:60,
    repeat:'daily', startDate:'2026-03-16', endDate:'', weeklyPattern:null };
  state.tasks.push(d);
  state.simulatedDate = '2026-03-16';
  __oldEnsureSchedule();
  const dBefore = ((state.schedule['2026-03-17']||[]).find(x=>x.taskId==='oldD')||{}).stepIndexes;
  d.steps.push({ name:'E', sets:1, reps:1 });
  __oldEditBehavior('oldD', 2, 1);
  // 编辑后系统又生成了新的日期（比如往前翻动时滚动生成）
  state.schedule['2026-05-20'] = [];    // 模拟一个尚未生成的日期
  __oldEnsureSchedule();
  const daily = { 编辑前未来某天: dBefore,
                  编辑后已有的未来天_应为0_1: ((state.schedule['2026-03-17']||[]).find(x=>x.taskId==='oldD')||{}).stepIndexes,
                  编辑后新滚出的天_应为0_1: ((state.schedule['2026-05-20']||[]).find(x=>x.taskId==='oldD')||{}).stepIndexes };
  return { weekly, daily };
`, '旧-2'), null, 1));

console.log('\n[旧-3] 循环生成是否会覆盖已有完成记录：');
console.log(JSON.stringify(await ev(`
  state.tasks = []; state.schedule = {}; resetRollCursors();
  state.simulatedDate = '2026-03-15';
  state.tasks.push({ id:'old3', name:'旧状态', orderType:'rotate', steps:[{name:'A',sets:3,reps:10}],
    restEnabled:false, restSeconds:60, repeat:'daily', startDate:'2026-03-15', endDate:'', weeklyPattern:null });
  __oldEnsureSchedule();
  const e = (state.schedule['2026-04-01']||[]).find(x => x.taskId === 'old3');
  e.status = 'completed'; e.sets = [{stepIndex:0,setIndex:0,targetReps:10,actualReps:10,status:'done'}]; e.pointer = 1;
  const before = e.id + '|' + e.status + '|' + e.sets.length;
  __oldEnsureSchedule();
  const a = (state.schedule['2026-04-01']||[]).find(x => x.taskId === 'old3');
  return { 前: before, 后: a.id + '|' + a.status + '|' + (a.sets||[]).length,
           结论: before === (a.id + '|' + a.status + '|' + (a.sets||[]).length) ? '旧版也不会覆盖（同天同任务已存在就跳过）' : '被覆盖' };
`, '旧-3'), null, 1));

console.log('\n[旧-4] 旧版 realToday 是常量，点「今天」不会重新取系统日期：');
console.log(JSON.stringify(await ev(`
  window.__fake.off = 0;
  const fakeToday = getRealToday();
  const stale = realToday;        // 旧版这是 const，页面打开时算一次就固定
  window.__fake.off = 2 * 86400000;   // 页面跨过午夜，系统日期前进两天
  const sys = getRealToday();
  state.simulatedDate = stale;    // 旧版 jumpToToday() 就是这么写的
  return { 页面里的常量: stale, 系统当前日期: sys, 点今天后得到: state.simulatedDate,
           是否会跳到昨天: state.simulatedDate !== sys };
`, '旧-4'), null, 1));

ws.close(); child.kill(); server.close(); process.exit(0);
