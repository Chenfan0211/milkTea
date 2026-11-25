// 把「UTF-8 与 GBK 混杂」的源文件统一修成纯 UTF-8。
//
// 背景：user-h5 部分 wxss 曾被不同工具（编辑器 / 生成脚本）分别以
// UTF-8 和 GBK 写回同一文件，结果中文注释一段正常一段乱码。
// oxfmt / eslint 遇到这类文件会直接报「Failed to read file」。
//
// 为什么不能整文件按 GBK 解码：
//   文件里大部分中文是合法 UTF-8，整文件当 GBK 解会把它们变成乱码（彁鐜颁綑棰）。
// 为什么不能整文件按 UTF-8 容错解码：
//   非法字节会变成 U+FFFD，原始信息永久丢失（不可逆）。
//
// 正确做法：以「行」为单位判断。同一行内的中文通常来自同一次写入，
// 因此逐行尝试严格 UTF-8 解码，失败则整行按 GBK 解码。
// 这样合法的 UTF-8 行原样保留，GBK 行被精确还原。
import fs from 'node:fs';

const utf8Strict = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const gbkDecoder = new TextDecoder('gb18030');

/** 判断一段 buffer 是否能被严格 UTF-8 解码 */
function isValidUtf8(buf) {
  try {
    utf8Strict.decode(buf);
    return true;
  } catch {
    return false;
  }
}

/**
 * 逐行修复：按 \n 切分 buffer（保留分隔符），每行独立判断编码。
 * 返回 { text, fixedLines }
 */
function repairByLine(buf) {
  const out = [];
  let fixedLines = 0;
  let start = 0;

  for (let i = 0; i <= buf.length; i++) {
    const isLineEnd = i === buf.length || buf[i] === 0x0a;
    if (!isLineEnd) continue;

    const lineBuf = buf.subarray(start, i);
    if (lineBuf.length > 0) {
      if (isValidUtf8(lineBuf)) {
        out.push(utf8Strict.decode(lineBuf));
      } else {
        // 该行含 GBK 字节，整行按 GBK 解码
        out.push(gbkDecoder.decode(lineBuf));
        fixedLines++;
      }
    }
    if (i < buf.length) out.push('\n');
    start = i + 1;
  }

  return { text: out.join(''), fixedLines };
}

const targets = process.argv.slice(2);
if (targets.length === 0) {
  console.error('用法: node scripts/fix-mixed-encoding.mjs <file...>');
  process.exit(1);
}

let changed = 0;
for (const file of targets) {
  const original = fs.readFileSync(file);
  if (isValidUtf8(original)) {
    console.log(`OK    ${file}（已是合法 UTF-8，跳过）`);
    continue;
  }
  const { text, fixedLines } = repairByLine(original);
  fs.writeFileSync(file, text, 'utf8');
  console.log(`FIXED ${file}（修复 ${fixedLines} 行 GBK 内容）`);
  changed++;
}

console.log(`\n完成：修复 ${changed} 个文件`);
