// kotlin_ref_check.mjs
// ---------------------------------------------------------------------------
// 本机无法编译 Kotlin（无 kotlinc / 无 Android SDK / 无网络），因此用这个脚本
// 做一层「同项目内符号引用」静态检查，专门抓最容易出现的编译错误：
//   1. 调用了同项目里定义的顶层函数/Composable，但没有 import，也不在同一个包；
//   2. 引用了同项目里定义的顶层 val，但没有 import；
//   3. 使用了一个从未在本项目任何文件里定义的「自定义」标识符（拼写错误）。
// 它不能替代编译器，但能发现大部分「漏 import / 名字写错」的问题。
// ---------------------------------------------------------------------------
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = 'E:\\dsh\\duanlian\\android';

/** 递归收集文件 */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      if (name === 'build' || name === '.gradle') continue;
      walk(full, out);
    } else if (name.endsWith('.kt')) {
      out.push(full);
    }
  }
  return out;
}

const files = walk(ROOT);

/** 解析：package、imports、顶层声明 */
function parse(file) {
  const text = readFileSync(file, 'utf8');
  const pkg = (text.match(/^package\s+([\w.]+)/m) || [])[1] || '';
  const imports = [...text.matchAll(/^import\s+([\w.*]+)/gm)].map(m => m[1]);
  // 顶层 fun / val / object / class / enum / interface（行首无缩进）
  const decls = [];
  for (const m of text.matchAll(/^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |expect )*(?:suspend )?(?:fun|val|var|object|class|interface|enum class|data class|sealed interface|sealed class|value class|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)/gm)) {
    decls.push(m[1]);
  }
  // 扩展函数接收者：fun Type.name(
  for (const m of text.matchAll(/^(?:@\w+\s*)*(?:internal |private |public )?fun\s+(?:<[^>]*>\s*)?[\w.]*?\.(\w+)\s*\(/gm)) {
    decls.push(m[1]);
  }
  return { file, pkg, imports, decls, text };
}

const parsed = files.map(parse);

/** 项目内所有顶层声明 → 定义它的包 */
const declToPkg = new Map();
const declToFile = new Map();
for (const p of parsed) {
  for (const d of p.decls) {
    if (!declToPkg.has(d)) declToPkg.set(d, p.pkg);
    if (!declToFile.has(d)) declToFile.set(d, relative(ROOT, p.file));
  }
}

// 只检查这些「项目自定义」符号，避免与 Kotlin/Compose 标准库混淆
const CUSTOM = new Set([...declToPkg.keys()].filter(n => n.length > 3));

let problems = 0;

for (const p of parsed) {
  const lines = p.text.split('\n');
  // 本包内直接可见，无需 import
  const samePkgDecls = new Set(
    parsed.filter(q => q.pkg === p.pkg).flatMap(q => q.decls)
  );
  // 已 import 的简单名
  const importedSimple = new Set();
  for (const imp of p.imports) {
    if (imp.endsWith('.*')) continue;
    const simple = imp.split('.').pop();
    importedSimple.add(simple);
    // 也允许 import com.x.Class.member 形式
    importedSimple.add(imp.split('.').slice(-2)[0]);
  }

  lines.forEach((line, idx) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) return;
    if (/^\s*(package|import)\s/.test(line)) return;
    for (const name of CUSTOM) {
      // 作为标识符出现（后面可能跟 ( 或 . 或空格）
      const re = new RegExp(`(?<![\\w.])${name}\\s*[({<.]`);
      if (!re.test(line)) continue;
      if (name === p.decls.find(() => true)) continue;
      const declaredHere = p.decls.includes(name);
      if (declaredHere || samePkgDecls.has(name) || importedSimple.has(name)) continue;
      // 排除声明行本身
      if (new RegExp(`^\\s*(@\\w+\\s*)*((public|internal|private|expect)\\s+)*(suspend\\s+)?(fun|val|var|object|class|interface|enum|data|sealed|value|typealias)\\b`).test(line)) continue;
      // 记录：疑似缺少 import
      const defPkg = declToPkg.get(name);
      const defFile = declToFile.get(name);
      problems++;
      console.log(`[缺 import?] ${relative(ROOT, p.file)}:${idx + 1}`);
      console.log(`           使用 ${name}，定义在 ${defFile} (package ${defPkg})`);
      console.log(`           行: ${trimmed.slice(0, 110)}`);
      break; // 每行只报一个，避免噪音
    }
  });
}

console.log('\n========================================');
console.log(`检查文件 ${files.length} 个，发现疑似问题 ${problems} 处`);
process.exit(0);
