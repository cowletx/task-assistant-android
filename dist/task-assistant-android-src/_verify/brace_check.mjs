// brace_check.mjs —— 逐字符扫描 Kotlin 源文件，跳过注释、字符串、字符字面量与插值表达式，统计括号平衡。
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

let bad = 0;
for (const file of files) {
  const src = readFileSync(file, 'utf8');
  let i = 0;
  let curly = 0, paren = 0, square = 0;
  let inLine = false, inBlock = false, inStr = false, inChar = false, inRaw = false;
  let rawQuotes = 0;
  const problems = [];

  while (i < src.length) {
    const c = src[i];
    const next = src[i + 1];

    if (inLine) { if (c === '\n') inLine = false; i++; continue; }
    if (inBlock) { if (c === '*' && next === '/') { inBlock = false; i += 2; continue; } i++; continue; }
    if (inStr) {
      if (c === '\\') { i += 2; continue; }
      if (c === '$' && next === '{') {
        // 模板插值：内部的括号也要计入
        let depth = 1; i += 2;
        while (i < src.length && depth > 0) {
          if (src[i] === '{') depth++;
          else if (src[i] === '}') depth--;
          i++;
        }
        continue;
      }
      if (c === '"') { inStr = false; }
      i++; continue;
    }
    if (inChar) { if (c === '\\') { i += 2; continue; } if (c === "'") inChar = false; i++; continue; }
    if (inRaw) {
      if (c === '"' && next === '"' && src[i + 2] === '"') { inRaw = false; i += 3; continue; }
      i++; continue;
    }

    if (c === '/' && next === '/') { inLine = true; i += 2; continue; }
    if (c === '/' && next === '*') { inBlock = true; i += 2; continue; }
    if (c === '"' && next === '"' && src[i + 2] === '"') { inRaw = true; i += 3; continue; }
    if (c === '"') { inStr = true; i++; continue; }
    if (c === "'") {
      // 判断是字符字面量还是别的（Kotlin 里 'x' 或 '\n'）
      inChar = true; i++; continue;
    }

    if (c === '{') curly++;
    else if (c === '}') { curly--; if (curly < 0) problems.push(`多余 } @${i}`); }
    else if (c === '(') paren++;
    else if (c === ')') { paren--; if (paren < 0) problems.push(`多余 ) @${i}`); }
    else if (c === '[') square++;
    else if (c === ']') square--;
    i++;
  }

  if (curly !== 0 || paren !== 0 || square !== 0 || problems.length) {
    bad++;
    console.log(`[不平衡] ${relative(ROOT, file)}  {} = ${curly}  () = ${paren}  [] = ${square}  ${problems.slice(0, 3).join('; ')}`);
  }
}

console.log('\n========================================');
console.log(`共 ${files.length} 个 Kotlin 文件，${bad} 个括号不平衡`);
process.exit(bad > 0 ? 1 : 0);
