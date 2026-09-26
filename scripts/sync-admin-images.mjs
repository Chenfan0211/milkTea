import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 把小程序端高清素材（user-h5/assets/images/3x）同步到运营后台静态资源目录
 * （public/assets/images/3x），保证后台能正确渲染商品图 / Banner 等图片。
 *
 * 背景：数据库里商品 image / gallery_image 存的是相对路径
 * 如 /assets/images/3x/menu-product.jpg，后台（Vite）从 public/ 提供该资源。
 * 若 public/assets/images/3x 缺少对应文件，后台 <img> 会 404，表现为「商品没有图片」。
 *
 * 策略（2026-09-26 调整）：
 *   - 默认「内容不一致则覆盖」：对比源与目标的 MD5，不同即用小程序端版本覆盖。
 *     后台 public/assets/images/3x 是小程序素材的「镜像」，应始终跟随小程序端，
 *     不再「跳过已存在」——那样会导致小程序端更新/压缩后的图永远同步不到后台。
 *   - 传入 --force 时无条件覆盖（跳过 MD5 对比，用于批量强制刷新）。
 *   - 只同步扩展名在 ALLOW_EXT 白名单内的图片，忽略目录与无关文件。
 */

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = path.join(root, 'user-h5', 'assets', 'images', '3x');
const targetDir = path.join(root, 'public', 'assets', 'images', '3x');

const ALLOW_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp', '.svg']);
const force = process.argv.includes('--force');

/** 计算文件 MD5（对比内容是否一致用，替代「存在即跳过」的旧策略）。 */
function md5(file) {
  return crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
}

if (!fs.existsSync(sourceDir)) {
  throw new Error(`源目录不存在: ${sourceDir}`);
}
fs.mkdirSync(targetDir, { recursive: true });

const files = fs.readdirSync(sourceDir).filter((name) => ALLOW_EXT.has(path.extname(name).toLowerCase()));

let copied = 0;
let unchanged = 0;
for (const name of files) {
  const src = path.join(sourceDir, name);
  const dst = path.join(targetDir, name);

  // 内容是否一致：源与目标 MD5 相同即视为无需同步。
  // 只有真正「内容变了」才覆盖，避免无意义的重写（也便于日志区分）。
  const same = !force && fs.existsSync(dst) && md5(src) === md5(dst);
  if (same) {
    unchanged += 1;
    continue;
  }
  fs.copyFileSync(src, dst);
  copied += 1;
}

console.log(`后台图片同步完成: 覆盖/新增 ${copied} 张，内容一致跳过 ${unchanged} 张`);
console.log(`源: ${path.relative(root, sourceDir)} -> 目标: ${path.relative(root, targetDir)}`);