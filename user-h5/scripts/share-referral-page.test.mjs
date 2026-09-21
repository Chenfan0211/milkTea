import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageRoot = path.join(root, 'pages/share-referral/share-referral');

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少分享有礼页文件: share-referral.${extension}`);
}

let definition;
const storage = {};
const toasts = [];
globalThis.wx = {
  showShareMenu() {},
  setClipboardData() {},
  showToast(options) {
    toasts.push(options && options.title);
  },
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  }
};
globalThis.Page = page => {
  definition = page;
};

assert.doesNotThrow(() => require(`${pageRoot}.js`), '分享有礼页脚本必须能正常加载');
assert.ok(definition && definition.data, '分享有礼页必须注册 Page 实例');

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/share-referral/share-referral'), 'app.json 必须注册分享有礼页');

const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8');
const wxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8');
const js = fs.readFileSync(`${pageRoot}.js`, 'utf8');

assert.ok(wxml.includes('title="分享有礼"') && wxml.includes('back="{{true}}"'), '分享有礼页必须提供标题与返回');
assert.ok(wxml.includes('{{inviteCode}}') && wxml.includes('copyCode'), '分享有礼页必须展示邀请码与复制按钮');
assert.ok(wxml.includes('邀请进度') && wxml.includes('{{invitedCount}}'), '分享有礼页必须展示邀请进度');
assert.ok(wxml.includes('奖励规则') && wxml.includes('wx:for="{{rewards}}"'), '分享有礼页必须渲染奖励规则');
assert.ok(wxml.includes('到账规则') && wxml.includes('{{earningNote}}'), '分享有礼页必须展示到账规则');
assert.ok(js.includes('3 时光币') && js.includes('3 元无门槛券'), '首单奖励必须为 3 时光币 + 3 元无门槛券');
assert.ok(js.includes('社交达人') && js.includes('时光推荐官'), '必须包含社交达人徽章与时光推荐官档位');
assert.ok(js.includes('5%') && js.includes('以后台配置为准'), '推荐官返利必须标注 5% 示例');
assert.ok(wxml.includes('立即邀请好友') && wxml.includes('bindtap="invite"'), '必须提供立即邀请好友按钮');

// 首页加入我们与我的页分享有礼入口
const homeJs = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8');
const homeWxml = fs.readFileSync(path.join(root, 'pages/home/home.wxml'), 'utf8');
assert.ok(
  homeJs.includes('openJoinApply') && homeJs.includes('/pages/role-apply/role-apply'),
  '首页加入我们必须跳转加盟合作页'
);
assert.ok(homeWxml.includes('bindtap="openJoinApply"'), '首页加入我们必须绑定跳转事件');

const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(
  profileJs.includes("id === 'share'") && profileJs.includes('/pages/share-referral/share-referral'),
  '我的页分享有礼必须跳转新页'
);

// 会员权益图标颜色统一为正文灰
const memberSvg = fs.readFileSync(path.join(root, 'assets/icons/lucide/member.svg'), 'utf8');
assert.ok(memberSvg.includes('stroke="#666762"'), '会员权益图标颜色必须与其他功能一致（#666762）');
assert.ok(!memberSvg.includes('stroke="#9B9B96"'), '会员权益图标不得再使用偏灰 #9B9B96');

// 新图标存在
for (const icon of ['award', 'medal']) {
  assert.ok(fs.existsSync(path.join(root, `assets/icons/lucide/${icon}.svg`)), `缺少 Lucide 图标: ${icon}`);
}

// 分享配置
const shareSource = fs.readFileSync(path.join(root, 'utils/share.js'), 'utf8');
assert.ok(
  shareSource.includes("'pages/share-referral/share-referral': '五零时光分享有礼'"),
  '分享有礼页必须配置分享标题'
);

// 设计规范
assert.ok(!wxml.includes('<button'), '分享有礼页不得使用原生 button');
assert.ok(
  wxss.includes('var(--brand-green)') && wxss.includes('var(--radius-md)') && wxss.includes('var(--shadow-card)'),
  '分享有礼页必须遵守设计 token'
);
assert.ok(!/\b\d+px\b/.test(wxss), '分享有礼页 WXSS 不得使用 px');
const colorLiterals = [...wxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)]
  .map(m => m[0])
  .filter(c => c.toUpperCase() !== '#FFFFFF');
assert.deepEqual(colorLiterals, [], '分享有礼页 WXSS 除白色前景外不得写死颜色');

console.log('分享有礼页结构、规则、入口与图标颜色测试通过');
