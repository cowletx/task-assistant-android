// kotlin_api_risk_check.mjs
// ---------------------------------------------------------------------------
// 本机没有编译器，无法验证 Kotlin/Compose/Room API 用法。这个脚本做一次
// 「已知高风险写法」扫描：这些模式在实践中最容易编译失败或运行期出错。
// 每条规则都标注了原因，命中即人工复核（不是自动判错）。
// ---------------------------------------------------------------------------
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = 'E:\\dsh\\duanlian\\android';
const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'build' || name === '.gradle') continue;
      walk(full);
    } else if (name.endsWith('.kt')) files.push(full);
  }
})(ROOT);

const RULES = [
  {
    id: 'room-transaction-suspend',
    desc: 'Room @Transaction 接口方法若调用方是协程，需能挂起；确认方法都标了 suspend',
    test: (line, ctx) => /@Transaction/.test(line) && !/suspend/.test(ctx.next),
  },
  {
    id: 'room-insert-return',
    desc: '@Insert(IGNORE) 返回 List<Long>：必须用 -1 判断是否插入成功，不能当成行数',
    test: (line) => /insertMissing\(/.test(line) && /\.size/.test(line),
  },
  {
    id: 'compose-weight-scope',
    desc: 'Modifier.weight 只在 RowScope/ColumnScope 可用',
    test: (line, ctx) => /Modifier\.weight\(/.test(line) && !/Row|Column/.test(ctx.prevLines.slice(-12).join('\n')),
  },
  {
    id: 'deprecated-divider',
    desc: 'material3 1.3 起 Divider 已弃用（可用但告警）；确认没有用已移除的重载',
    test: (line) => /\bDivider\(/.test(line) && !/HorizontalDivider|VerticalDivider/.test(line),
  },
  {
    id: 'scroll-column-weight',
    desc: 'verticalScroll 的 Column 内部再用 weight 会崩溃/无效',
    test: (line, ctx) => /Modifier\.weight\(/.test(line) && /verticalScroll/.test(ctx.prevLines.slice(-40).join('\n')),
  },
  {
    id: 'room-flow-suspend',
    desc: '返回 Flow 的 DAO 方法不应是 suspend（Room 会报错）',
    test: (line, ctx) => /suspend fun .*\(.*\): Flow</.test(ctx.next),
  },
  {
    id: 'context-in-viewmodel',
    desc: 'ViewModel 里不应直接持有 Context/Activity 类型',
    test: (line, ctx) => /class MainViewModel/.test(line) && /Context|Activity/.test(ctx.text),
  },
  {
    id: 'remember-missing',
    desc: 'Composable 里用 var x by mutableStateOf 必须配 remember',
    test: (line) => /var\s+\w+\s+by\s+mutableStateOf/.test(line) && !/remember/.test(line),
  },
  {
    id: 'launched-loop-no-delay',
    desc: 'LaunchedEffect 里的 while(true) 必须含 delay，否则卡死主线程',
    test: (line, ctx) => /while\s*\(\s*true\s*\)/.test(line) && !/delay\(/.test(ctx.nextLines.slice(0, 6).join('\n')),
  },
  {
    id: 'experimental-api',
    desc: '使用需要 OptIn 的实验性 API（如 experimental foundation/material3）',
    test: (line) => /TopAppBar|ModalBottomSheet|PullToRefresh|FlowRow/.test(line) && !/@OptIn|@ExperimentalMaterial3Api/.test(line),
  },
  {
    id: 'string-interp-in-stringres',
    desc: 'strings.xml 里不应有未转义的 % 或裸 &',
    fileOnly: /strings\.xml$/,
    test: (line) => /%[^1-9$s]/.test(line),
  },
];

let hits = 0;
for (const file of files) {
  const lines = readFileSync(file, 'utf8').split('\n');
  for (const rule of RULES) {
    if (rule.fileOnly && !rule.fileOnly.test(file)) continue;
    lines.forEach((line, i) => {
      const trimmed = line.trim();
      if (trimmed.startsWith('//') || trimmed.startsWith('*')) return;
      const ctx = {
        next: lines[i + 1] ?? '',
        prevLines: lines.slice(Math.max(0, i - 12), i),
        nextLines: lines.slice(i, i + 7),
        text: lines.join('\n'),
      };
      if (rule.test(line, ctx)) {
        hits++;
        console.log(`[${rule.id}] ${relative(ROOT, file)}:${i + 1}`);
        console.log(`   ${rule.desc}`);
        console.log(`   ${trimmed.slice(0, 120)}`);
      }
    });
  }
}

console.log('\n========================================');
console.log(`扫描 ${files.length} 个文件，命中 ${hits} 处需人工复核的写法`);
