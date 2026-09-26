import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getIconData } from '@iconify/utils';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUTPUT = path.join(ROOT, 'src', 'assets', 'iconify', 'offline-icons.json');
const COLLECTION_PREFIXES = ['ant-design', 'heroicons', 'material-symbols', 'mdi', 'ph'];
const SOURCE_ROOTS = [
  path.join(ROOT, 'src'),
  path.join(ROOT, 'server', 'src', 'main', 'resources', 'db'),
  path.join(ROOT, '.env'),
  path.join(ROOT, '.env.prod'),
  path.join(ROOT, '.env.prod-ip'),
  path.join(ROOT, '.env.test')
];
const ICON_NAME_RE = /(?:ant-design|heroicons|material-symbols|mdi|ph):[a-z0-9]+(?:-[a-z0-9]+)*/g;
const SPECIAL_ICON_NAMES = ['mdi:menu'];
const SCAN_EXTENSIONS = new Set(['.js', '.json', '.mjs', '.sql', '.ts', '.vue']);

function shouldScan(filePath) {
  const baseName = path.basename(filePath);
  return baseName.startsWith('.env') || SCAN_EXTENSIONS.has(path.extname(filePath));
}

async function collectFiles(target, result = []) {
  const stat = await fs.stat(target).catch(() => null);
  if (!stat) return result;

  if (stat.isFile()) {
    if (shouldScan(target)) result.push(target);
    return result;
  }

  const entries = await fs.readdir(target, { withFileTypes: true });
  await Promise.all(
    entries.map(entry => {
      if (entry.name === 'node_modules' || entry.name === 'dist') return Promise.resolve();
      return collectFiles(path.join(target, entry.name), result);
    })
  );

  return result;
}

function addIconName(iconNames, iconName) {
  const [prefix, ...nameParts] = iconName.split(':');
  const name = nameParts.join(':');
  if (!COLLECTION_PREFIXES.includes(prefix) || !name) return;
  if (!iconNames.has(prefix)) iconNames.set(prefix, new Set());
  iconNames.get(prefix).add(name);
}

async function main() {
  const files = [];
  for (const root of SOURCE_ROOTS) {
    await collectFiles(root, files);
  }

  const iconNames = new Map(COLLECTION_PREFIXES.map(prefix => [prefix, new Set()]));
  for (const iconName of SPECIAL_ICON_NAMES) addIconName(iconNames, iconName);

  for (const file of files) {
    const content = await fs.readFile(file, 'utf8');
    for (const [iconName] of content.matchAll(ICON_NAME_RE)) {
      addIconName(iconNames, iconName);
    }
  }

  const collections = [];
  let iconCount = 0;

  for (const prefix of COLLECTION_PREFIXES) {
    const names = [...iconNames.get(prefix)].sort();
    if (names.length === 0) continue;

    const collectionPath = path.join(ROOT, 'node_modules', '@iconify', 'json', 'json', `${prefix}.json`);
    const collection = JSON.parse(await fs.readFile(collectionPath, 'utf8'));
    const icons = {};

    for (const name of names) {
      const icon = getIconData(collection, name);
      if (!icon) throw new Error(`Icon not found: ${prefix}:${name}`);
      icons[name] = icon;
    }

    collections.push({
      prefix,
      width: collection.width,
      height: collection.height,
      icons
    });
    iconCount += names.length;
  }

  await fs.mkdir(path.dirname(OUTPUT), { recursive: true });
  await fs.writeFile(OUTPUT, `${JSON.stringify(collections, null, 2)}\n`, 'utf8');

  console.log(`Generated ${iconCount} offline Iconify icons in ${path.relative(ROOT, OUTPUT)}`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
