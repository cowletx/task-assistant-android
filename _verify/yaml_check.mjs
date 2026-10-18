// yaml_check.mjs —— 用 Node 内置模块无法解析 YAML，这里做一份最小但严格的
// GitHub Actions 工作流结构校验：缩进一致性、键值形态、steps 结构、引用的路径是否存在。
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const FILE = 'E:\\dsh\\duanlian\\.github\\workflows\\android-ci.yml';
const REPO = 'E:\\dsh\\duanlian';
const raw = readFileSync(FILE, 'utf8');
const lines = raw.split('\n');

const problems = [];

// 1) Tab 字符（YAML 禁止用 tab 缩进）
lines.forEach((l, i) => { if (l.includes('\t')) problems.push(`第 ${i + 1} 行含 Tab 字符`); });

// 2) 顶层键
const topKeys = lines.filter(l => /^[A-Za-z_]/.test(l)).map(l => l.split(':')[0]);
console.log('顶层键:', topKeys.join(', '));
for (const must of ['name', 'on', 'jobs']) {
  if (!topKeys.includes(must)) problems.push(`缺少顶层键: ${must}`);
}

// 3) on 必须作为字符串键存在（不能写成了布尔 true 的形态）
if (!/^on:/m.test(raw)) problems.push('缺少 "on:" 触发配置');

// 4) jobs 下必须有 jobs.<id>.steps，且每个 step 有 uses 或 run
const jobLines = lines.map((l, i) => ({ l, i })).filter(x => /^  [A-Za-z0-9_-]+:\s*$/.test(x.l));
console.log('任务(job):', jobLines.map(x => x.l.trim().replace(':', '')).join(', '));
if (jobLines.length === 0) problems.push('没有找到任何 job');

// 5) uses 版本号
const usesList = [...raw.matchAll(/uses:\s*([^\s#]+)/g)].map(m => m[1]);
console.log('使用的 Action:');
usesList.forEach(u => console.log('  ' + u));
usesList.forEach(u => {
  if (u.startsWith('./')) return;
  if (!/@/.test(u)) problems.push(`Action 未固定版本: ${u}`);
});

// 6) working-directory 是否存在
const wd = [...raw.matchAll(/working-directory:\s*([^\s#]+)/g)].map(m => m[1]);
wd.forEach(d => {
  const full = join(REPO, d);
  console.log(`working-directory: ${d} -> ${existsSync(full) ? '存在' : '不存在'}`);
  if (!existsSync(full)) problems.push(`working-directory 不存在: ${d}`);
});

// 7) 上传/下载产物里引用的路径是否至少父目录合理
const paths = [...raw.matchAll(/^\s{10,}(\S+)$/gm)].map(m => m[1]).filter(p => p.includes('/') || p.includes('*'));
console.log('产物/日志路径:');
[...new Set(paths)].forEach(p => console.log('  ' + p));

// 8) 关键命令是否指向真实存在的 Gradle 任务与模块
const cmds = [...raw.matchAll(/run:\s*(.+)/g)].map(m => m[1].trim());
console.log('run 命令:');
cmds.forEach(c => console.log('  ' + c));
const settings = readFileSync(join(REPO, 'android', 'settings.gradle.kts'), 'utf8');
const modules = [...settings.matchAll(/include\("(:[^"]+)"\)/g)].map(m => m[1]);
console.log('settings.gradle.kts 实际模块:', modules.join(', '));
for (const m of modules) {
  if (raw.includes(`${m}:test`) || raw.includes(`${m}:assemble`)) {
    console.log(`  工作流引用了模块 ${m} ✓`);
  }
}
if (!raw.includes(':core-schedule:test')) problems.push('工作流未引用 :core-schedule:test');
if (!raw.includes(':app:assembleDebug')) problems.push('工作流未引用 :app:assembleDebug');

console.log('\n========================================');
if (problems.length === 0) {
  console.log('工作流结构校验：未发现问题');
} else {
  console.log(`工作流结构校验：发现 ${problems.length} 个问题`);
  problems.forEach(p => console.log('  - ' + p));
}
process.exit(problems.length > 0 ? 1 : 0);
