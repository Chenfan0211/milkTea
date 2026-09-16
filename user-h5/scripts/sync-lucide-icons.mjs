import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceDir = path.join(root, 'node_modules', 'lucide-static', 'icons')
const outputDir = path.join(root, 'assets', 'icons', 'lucide')

const jobs = [
  { source: 'house', output: 'home', color: '#9B9B96' },
  { source: 'house', output: 'home-active', color: '#53882C' },
  { source: 'cup-soda', output: 'menu', color: '#9B9B96' },
  { source: 'cup-soda', output: 'menu-active', color: '#53882C' },
  { source: 'crown', output: 'member', color: '#9B9B96' },
  { source: 'crown', output: 'member-active', color: '#53882C' },
  { source: 'clipboard-list', output: 'orders', color: '#9B9B96' },
  { source: 'clipboard-list', output: 'orders-active', color: '#53882C' },
  { source: 'circle-user-round', output: 'profile', color: '#9B9B96' },
  { source: 'circle-user-round', output: 'profile-active', color: '#53882C' },
  { source: 'ticket-percent', output: 'ticket-percent', color: '#666762' },
  { source: 'gift', output: 'gift', color: '#666762' },
  { source: 'graduation-cap', output: 'graduation-cap', color: '#666762' },
  { source: 'calendar-check', output: 'calendar-check', color: '#666762' },
  { source: 'calendar-check', output: 'calendar-check-brand', color: '#53882C' },
  { source: 'share-2', output: 'share-2', color: '#666762' },
  { source: 'ticket', output: 'ticket', color: '#666762' },
  { source: 'badge-japanese-yen', output: 'badge-japanese-yen', color: '#666762' },
  { source: 'badge-japanese-yen', output: 'badge-japanese-yen-brand', color: '#53882C' },
  { source: 'map-pinned', output: 'map-pinned', color: '#666762' },
  { source: 'headset', output: 'headset', color: '#666762' },
  { source: 'handshake', output: 'handshake', color: '#666762' },
  { source: 'receipt', output: 'receipt', color: '#666762' },
  { source: 'search', output: 'search', color: '#9B9B96' },
  { source: 'soup', output: 'dine-in', color: '#747570' },
  { source: 'package', output: 'takeaway', color: '#747570' },
  { source: 'qr-code', output: 'qr-code', color: '#53882C' },
  { source: 'star', output: 'star', color: '#747570' },
  { source: 'map-pin', output: 'map-pin', color: '#747570' },
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
  { source: 'info', output: 'info', color: '#8D8D88' },
  { source: 'x', output: 'x', color: '#747570' },
  { source: 'heart', output: 'heart', color: '#777873' },
{ source: 'heart', output: 'heart-active', color: '#53882C' },
  { source: 'phone', output: 'phone', color: '#53882C' },
  { source: 'send', output: 'send', color: '#53882C' },
  { source: 'copy', output: 'copy', color: '#777873' },
  { source: 'rotate-ccw', output: 'rotate-ccw', color: '#53882C' },
  { source: 'message-square-heart', output: 'message-square-heart', color: '#53882C' },
  { source: 'chef-hat', output: 'chef-hat', color: '#747570' },
  { source: 'circle-check-big', output: 'circle-check-big', color: '#53882C' },
  { source: 'gem', output: 'gem', color: '#53882C' },
  { source: 'circle-help', output: 'circle-help', color: '#777873' }
]

fs.mkdirSync(outputDir, { recursive: true })

for (const job of jobs) {
  const sourcePath = path.join(sourceDir, `${job.source}.svg`)
  if (!fs.existsSync(sourcePath)) {
    throw new Error(`Lucide 图标不存在: ${job.source}`)
  }

  const svg = fs.readFileSync(sourcePath, 'utf8').replaceAll('currentColor', job.color).trim()
  fs.writeFileSync(path.join(outputDir, `${job.output}.svg`), `${svg}\n`, 'utf8')
}

console.log(`Lucide 图标同步完成: ${jobs.length} 个 SVG -> ${path.relative(root, outputDir)}`)
