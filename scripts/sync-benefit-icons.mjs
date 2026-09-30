/**
 * 同步「会员权益图标」到运营后台可引用的目录。
 *
 * 背景：
 *   会员等级编辑弹窗的「图标」下拉框需要渲染 Lucide SVG。
 *   图标唯一源是小程序端的 user-h5/assets/icons/lucide/（符合项目图标规范），
 *   但运营后台（src/）与 user-h5 是相互独立的两个工程：
 *     · Vite 的 import.meta.glob 跨到 user-h5 后，query:'?url' 无法生成可访问 URL，
 *       产物里只剩源文件路径字符串 → 运行时图标全部空白（下拉框无图）。
 *   因此这里把白名单图标复制到 src/assets/lucide/，供运营后台从本工程内引用。
 *
 * 用法：node scripts/sync-benefit-icons.mjs
 *   （package.json 的 icons:benefit 脚本，以及 build 前置步骤会调用）
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SRC_DIR = path.join(ROOT, 'user-h5', 'assets', 'icons', 'lucide');
const OUT_DIR = path.join(ROOT, 'src', 'assets', 'lucide');

/**
 * 会员权益可选图标白名单 —— 必须与
 * src/views/marketing/member/BenefitIconSelect.vue 的 BENEFIT_ICON_WHITELIST 一致。
 * 新增权益图标时：先在 user-h5 补齐 SVG，再把名字加到这里和组件白名单。
 */
const BENEFIT_ICONS = [
  'badge-percent',
  'badge-japanese-yen',
  'badge-japanese-yen-brand',
  'star',
  'star-brand',
  'ticket',
  'ticket-percent',
  'gift',
  'gift-brand',
  'crown-gold',
  'medal',
  'gem',
  'award',
  'wallet',
  'wallet-brand',
  'trending-up',
  'trending-up-brand',
  'shopping-bag',
  'headset',
  'calendar-check',
  'calendar-check-brand',
  'heart',
  'heart-active',
  'handshake',
  'graduation-cap',
  'message-square-heart'
];

async function main() {
  await fs.mkdir(OUT_DIR, { recursive: true });

  const missing = [];
  let copied = 0;

  for (const name of BENEFIT_ICONS) {
    const from = path.join(SRC_DIR, `${name}.svg`);
    const to = path.join(OUT_DIR, `${name}.svg`);
    try {
      const content = await fs.readFile(from, 'utf8');
      await fs.writeFile(to, content, 'utf8');
      copied += 1;
    } catch {
      missing.push(name);
    }
  }

  // 清理已不在白名单中的历史产物，避免僵尸图标残留
  const existing = await fs.readdir(OUT_DIR).catch(() => []);
  let removed = 0;
  for (const file of existing) {
    if (!file.endsWith('.svg')) continue;
    const name = file.replace(/\.svg$/, '');
    if (!BENEFIT_ICONS.includes(name)) {
      await fs.unlink(path.join(OUT_DIR, file));
      removed += 1;
    }
  }

  if (missing.length) {
    console.error(`[benefit-icons] 缺少源图标（请先在 user-h5 补齐）：${missing.join(', ')}`);
    process.exit(1);
  }

  console.log(
    `[benefit-icons] 已同步 ${copied} 个会员权益图标到 src/assets/lucide/` +
      (removed ? `，清理 ${removed} 个旧文件` : '')
  );
}

main().catch(err => {
  console.error('[benefit-icons] 同步失败：', err);
  process.exit(1);
});
