import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 页面 / 组件配置项禁用保留属性名。
 *
 * 背景（真实故障）：微信会把 Page 实例的 `route` 属性占用为「当前页面路径」
 * （字符串）。若在页面配置里定义同名方法 `route() {...}`，框架会把它覆盖成
 * 字符串，`this.route()` 直接抛 `TypeError: this.route is not a function`，
 * 页面卡在 onLoad 不动（表现为「一直停留在冷启动中」）。
 *
 * 因此这里静态扫描所有页面 / 组件，禁止用这些保留名定义方法。
 */
const RESERVED = [
  'route',
  'data',
  'options',
  'setData',
  'selectComponent',
  'selectAllComponents',
  'groupSetData',
  'animate',
  'clearAnimation'
];

function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) return walk(target);
    return [target];
  });
}

const targets = [
  ...walk(path.join(root, 'pages')),
  ...walk(path.join(root, 'components'))
].filter(file => file.endsWith('.js'));

const violations = [];
for (const file of targets) {
  const source = fs.readFileSync(file, 'utf8');
  const relative = path.relative(root, file);
  for (const name of RESERVED) {
    // 只匹配「对象字面量里定义为方法」的形式：缩进 + 名称 + ( 或 : function
    const pattern = new RegExp(`(?:^|\\n)\\s{4}${name}\\s*(?:\\(|:\\s*function)`, 'm');
    if (pattern.test(source)) {
      violations.push(`${relative} 使用了保留属性名定义方法: ${name}`);
    }
  }
}

assert.deepEqual(
  violations,
  [],
  `页面/组件不得使用保留属性名做方法名（会被框架覆盖，导致 TypeError 卡死）:\n${violations.join('\n')}`
);

// 启动页必须存在可调用的引导方法，且不得再叫 route
const launchSource = fs.readFileSync(path.join(root, 'pages/launch/launch.js'), 'utf8');
assert.ok(
  /^\s{4}bootstrap\s*\(/m.test(launchSource),
  '启动页必须用非保留名（bootstrap）定义路由方法'
);
assert.ok(
  !/^\s{4}route\s*\(/m.test(launchSource),
  '启动页不得再用 route 作为方法名（与微信页面实例保留属性冲突）'
);
assert.ok(
  launchSource.includes('this.bootstrap()'),
  '启动页 onLoad 必须调用 bootstrap'
);

console.log('页面/组件保留属性名冲突扫描通过');