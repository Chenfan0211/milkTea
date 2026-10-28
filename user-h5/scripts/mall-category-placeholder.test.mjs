import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const placeholder = path.join(root, 'assets', 'images', '3x', 'mall-category-placeholder.jpg');
const buildScript = fs.readFileSync(path.join(root, 'scripts', 'build-images.mjs'), 'utf8');
const checkScript = fs.readFileSync(path.join(root, 'scripts', 'check-project.mjs'), 'utf8');

assert.equal(fs.existsSync(placeholder), true, '必须新增商城分类占位图');
assert.ok(fs.statSync(placeholder).size <= 20 * 1024, '商城分类占位图不得超过 20KB');
assert.match(buildScript, /mall-category-placeholder\.jpg/, 'build:images 必须生成商城分类占位图');
assert.match(buildScript, /144[\s\S]*144/, '商城分类占位图必须为 144x144');
assert.match(checkScript, /mall-category-placeholder\.jpg/, 'check 必须校验商城分类占位图');

console.log('mall-category-placeholder.test.mjs: passed');
