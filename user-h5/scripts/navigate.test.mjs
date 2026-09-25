import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const calls = [];
globalThis.wx = {
  switchTab(options) {
    calls.push({ api: 'switchTab', url: options.url });
    if (options.complete) options.complete();
  },
  reLaunch(options) {
    calls.push({ api: 'reLaunch', url: options.url });
    if (options.complete) options.complete();
  },
  navigateBack(options) {
    calls.push({ api: 'navigateBack', delta: options.delta });
  }
};

let pageStack = [];
globalThis.getCurrentPages = () => pageStack;

const navigate = require(path.join(root, 'utils/navigate.js'));

// 1. tabBar 页必须走 switchTab（用 reLaunch 会静默失败，页面停在原地）
for (const tab of navigate.TAB_BAR_PAGES) {
  calls.length = 0;
  navigate.go(tab);
  assert.equal(calls.length, 1, `${tab} 必须只触发一次跳转`);
  assert.equal(calls[0].api, 'switchTab', `${tab} 是 tabBar 页，必须用 switchTab`);
}

// 2. 非 tabBar 页走 reLaunch
calls.length = 0;
navigate.go('/pages/order-detail/order-detail?id=1');
assert.equal(calls.length, 1, '非 tabBar 页必须只触发一次跳转');
assert.equal(calls[0].api, 'reLaunch', '非 tabBar 页必须用 reLaunch');

// 3. tabBar 页带 query 时要去掉 query（微信不允许带参切 tab）
calls.length = 0;
navigate.go('/pages/home/home?from=x');
assert.equal(calls[0].api, 'switchTab', '带 query 的 tabBar 页仍必须用 switchTab');
assert.equal(calls[0].url, '/pages/home/home', 'tabBar 跳转必须剥掉 query');

// 4. 路径规范化：无前导斜杠也要能识别为 tabBar 页
assert.equal(navigate.isTabBarPage('pages/home/home'), true, '无前导斜杠的 tabBar 路径必须可识别');
assert.equal(navigate.isTabBarPage('/pages/home/home'), true, '有前导斜杠的 tabBar 路径必须可识别');
assert.equal(navigate.isTabBarPage('/pages/order-detail/order-detail'), false, '非 tabBar 页不得误判');

// 5. 有上一页时 navigateBack；栈底时回落首页（switchTab）
pageStack = [{ route: 'pages/home/home' }, { route: 'pages/order-detail/order-detail' }];
calls.length = 0;
navigate.back();
assert.equal(calls[0].api, 'navigateBack', '有上一页时必须 navigateBack');

pageStack = [{ route: 'pages/auth-login/auth-login' }];
calls.length = 0;
navigate.back();
assert.equal(calls[0].api, 'switchTab', '栈底回落首页必须用 switchTab，否则会静默失败');
assert.equal(calls[0].url, '/pages/home/home', '栈底必须回落到首页');

// 6. complete 回调必须透传（登录成功续跑原操作依赖它）
calls.length = 0;
let completed = false;
navigate.go('/pages/home/home', {
  complete() {
    completed = true;
  }
});
assert.equal(completed, true, 'complete 回调必须被透传执行');

// 7. 跳转清单必须与 app.json 的 tabBar 逐项一致（不一致会导致跳转静默失败）
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
const declared = appJson.tabBar.list.map(item => `/${item.pagePath}`);
assert.deepEqual(
  navigate.TAB_BAR_PAGES.slice().sort(),
  declared.slice().sort(),
  'TAB_BAR_PAGES 必须与 app.json tabBar.list 完全一致'
);

console.log('页面跳转（tabBar 必须 switchTab）与栈底回落测试通过');