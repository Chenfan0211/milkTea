import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceDir = path.join(root, 'node_modules', 'lucide-static', 'icons');
const outputDir = path.join(root, 'assets', 'icons', 'lucide');

const jobs = [
  { source: 'house', output: 'home', color: '#9B9B96' },
  { source: 'house', output: 'home-active', color: '#53882C' },
  { source: 'cup-soda', output: 'menu', color: '#9B9B96' },
  { source: 'cup-soda', output: 'menu-active', color: '#53882C' },
  { source: 'crown', output: 'member', color: '#666762' },
  { source: 'crown', output: 'member-active', color: '#53882C' },
  { source: 'crown', output: 'member-gold', color: '#D4A017', fill: true },
  { source: 'clipboard-list', output: 'orders', color: '#9B9B96' },
  { source: 'clipboard-list', output: 'orders-active', color: '#53882C' },
  { source: 'circle-user-round', output: 'profile', color: '#9B9B96' },
  { source: 'circle-user-round', output: 'profile-active', color: '#53882C' },
  { source: 'ticket-percent', output: 'ticket-percent', color: '#666762' },
  { source: 'gift', output: 'gift', color: '#666762' },
  { source: 'gift', output: 'gift-brand', color: '#53882C' },
  { source: 'graduation-cap', output: 'graduation-cap', color: '#666762' },
  { source: 'calendar-check', output: 'calendar-check', color: '#666762' },
  { source: 'calendar-check', output: 'calendar-check-brand', color: '#53882C' },
  { source: 'share-2', output: 'share-2', color: '#666762' },
  { source: 'ticket', output: 'ticket', color: '#666762' },
  { source: 'badge-japanese-yen', output: 'badge-japanese-yen', color: '#666762' },
  { source: 'badge-japanese-yen', output: 'badge-japanese-yen-brand', color: '#53882C' },
  { source: 'map-pinned', output: 'map-pinned', color: '#666762' },
  { source: 'locate-fixed', output: 'locate-fixed', color: '#2F302D' },
  { source: 'refresh-cw', output: 'refresh-cw', color: '#2F302D' },
  { source: 'headset', output: 'headset', color: '#666762' },
  { source: 'handshake', output: 'handshake', color: '#666762' },
  { source: 'receipt', output: 'receipt', color: '#666762' },
  { source: 'shopping-bag', output: 'shopping-bag', color: '#747570' },
  { source: 'receipt-text', output: 'receipt-brand', color: '#53882C' },
  { source: 'settings', output: 'settings-brand', color: '#53882C' },
  { source: 'search', output: 'search', color: '#9B9B96' },
  { source: 'soup', output: 'dine-in', color: '#747570' },
  { source: 'package', output: 'takeaway', color: '#747570' },
  { source: 'qr-code', output: 'qr-code', color: '#53882C' },
  { source: 'star', output: 'star', color: '#747570' },
  { source: 'star', output: 'star-brand', color: '#53882C' },
  { source: 'star', output: 'star-gold', color: '#D4A017', fill: true },
  { source: 'apple', output: 'apple-white', color: '#FFFFFF' },
  { source: 'leaf', output: 'leaf-white', color: '#FFFFFF' },
  { source: 'phone', output: 'phone-white', color: '#FFFFFF' },
  { source: 'navigation', output: 'navigation-white', color: '#FFFFFF' },
  { source: 'sprout', output: 'store-decor-sprout', color: '#A6C685' },
  { source: 'bookmark', output: 'bookmark', color: '#2F302D' },
  { source: 'bookmark-check', output: 'bookmark-check', color: '#53882C' },
  { source: 'circle-dot', output: 'queue-safe', color: '#53882C', dotFill: true },
  { source: 'circle-dot', output: 'queue-warning', color: '#E6A23C', dotFill: true },
  { source: 'circle-dot', output: 'queue-danger', color: '#FF0000', dotFill: true },
  { source: 'map-pin', output: 'map-pin', color: '#747570' },
  { source: 'map-pin', output: 'store-map-pin', color: '#53882C' },
  { source: 'map-pin', output: 'store-map-pin-active', color: '#3F6E1F' },
  { source: 'chevron-right', output: 'chevron-right', color: '#777873' },
  { source: 'chevron-right', output: 'chevron-right-brand', color: '#53882C' },
  { source: 'chevron-left', output: 'chevron-left', color: '#2F302D' },
  { source: 'chevron-down', output: 'chevron-down', color: '#858681' },
  { source: 'navigation', output: 'navigation', color: '#53882C' },
  { source: 'shopping-bag', output: 'shopping-bag-white', color: '#FFFFFF' },
  { source: 'clipboard-pen-line', output: 'empty-design', color: '#53882C' },
  { source: 'check', output: 'check', color: '#FFFFFF' },
  { source: 'trash-2', output: 'trash-2', color: '#8D8D88' },
  { source: 'pencil', output: 'pencil', color: '#53882C' },
  { source: 'minus', output: 'minus', color: '#53882C' },
  { source: 'plus', output: 'plus', color: '#FFFFFF' },
  { source: 'plus', output: 'plus-brand', color: '#53882C' },
  { source: 'info', output: 'info', color: '#8D8D88' },
  { source: 'x', output: 'x', color: '#747570' },
  { source: 'heart', output: 'heart', color: '#777873' },
  { source: 'heart', output: 'heart-active', color: '#53882C' },
  { source: 'phone', output: 'phone', color: '#53882C' },
  { source: 'send', output: 'send', color: '#53882C' },
  { source: 'copy', output: 'copy', color: '#777873' },
  { source: 'rotate-ccw', output: 'rotate-ccw', color: '#53882C' },
  { source: 'rotate-ccw', output: 'rotate-ccw-white', color: '#FFFFFF' },
  { source: 'message-square-heart', output: 'message-square-heart', color: '#53882C' },
  { source: 'chef-hat', output: 'chef-hat', color: '#747570' },
  { source: 'circle-check-big', output: 'circle-check-big', color: '#53882C' },
  { source: 'award', output: 'award', color: '#D4A017', fill: true },
  { source: 'medal', output: 'medal', color: '#53882C' },
  { source: 'gem', output: 'gem', color: '#53882C' },
  { source: 'wallet', output: 'wallet', color: '#53882C' },
  { source: 'wallet', output: 'wallet-brand', color: '#53882C' },
  { source: 'wallet', output: 'wallet-white', color: '#FFFFFF' },
  { source: 'wallet', output: 'wallet-muted', color: '#9B9B96' },
  { source: 'clock', output: 'clock-muted', color: '#9B9B96' },
  { source: 'shield-check', output: 'shield-check-muted', color: '#9B9B96' },
  { source: 'receipt-text', output: 'receipt-muted', color: '#9B9B96' },
  { source: 'hourglass', output: 'hourglass-brand', color: '#53882C' },
  { source: 'circle-check-big', output: 'circle-check-big-muted', color: '#9B9B96' },
  { source: 'circle', output: 'circle-dot-brand', color: '#53882C', dotFill: true },
  { source: 'circle', output: 'circle-dot-muted', color: '#9B9B96' },
  { source: 'circle-alert', output: 'circle-alert-warning', color: '#C65A1E' },
  { source: 'circle-help', output: 'circle-help', color: '#777873' },
  { source: 'file-search', output: 'file-search-brand', color: '#53882C' },
  { source: 'store', output: 'store', color: '#747570' },
  { source: 'store', output: 'store-brand', color: '#53882C' },
  { source: 'trending-up', output: 'trending-up', color: '#747570' },
  { source: 'trending-up', output: 'trending-up-brand', color: '#53882C' },
  { source: 'users', output: 'users', color: '#747570' },
  { source: 'users', output: 'users-brand', color: '#53882C' },
  { source: 'user-plus', output: 'user-plus', color: '#747570' },
  { source: 'scan-line', output: 'scan-line', color: '#747570' },
  { source: 'banknote', output: 'banknote', color: '#747570' },
  { source: 'badge-percent', output: 'badge-percent', color: '#747570' },
  { source: 'crown', output: 'crown-gold', color: '#D4A017', fill: true },
  { source: 'lock', output: 'lock-muted', color: '#9B9B96' },
  { source: 'badge-percent', output: 'badge-percent-muted', color: '#9B9B96' },
  { source: 'star', output: 'star-muted', color: '#9B9B96' },
  { source: 'badge-japanese-yen', output: 'badge-japanese-yen-muted', color: '#9B9B96' },
  { source: 'ticket', output: 'ticket-muted', color: '#9B9B96' },
  { source: 'gift', output: 'gift-muted', color: '#9B9B96' },
  { source: 'trending-up', output: 'trending-up-muted', color: '#9B9B96' },
  { source: 'store', output: 'store-white', color: '#FFFFFF' },
  { source: 'trending-up', output: 'trending-up-white', color: '#FFFFFF' },
  { source: 'users', output: 'users-white', color: '#FFFFFF' }
];

fs.mkdirSync(outputDir, { recursive: true });

for (const job of jobs) {
  const sourcePath = path.join(sourceDir, `${job.source}.svg`);
  if (!fs.existsSync(sourcePath)) {
    throw new Error(`Lucide 图标不存在: ${job.source}`);
  }

  let svg = fs.readFileSync(sourcePath, 'utf8').replaceAll('currentColor', job.color).trim();
  if (job.fill) svg = svg.replace('fill="none"', `fill="${job.color}"`);
  if (job.dotFill) {
    svg = svg.replace('fill="none"', `fill="${job.color}"`);
    svg = svg.replace(
      '<circle cx="12" cy="12" r="1" />',
      '<circle cx="12" cy="12" r="1" fill="#FFFFFF" stroke="#FFFFFF" />'
    );
    svg = svg.replace('<circle cx="12" cy="12" r="10" />', `<circle cx="12" cy="12" r="10" fill="${job.color}" />`);
  }
  fs.writeFileSync(path.join(outputDir, `${job.output}.svg`), `${svg}\n`, 'utf8');
}

console.log(`Lucide 图标同步完成: ${jobs.length} 个 SVG -> ${path.relative(root, outputDir)}`);
