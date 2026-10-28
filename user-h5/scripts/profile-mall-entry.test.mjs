import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const navigation = [];
globalThis.wx = {
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  },
  showToast() {},
  showModal() {},
  navigateTo: options => navigation.push(options.url),
  showShareMenu() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 }),
  request(options) {
    setTimeout(() => {
      options.success && options.success({ statusCode: 200, data: { code: 0, message: 'ok', data: {} } });
    }, 5);
  }
};

const appObj = {
  globalData: { points: 0, signedDates: [], pointsListeners: [] },
  subscribePoints() {
    return () => {};
  }
};
globalThis.getApp = () => appObj;

let pageDefinition = null;
globalThis.Page = definition => {
  pageDefinition = definition;
};

const api = require(path.join(root, 'utils/api.js'));
require(path.join(root, 'pages/profile/profile.js'));

function createPage() {
  const instance = Object.assign({}, pageDefinition);
  instance.data = JSON.parse(JSON.stringify(pageDefinition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

const legacyFunctions = [
  { id: 'share', label: '分享有礼', icon: '/assets/icons/lucide/share-2.svg' },
  { id: 'coupon-wallet', label: '我的券包', icon: '/assets/icons/lucide/ticket.svg' },
  { id: 'points', label: '时光币兑换', icon: '/assets/icons/lucide/badge-japanese-yen.svg' },
  { id: 'benefits', label: '会员权益', icon: '/assets/icons/lucide/member.svg' }
];

async function mount(config) {
  api.fetchProfileConfig = () => Promise.resolve(config);
  const page = createPage();
  page.onShow();
  await new Promise(resolve => setTimeout(resolve, 20));
  return page;
}

// 接口仍返回旧的 8 项配置时，「我的」页必须补出线上商城入口。
{
  const page = await mount({ functions: legacyFunctions, banners: [] });
  const mall = page.data.profileFunctions.filter(item => item.id === 'mall');
  assert.equal(mall.length, 1, '旧配置缺少 mall 时必须合并出唯一线上商城入口');
  assert.equal(mall[0].label, '线上商城');
  assert.equal(mall[0].icon, '/assets/icons/lucide/shopping-bag.svg');
}

// 后端已经带 mall 时不得重复渲染。
{
  const page = await mount({
    functions: legacyFunctions.concat({ id: 'mall', label: '线上商城', icon: '/assets/icons/lucide/shopping-bag.svg' }),
    banners: []
  });
  assert.equal(
    page.data.profileFunctions.filter(item => item.id === 'mall').length,
    1,
    '后端与前端同时提供 mall 时必须去重'
  );
}

// 首帧（接口未返回前）也必须存在商城入口，不能依赖网络成功。
{
  const page = createPage();
  assert.equal(
    page.data.profileFunctions.filter(item => item.id === 'mall').length,
    1,
    '「我的」页初始数据必须包含线上商城入口'
  );
}

// 点击商城入口必须进入 packageMall 商城首页。
{
  const page = await mount({ functions: legacyFunctions, banners: [] });
  page.handleProfileAction({ currentTarget: { dataset: { id: 'mall', label: '线上商城' } } });
  assert.equal(navigation.at(-1), '/packageMall/pages/home/home', '商城入口必须跳转 packageMall 首页');
}

console.log('「我的」页线上商城入口测试通过');
