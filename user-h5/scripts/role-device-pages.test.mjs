import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

/**
 * 门店经营角色「设备管理 / 冲泡记录 / 设备冲泡明细」多页的验收测试。
 *
 * 覆盖三件容易漏的事：
 *   1) 页面四件套齐全、已注册进分包、工作台入口指向它们；
 *   2) 数据走的是新接口（设备 / 概览 / 冲泡记录），且口径写对（默认近 7 天、只读）；
 *   3) 页面遵守设计规范（Lucide 本地图标、token、aria、无原生 button / emoji / 远程资源）。
 */

const PAGES = ['role-devices', 'role-brew-records', 'role-device-brew'];

for (const page of PAGES) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(
      fs.existsSync(path.join(root, `packageRole/${page}/${page}.${extension}`)),
      `missing ${page}.${extension}`
    );
  }
}

const appJson = JSON.parse(read('app.json'));
const roleSubpackage = (appJson.subpackages || []).find(item => item.root === 'packageRole');
assert.ok(roleSubpackage, 'app.json must keep the packageRole subpackage');
for (const page of PAGES) {
  assert.ok(roleSubpackage.pages.includes(`${page}/${page}`), `${page} must be registered in packageRole subpackage`);
}

// ---------- 工作台入口 ----------
const workbenchJs = read('packageRole/role-workbench/role-workbench.js');
assert.ok(
  workbenchJs.includes('resolveRoleActionRoute'),
  'workbench must resolve 设备管理/冲泡记录 via utils/roles.js'
);
assert.ok(
  !/const routeMap = \{/.test(workbenchJs),
  'workbench must not keep a local routeMap copy (历史 bug：两处拷贝只改了一边)'
);

// 「我的功能」同样必须能进这两页：历史 bug 是 profile 里另有一份 routeMap 缺这两个 action，
// 表现就是点了弹「该功能待接入」。
const profileJs = read('pages/profile/profile.js');
assert.ok(
  profileJs.includes('resolveRoleActionRoute'),
  'profile (我的功能) must resolve 设备管理/冲泡记录 via utils/roles.js'
);
assert.ok(!/const routeMap = \{/.test(profileJs), 'profile must not keep a local routeMap copy');

// ---------- 接口层 ----------
const apiJs = read('utils/api.js');
assert.ok(
  apiJs.includes('/api/v1/app/workbench/store/${subjectId}/devices`'),
  'api must expose the store devices endpoint'
);
assert.ok(
  apiJs.includes('/api/v1/app/workbench/store/${subjectId}/devices/summary`'),
  'api must expose the store device summary endpoint'
);
assert.ok(
  apiJs.includes('/api/v1/app/workbench/store/${subjectId}/brew-records`'),
  'api must expose the store brew records endpoint'
);
for (const name of ['fetchStoreDevices', 'fetchStoreDeviceSummary', 'fetchStoreBrewRecords']) {
  assert.ok(new RegExp(`function ${name}\\(`).test(apiJs), `api must define ${name}`);
  assert.ok(new RegExp(`^\\s+${name},$`, 'm').test(apiJs), `api must export ${name}`);
}

// ---------- 数据层 ----------
const roles = require(path.join(root, 'utils/roles.js'));

// 两个入口页（「我的功能」与「角色工作台」）都按同一份映射解析：
// devices / brew 必须指向已注册的这两个页面（历史 bug：profile 靠本地拷贝 → 弹「该功能待接入」）
assert.equal(
  roles.resolveRoleActionRoute('devices'),
  '/packageRole/role-devices/role-devices',
  'devices action must resolve to the 店设备 page'
);
assert.equal(
  roles.resolveRoleActionRoute('brew'),
  '/packageRole/role-brew-records/role-brew-records',
  'brew action must resolve to the 冲泡记录 page'
);
assert.equal(roles.resolveRoleActionRoute(''), null, 'empty action must resolve to null');

assert.ok(
  roles.getRoleDefinitions().some(item => item.id === 'store'),
  'store role must stay defined'
);

const storeDashboard = roles.getDashboard('store');
const actionIds = (storeDashboard.actions || []).map(item => item.id);
assert.ok(actionIds.includes('devices'), 'store workbench must expose 设备管理 entry');
assert.ok(actionIds.includes('brew'), 'store workbench must expose 冲泡记录 entry');
for (const action of storeDashboard.actions) {
  if (action.id === 'devices' || action.id === 'brew') {
    assert.ok(action.label && action.description, `store action ${action.id} must be labelled`);
    assert.ok(
      action.icon.startsWith('/assets/icons/lucide/'),
      `store action ${action.id} must use a local Lucide icon`
    );
  }
}

const rolesJs = read('utils/roles.js');
assert.ok(rolesJs.includes("id: 'devices'"), 'store workbench must expose 设备管理 entry');
assert.ok(rolesJs.includes("id: 'brew'"), 'store workbench must expose 冲泡记录 entry');
assert.ok(rolesJs.includes('/assets/icons/lucide/device.svg'), '设备管理 entry must use Lucide device icon');
assert.ok(rolesJs.includes('/assets/icons/lucide/brew-record.svg'), '冲泡记录 entry must use Lucide brew-record icon');

for (const name of ['loadStoreDevices', 'loadStoreDeviceSummary', 'loadStoreBrewRecords']) {
  assert.equal(typeof roles[name], 'function', `roles must export ${name}`);
}
assert.deepEqual(
  roles.STORE_BREW_RANGES.map(item => item.id),
  ['7d', '30d'],
  'brew ranges must be 近 7 天 / 近 30 天'
);

// 设备在线状态三态：在线 / 离线 / 未上报（历史数据可能没有状态）
assert.equal(roles.normalizeStoreDevice({ onlineStatus: 'ONLINE' }).statusKey, 'online');
assert.equal(roles.normalizeStoreDevice({ onlineStatus: 'offline' }).statusLabel, '离线');
assert.equal(roles.normalizeStoreDevice({}).statusLabel, '未上报');
assert.equal(
  roles.normalizeStoreDevice({ deviceSn: 'SN-1', alias: '吧台机' }).displayName,
  '吧台机',
  'alias wins over device SN as the display name'
);

// 时间范围：两端都含当天（近 7 天 = 今天往前 6 天）
const sevenDays = roles.storeBrewDateRange('7d');
const thirtyDays = roles.storeBrewDateRange('30d');
const dayDiff = (from, to) => Math.round((new Date(`${to}T00:00:00`) - new Date(`${from}T00:00:00`)) / 86400000);
assert.equal(dayDiff(sevenDays.from, sevenDays.to), 6, '近 7 天必须覆盖 7 个自然日');
assert.equal(dayDiff(thirtyDays.from, thirtyDays.to), 29, '近 30 天必须覆盖 30 个自然日');
assert.equal(roles.storeBrewDateRange('unknown').id, '7d', 'unknown range falls back to 近 7 天');

// 非门店角色不得拉数据（返回 null，由页面提示并回角色中心）
const noRoleResults = await Promise.all([
  roles.loadStoreDevices({}),
  roles.loadStoreBrewRecords({}),
  roles.loadStoreDeviceSummary()
]);
for (const result of noRoleResults) {
  assert.equal(result, null, '没有门店经营角色时不得返回数据');
}

// 两个新图标必须真实生成（来源固定 lucide-static，禁止远程/临时图标）
for (const icon of ['device.svg', 'brew-record.svg']) {
  const svg = read(`assets/icons/lucide/${icon}`);
  assert.ok(svg.includes('@license lucide-static'), `${icon} must keep the lucide license header`);
  assert.ok(svg.includes('stroke="#666762"'), `${icon} must use the project icon colour`);
  assert.ok(!svg.includes('currentColor'), `${icon} must not keep currentColor`);
}

// ---------- 分享登记 ----------
const share = require(path.join(root, 'utils/share.js'));
for (const page of PAGES) {
  const route = `packageRole/${page}/${page}`;
  assert.ok(share.isPrivatePage(route), `${route} must be private (含门店经营数据)`);
  assert.ok(share.PAGE_SHARE_TITLES[route], `${route} must define a share title`);
}

// ---------- 页面规范 ----------
for (const page of PAGES) {
  const wxml = read(`packageRole/${page}/${page}.wxml`);
  const wxss = read(`packageRole/${page}/${page}.wxss`);
  const js = read(`packageRole/${page}/${page}.js`);
  const json = JSON.parse(read(`packageRole/${page}/${page}.json`));

  assert.ok(!/<button[\s>]/.test(wxml), `${page} must not use native button`);
  assert.ok(!/<svg[\s>]/.test(wxml), `${page} must not inline SVG`);
  assert.ok(!/https?:\/\//.test(wxml), `${page} must not reference remote resources`);
  assert.ok(!/[\u{1F300}-\u{1FAFF}]/u.test(wxml), `${page} must not use emoji as icons`);
  assert.ok(wxml.includes('aria-role="button"'), `${page} must expose aria-role on tappable rows`);
  assert.ok(wxml.includes('aria-label'), `${page} must expose aria-label on tappable rows`);
  assert.ok(
    wxml.includes('/assets/icons/lucide/refresh-cw.svg'),
    `${page} must let the operator refresh with the Lucide refresh icon`
  );
  assert.ok(
    json.usingComponents && json.usingComponents['navigation-bar'] && json.usingComponents['empty-state'],
    `${page} must register navigation-bar and empty-state`
  );
  assert.ok(
    /var\(--(page-bg|card-bg|brand-green|brand-soft|line-color|text-)/.test(wxss),
    `${page} wxss must use design tokens`
  );
  // 只允许白色前景字面量；其余颜色一律走 token
  const hexLiterals = wxss.match(/#[0-9A-Fa-f]{3,8}\b/g) || [];
  assert.deepEqual(
    hexLiterals.filter(value => value.toUpperCase() !== '#FFFFFF'),
    [],
    `${page} wxss may only hardcode #FFFFFF`
  );
  assert.ok(js.includes('ensureBusinessRole'), `${page} must wait for the business role before rendering`);
  assert.ok(js.includes('onReachBottom'), `${page} must support 触底加载下一页`);
}

// ---------- 横向滚动行必须有显式高度（Skyline 下没高度会被压扁裁切） ----------
//
// 背景：冲泡记录页原来那排「设备筛选」chip 是横向 scroll-view 却没给高度，
// 上线后整行被压成几像素、内容被裁掉（用户截图反馈）。横滚容器在 Skyline 下
// 不会按内容撑开，必须显式写 height —— 这条守卫防止同类写法再次出现。
const KNOWN_LEGACY_HORIZONTAL_SCROLLERS = new Set([
  // 既有隐患（本轮不改，仅登记）：都是「记录页分类 chip 行」同款写法，
  // 各自的分类行高度问题需要单独排期修复；修好后请从本名单移除对应项。
  'pages/exchange-records/exchange-records.wxml::exchange-record-categories',
  'packageRole/role-income-records/role-income-records.wxml::income-record-categories',
  'packageRole/role-invest-records/role-invest-records.wxml::invest-record-categories',
  'packageRole/role-withdraw-records/role-withdraw-records.wxml::withdraw-record-categories'
]);

function walkWxml(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.git') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walkWxml(full, out);
    else if (entry.name.endsWith('.wxml')) out.push(full);
  }
  return out;
}

const wxmlFiles = ['pages', 'packageRole', 'packageHealth', 'components'].flatMap(dir => {
  const full = path.join(root, dir);
  return fs.existsSync(full) ? walkWxml(full) : [];
});
const horizontalScrollOffenders = [];
for (const wxmlFile of wxmlFiles) {
  const source = fs.readFileSync(wxmlFile, 'utf8');
  const wxssPath = wxmlFile.replace(/\.wxml$/, '.wxss');
  const css = fs.existsSync(wxssPath) ? fs.readFileSync(wxssPath, 'utf8') : '';
  for (const match of source.matchAll(/<scroll-view[^>]*>/gs)) {
    const tag = match[0];
    if (!/\sscroll-x(\s|>|=)/.test(tag)) continue;
    const classAttr = (tag.match(/class="([^"]+)"/) || [])[1] || '';
    const relative = path.relative(root, wxmlFile).replace(/\\/g, '/');
    const firstClass = classAttr.split(/\s+/).filter(Boolean)[0] || '';
    if (KNOWN_LEGACY_HORIZONTAL_SCROLLERS.has(`${relative}::${firstClass}`)) continue;
    let hasHeight = /style="[^"]*height/.test(tag);
    for (const cls of classAttr.split(/\s+/).filter(Boolean)) {
      if (new RegExp(`\\.${cls}\\s*\\{[^}]*height\\s*:`, 'm').test(css)) hasHeight = true;
    }
    if (!hasHeight) horizontalScrollOffenders.push(`${relative} :: class="${classAttr}"`);
  }
}
assert.deepEqual(
  horizontalScrollOffenders,
  [],
  `横向 scroll-view 必须给显式高度，否则 Skyline 下会被压扁裁切：\n${horizontalScrollOffenders.join('\n')}`
);

// 冲泡记录页本身：不再有横滚行、不再依赖设备接口，且有搜索框
const devicesWxml = read('packageRole/role-devices/role-devices.wxml');
const devicesJs = read('packageRole/role-devices/role-devices.js');
assert.ok(devicesWxml.includes('bindtap="openDeviceBrew"'), '设备卡片必须整体可进入设备冲泡明细');
assert.ok(
  devicesWxml.includes('aria-role="button"') && devicesWxml.includes('查看冲泡明细'),
  '设备卡片必须提供可读的明细入口'
);
assert.ok(devicesWxml.includes('data-device-sn="{{item.deviceSn}}"'), '设备卡片必须携带设备编码');
assert.ok(devicesJs.includes('encodeURIComponent(device.deviceSn)'), '设备卡片必须 URL 转义设备编码');
const deviceBrewJs = read('packageRole/role-device-brew/role-device-brew.js');
assert.ok(
  deviceBrewJs.includes('deviceSn: this.data.device && this.data.device.deviceSn'),
  '设备冲泡明细必须按设备编码筛选'
);
assert.ok(deviceBrewJs.includes('function safeDecodeOption'), '设备冲泡明细必须解码 URL 参数');
assert.ok(deviceBrewJs.includes('displayName: safeDecodeOption(source.displayName)'), '设备名称必须解码后展示');
assert.ok(deviceBrewJs.includes('statusLabel: safeDecodeOption(source.statusLabel)'), '设备状态必须解码后展示');

const brewWxml = read('packageRole/role-brew-records/role-brew-records.wxml');
const brewJs = read('packageRole/role-brew-records/role-brew-records.js');
assert.ok(!/scroll-x/.test(brewWxml), '冲泡记录页不应再保留横向筛选行');
assert.ok(brewWxml.includes('brew-search') && brewWxml.includes('bindinput="handleKeyword"'), '冲泡记录页必须有搜索框');
assert.ok(brewWxml.includes('bindconfirm="confirmSearch"'), '搜索框必须支持键盘搜索键立即查询');
assert.ok(brewWxml.includes('clearKeyword'), '搜索框必须有清空按钮');
assert.ok(brewJs.includes('SEARCH_DEBOUNCE_MS = 300'), '搜索必须带 300ms 去抖');
assert.ok(
  brewJs.includes('clearTimeout(this._searchTimer)') && brewJs.includes('onUnload'),
  '防抖定时器必须在卸载时清理'
);
assert.ok(brewJs.includes('this._reqSeq'), '搜索必须用请求序号丢弃过期响应，避免最后一次输入被吞掉');
assert.ok(!brewJs.includes('loadStoreDevices'), '冲泡记录页不得再调用设备接口');
assert.ok(!/deviceOptions|activeDeviceId|switchDevice/.test(brewJs), '冲泡记录页不应残留设备筛选状态');
assert.ok(
  read('utils/api.js').includes("'current', 'size', 'keyword', 'deviceSn', 'from', 'to'"),
  'fetchStoreBrewRecords 必须透传 keyword'
);
assert.ok(read('utils/roles.js').includes('params.keyword'), 'loadStoreBrewRecords 必须把 keyword 传给接口');

console.log('role-device-pages: ok');
