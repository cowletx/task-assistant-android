// android_resource_check.mjs
// 校验 Android 资源引用的完整性：Manifest 与所有 res/*.xml 里出现的
// @type/name（以及 ?attr/...）是否真的在 res/ 下有定义。
// 这类错误（例如 @mipmap/ic_launcher 缺文件、@string/xxx 拼错）会让 AAPT2 直接构建失败，
// 而本机没有编译器，所以用这个脚本提前发现。
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, basename } from 'node:path';

const ANDROID = 'E:\\dsh\\duanlian\\android';
const APP_RES = join(ANDROID, 'app', 'src', 'main', 'res');
const MANIFEST = join(ANDROID, 'app', 'src', 'main', 'AndroidManifest.xml');

// 解析 res/ 下所有资源：目录名（去掉 -限定符）→ 定义的名字集合
const defined = new Map(); // type -> Set<name>
const xmlFiles = [];

(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full);
    else if (name.endsWith('.xml')) xmlFiles.push(full);
  }
})(APP_RES);

for (const entry of readdirSync(APP_RES)) {
  const full = join(APP_RES, entry);
  if (!statSync(full).isDirectory()) continue;
  const type = entry.split('-')[0]; // mipmap-anydpi-v26 -> mipmap
  if (!defined.has(type)) defined.set(type, new Set());
  for (const f of readdirSync(full)) {
    if (f.endsWith('.xml') || f.endsWith('.png') || f.endsWith('.webp') || f.endsWith('.jpg')) {
      defined.get(type).add(f.slice(0, f.lastIndexOf('.')));
    }
  }
}
// values/*.xml 里 <string name="x"> / <color name="x"> / <style name="x"> 等
for (const file of xmlFiles) {
  if (!file.includes('values')) continue;
  const text = readFileSync(file, 'utf8');
  for (const m of text.matchAll(/<(string|color|style|dimen|integer|bool|array|plurals)\s+name="([^"]+)"/g)) {
    const type = m[1];
    if (type === 'array') continue;
    if (!defined.has(type)) defined.set(type, new Set());
    defined.get(type).add(m[2]);
  }
}

console.log('已定义的资源类型与数量：');
[...defined.entries()].sort().forEach(([t, s]) => console.log(`  ${t}: ${s.size} 个`));

// 收集引用
const problems = [];
const refRe = /[@?](?:\+)?(?:android:)?(\w+)\/([\w.]+)/g;

function checkRefs(text, where) {
  for (const m of text.matchAll(refRe)) {
    const [, type, name] = m;
    // 跳过 android: 内置命名空间与 xml 工具属性
    if (m[0].startsWith('?') && !m[0].startsWith('?attr')) continue;
    if (type === 'attr' || type === 'id') continue;
    const set = defined.get(type);
    if (!set) {
      problems.push(`${where}: 引用 @${type}/${name}，但 res/ 下没有 ${type} 类型资源`);
      continue;
    }
    if (!set.has(name)) {
      problems.push(`${where}: 引用 @${type}/${name}，但未找到定义（该类型已有: ${[...set].slice(0, 8).join(', ')}${set.size > 8 ? ' …' : ''}）`);
    }
  }
}

checkRefs(readFileSync(MANIFEST, 'utf8'), 'AndroidManifest.xml');
for (const file of xmlFiles) {
  checkRefs(readFileSync(file, 'utf8'), relative(ANDROID, file));
}

// 反向检查：定义了但被 values/themes 或 manifest 用到的 style 是否存在父样式（只做提示）
console.log('\n========================================');
if (problems.length === 0) {
  console.log('资源引用校验：未发现缺失引用');
} else {
  console.log(`资源引用校验：发现 ${problems.length} 个问题`);
  problems.forEach(p => console.log('  - ' + p));
}

// 额外：Manifest 里 android:name 指向的类是否在源码中存在
// 注意：android:name 也用于权限/action/category/property，需要用上下文区分；
// 并且类名可能是相对的（相对 manifest 的 package / namespace）。
const manifestText = readFileSync(MANIFEST, 'utf8');
const namespace = 'com.example.taskassistant'; // 与 app/build.gradle.kts 的 namespace 一致
const srcRoot = join(ANDROID, 'app', 'src', 'main', 'java');
const srcIndex = new Set();
(function indexSrc(dir, prefix) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) indexSrc(full, prefix ? `${prefix}.${name}` : name);
    else if (name.endsWith('.kt')) srcIndex.add(`${prefix}.${name.slice(0, -3)}`);
  }
})(srcRoot, '');

/** 只在 <application>/<activity>/<service>/<receiver>/<provider> 的 android:name 上取类名 */
const elementRe = /<(application|activity|service|receiver|provider)\b[^>]*android:name="([^"]+)"/g;
const classRefs = [...manifestText.matchAll(elementRe)].map(m => ({ tag: m[1], name: m[2] }));

console.log('\nManifest 中声明的组件类：');
let classProblems = 0;
for (const { tag, name } of classRefs) {
  const fqcn = name.startsWith('.')
    ? namespace + name
    : (name.includes('.') ? name : `${namespace}.${name}`);
  const ok = srcIndex.has(fqcn);
  console.log(`  ${ok ? 'OK  ' : '缺失'} <${tag}> ${name}  ->  ${fqcn}`);
  if (!ok) { problems.push(`Manifest 引用的类不存在: ${name} (${fqcn})`); classProblems++; }
}

// 反向：源码里的 Application/Activity/Service 是否都在 Manifest 注册
console.log('\n源码中的组件类是否已注册：');
for (const fqcn of [...srcIndex].sort()) {
  const isApp = /TaskAssistantApp$/.test(fqcn);
  const isActivity = /Activity$/.test(fqcn);
  const isService = /Service$/.test(fqcn);
  if (!isApp && !isActivity && !isService) continue;
  const registered = manifestText.includes(fqcn) ||
    manifestText.includes(fqcn.replace(`${namespace}.`, '.')) ||
    manifestText.includes(fqcn.replace(`${namespace}.`, ''));
  console.log(`  ${registered ? 'OK  ' : '未注册'} ${fqcn}`);
  if (!registered) problems.push(`组件类未在 Manifest 注册: ${fqcn}`);
}

console.log('\n========================================');
if (problems.length === 0) {
  console.log('资源引用 + 组件注册校验：未发现问题');
} else {
  console.log(`共发现 ${problems.length} 个问题`);
  problems.forEach(p => console.log('  - ' + p));
}
process.exit(problems.length > 0 ? 1 : 0);