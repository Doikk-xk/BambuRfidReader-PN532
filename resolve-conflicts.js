#!/usr/bin/env node
/**
 * 自动解决 rebase 冲突中的 strings.xml 同名 key 冲突。
 *
 * 背景: PN532 分支往 strings.xml 加了 bt_* 系列文案，上游若新增同名 key
 *       (或改同名 key 的文案) 就会冲突。上游的文案一般值得保留，
 *       所以策略是: 保留双方内容，把上游侧重名的 key 自动改名加 _upstream 后缀。
 *
 * 非 strings.xml 的文件 (如 MainActivity.kt) 保持冲突状态，交给人工判断 ——
 *       那种冲突涉及逻辑，不能盲目合并。
 *
 * 用法: node resolve-conflicts.js
 * 退出码: 0 = 已全部处理; 2 = 仍有需要人工处理的冲突
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const CONFLICT_START = /^<<<<<<< /;
const CONFLICT_MID = /^=======\s*$/;
const CONFLICT_END = /^>>>>>>> /;

function gitOut(cmd) {
  try {
    return execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (e) {
    return '';
  }
}

/** 提取一侧所有 <string name="KEY"> 形式的 key */
function collectKeys(block) {
  const keys = [];
  const re = /<string\s+name="([^"]+)"/g;
  let m;
  while ((m = re.exec(block)) !== null) keys.push(m[1]);
  return keys;
}

/** 合并单个冲突块：保留双方，上游侧重名 key 自动改名 */
function mergeBlock(ours, theirs, eol, fileKeys) {
  const ourKeys = new Set(collectKeys(ours));
  // 文件里其它地方已有的 key 也要算进去，避免合并后出现重复资源
  for (const k of fileKeys) ourKeys.add(k);
  const theirKeys = new Set(collectKeys(theirs));

  // 上游侧同名 key 需要改名，避免 Android 重复资源报错
  let renamed = theirs;
  for (const k of theirKeys) {
    if (ourKeys.has(k)) {
      const re = new RegExp(`(<string\\s+name=")${k}(")`, 'g');
      renamed = renamed.replace(re, `$1${k}_upstream$2`);
    }
  }
  // ours 末尾可能没有换行，直接拼接会把两行挤到一起
  const tail = ours.endsWith(eol) || ours === '' ? ours : ours + eol;
  return tail + renamed;
}

/** 处理单个文件的全部冲突块 */
function resolveFile(file) {
  const raw = fs.readFileSync(file, 'utf8');
  // 保留原文件行尾风格 (CRLF / LF)，避免整文件行尾变化造成巨大 diff
  const eol = raw.includes('\r\n') ? '\r\n' : '\n';
  const lines = raw.split(/\r?\n/);
  const out = [];
  let i = 0;
  let changed = false;
  let autoMerged = 0;

  // 第一遍: 收集冲突块之外已存在的 key (合并后不能和它们重名)
  const fileKeys = new Set();
  {
    let inConflict = false;
    for (const line of lines) {
      if (CONFLICT_START.test(line)) { inConflict = true; continue; }
      if (inConflict && CONFLICT_MID.test(line)) continue;
      if (inConflict && CONFLICT_END.test(line)) { inConflict = false; continue; }
      if (!inConflict) for (const k of collectKeys(line)) fileKeys.add(k);
    }
  }

  while (i < lines.length) {
    if (CONFLICT_START.test(lines[i])) {
      const ours = [];
      const theirs = [];
      i++;
      while (i < lines.length && !CONFLICT_MID.test(lines[i])) { ours.push(lines[i]); i++; }
      i++; // 跳过 =======
      while (i < lines.length && !CONFLICT_END.test(lines[i])) { theirs.push(lines[i]); i++; }
      i++; // 跳过 >>>>>>>

      const isStrings = /strings\.xml$/.test(file);
      if (isStrings) {
        const merged = mergeBlock(ours.join(eol), theirs.join(eol), eol, fileKeys);
        out.push(merged);
        autoMerged++;
        changed = true;
      } else {
        // 非 strings 文件：原样保留冲突，交人工
        out.push('<<<<<<< OURS');
        out.push(...ours);
        out.push('=======');
        out.push(...theirs);
        out.push('>>>>>>> THEIRS');
      }
    } else {
      out.push(lines[i]);
      i++;
    }
  }

  if (changed) {
    fs.writeFileSync(file, out.join(eol));
  }
  return { autoMerged, changed };
}

function main() {
  const conflicted = gitOut('git diff --name-only --diff-filter=U')
    .split('\n')
    .map(s => s.trim())
    .filter(Boolean);

  if (conflicted.length === 0) {
    console.log('[OK] 没有处于冲突状态的文件');
    return 0;
  }

  const remaining = [];
  let autoCount = 0;

  for (const f of conflicted) {
    if (!fs.existsSync(f)) continue;
    const r = resolveFile(f);
    if (r.autoMerged > 0) {
      autoCount += r.autoMerged;
      console.log(`[自动合并] ${f}  (${r.autoMerged} 个冲突块, 上游同名 key 已加 _upstream 后缀)`);
    } else {
      remaining.push(f);
      console.log(`[需人工] ${f}`);
    }
  }

  console.log('');
  if (remaining.length > 0) {
    console.log(`以下文件需要人工处理（${remaining.length} 个）:`);
    for (const f of remaining) console.log(`    ${f}`);
    console.log('');
    console.log("  查看:  grep -n -A8 '^<<<<<<<' <文件>");
    console.log("  解决后: git add <文件> && git rebase --continue");
    return 2;
  }

  console.log(`[OK] ${autoCount} 个冲突块已自动合并`);
  checkDuplicateKeys(conflicted);
  console.log('接下来执行: git add -A && git rebase --continue');
  return 0;
}

/** 收尾检查: 扫描 strings.xml 是否还有重复 key (Android 会直接编译报错) */
function checkDuplicateKeys(files) {
  for (const f of files) {
    if (!/strings\.xml$/.test(f) || !fs.existsSync(f)) continue;
    const s = fs.readFileSync(f, 'utf8');
    const keys = [];
    const re = /<string\s+name="([^"]+)"/g;
    let m;
    while ((m = re.exec(s)) !== null) keys.push(m[1]);
    const dup = [...new Set(keys.filter((k, i) => keys.indexOf(k) !== i))];
    if (dup.length > 0) {
      console.log(`[警告] ${f} 存在重复 key: ${dup.join(', ')}`);
      console.log('       Android 会报 "Duplicate resources" 编译错误，请手动改名。');
    }
  }
}

process.exit(main());
