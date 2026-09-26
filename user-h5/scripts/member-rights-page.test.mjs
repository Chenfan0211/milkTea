import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = {};
let memberRightsDefinition;
let memberDefinition;

globalThis.wx = {
  showShareMenu() {},
  navigateTo() {},
  switchTab() {},
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  }
};
globalThis.getApp = () => ({ globalData: { points: 0 } });

// ---- 会员权益页（我的页入口） ----
globalThis.Page = page => {
  memberRightsDefinition = page;
};
assert.doesNotThrow(
  () => require(path.join(root, 'pages/member-rights/member-rights.js')),
  '会员权益页脚本必须能正常加载'
);
assert.ok(memberRightsDefinition && memberRightsDefinition.data, '会员权益页必须注册 Page 实例');

const rightsWxml = fs.readFileSync(path.join(root, 'pages/member-rights/member-rights.wxml'), 'utf8');
const rightsJson = JSON.parse(fs.readFileSync(path.join(root, 'pages/member-rights/member-rights.json'), 'utf8'));

// ---- 会员专区 Tab ----
globalThis.Page = page => {
  memberDefinition = page;
};
assert.doesNotThrow(() => require(path.join(root, 'pages/member/member.js')), '会员专区脚本必须能正常加载');
const memberWxml = fs.readFileSync(path.join(root, 'pages/member/member.wxml'), 'utf8');
const memberJson = JSON.parse(fs.readFileSync(path.join(root, 'pages/member/member.json'), 'utf8'));
const memberJs = fs.readFileSync(path.join(root, 'pages/member/member.js'), 'utf8');

// ---- 共享会员面板组件 ----
const panelRoot = path.join(root, 'components/member-panel/member-panel');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${panelRoot}.${extension}`), `缺少会员面板组件: member-panel.${extension}`);
}
const panelWxml = fs.readFileSync(`${panelRoot}.wxml`, 'utf8');
const panelWxss = fs.readFileSync(`${panelRoot}.wxss`, 'utf8');

// 两页都必须通过同一组件渲染，且含等级说明入口
for (const [label, wxml, json] of [
  ['会员权益页', rightsWxml, rightsJson],
  ['会员专区', memberWxml, memberJson]
]) {
  assert.ok(wxml.includes('<member-panel'), `${label}必须使用会员面板组件`);
  assert.ok(json.usingComponents && json.usingComponents['member-panel'], `${label}必须注册会员面板组件`);
  assert.ok(wxml.includes('bind:openrules="openLevelRules"'), `${label}必须接通等级说明入口`);
}
assert.ok(panelWxml.includes('等级说明'), '会员面板必须包含等级说明入口');
assert.ok(panelWxml.includes('当前等级'), '会员卡必须标记当前等级');
assert.ok(panelWxss.includes('.member-card__badge') && panelWxss.includes('background: var(--brand-green)') && panelWxss.includes('font-weight: 600'), '当前等级角标必须使用品牌绿高对比样式');
assert.ok(panelWxml.includes('永久有效'), '会员卡必须展示永久有效');
assert.ok(panelWxml.includes('member-card__badge--permanent'), '永久有效必须改为胶囊标签');
assert.ok(panelWxml.includes('wx:if="{{item.isReached}}"'), '永久有效只应在已达成等级显示');
assert.ok(!panelWxml.includes('member-card__foot'), '永久有效不得再使用整条底部深色背景');
assert.ok(panelWxml.includes('五零时光') && panelWxml.includes('时光'), '会员卡必须使用自有品牌字标与印章');
assert.ok(panelWxml.includes('member-card__crown'), '会员卡必须包含皇冠勋章图标');
assert.ok(panelWxml.includes("item.isReached ? 'crown-gold' : 'member'"), '锁定等级必须替换为灰色皇冠');
assert.ok(panelWxml.includes('is-active') && panelWxml.includes('is-locked'), '会员卡必须渲染选中与锁定状态');
assert.ok(panelWxml.includes('lock-muted.svg'), '未达成等级卡片必须使用灰色锁图标');
assert.ok(!panelWxml.includes('真茶屋'), '会员卡不得照搬参考图品牌');

// 横向卡组与进度轴
assert.ok(panelWxml.includes('class="member-cards"') && panelWxml.includes('scroll-x') && panelWxml.includes('show-scrollbar="{{false}}"'), '会员卡必须使用 scroll-view 横向滑动且关闭滚动条');
assert.ok(panelWxml.includes('member-cards__track'), '会员卡必须为横向滑动卡组');
const memberCardsRule = panelWxss.match(/\.member-cards\s*\{[\s\S]*?\}/)?.[0] || '';
assert.ok(
  memberCardsRule.includes('width: 100%') &&
    memberCardsRule.includes('white-space: nowrap'),
  '会员卡横向容器必须为全宽且不换行'
);
assert.ok(panelWxml.includes('wx:for="{{axis}}"'), '会员面板必须渲染成长值进度轴');
assert.ok(panelWxml.includes('member-axis__dot'), '进度轴必须绘制节点');
assert.ok(panelWxml.includes('当前成长值'), '会员面板必须展示当前成长值');

// 特权区
assert.ok(panelWxml.includes('会员特权'), '会员面板必须包含会员特权区块');
assert.ok(panelWxml.includes('wx:for="{{privileges}}"'), '会员面板必须渲染特权列表');
assert.ok(
  panelWxml.includes('privilege-item__icon') && panelWxml.includes('privilege-item__count'),
  '特权项必须包含图标与数量角标'
);
assert.ok(
  panelWxml.includes('activeReached') && panelWxml.includes('未达成等级，权益预览'),
  '未达成等级特权区必须显示预览提示与灰态'
);
assert.ok(panelWxml.includes('item.mutedIcon'), '未达成等级特权图标必须切换到灰色图标');

// 页面必须移除旧的成长值渠道与时光币规则区块
for (const [label, wxml] of [
  ['会员权益页', rightsWxml],
  ['会员专区', memberWxml]
]) {
  assert.ok(!wxml.includes('消费杯数成长值'), `${label}不得展示消费杯数渠道`);
  assert.ok(!wxml.includes('pointsEarningRules'), `${label}不得展示时光币获取方式区块`);
}

// 我的页入口
const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8');
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(
  profileWxml.includes('bindtap="openMemberRights"') && profileWxml.includes('aria-label="查看会员权益"'),
  '我的页 VIP 区域必须可点击并有无障碍标签'
);
assert.ok(
  profileJs.includes('openMemberRights') && profileJs.includes('/pages/member-rights/member-rights'),
  '我的页必须跳转会员权益页'
);

// 页面注册
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/member-rights/member-rights'), 'app.json 必须注册会员权益页');
assert.ok(appJson.pages.includes('pages/member-level-rules/member-level-rules'), 'app.json 必须注册等级说明页');

// ---- 等级数据与成长值逻辑 ----
// 会员等级数据已迁移到数据库（server/src/main/resources/db/migration/V3__seed_base.sql）。
// 这里从 seed 解析出与前端一致的等级结构，供断言使用。
function loadMemberLevelsFromSeed() {
  const sql = fs.readFileSync(
    path.join(root, '..', 'server/src/main/resources/db/migration/V3__seed_base.sql'),
    'utf8'
  );
  const block = sql.match(/INSERT INTO member_level[\s\S]*?;/);
  assert.ok(block, 'V3 seed 必须包含 member_level 初始化');
  return [...block[0].matchAll(/\('(Lv\d+)', '([^']+)', (\d+), '([^']+)', '([\s\S]*?)', (\d+)\)/g)].map(m => ({
    level: m[1],
    name: m[2],
    // seed 金额单位为「分」，前端按「元」比较
    amountTarget: Math.round(Number(m[3]) / 100),
    discount: m[4],
    benefits: JSON.parse(m[5]),
    sort: Number(m[6])
  }));
}

const memberLevels = loadMemberLevelsFromSeed();
function memberCondition(item) {
  return item.amountTarget === 0 ? '注册即得' : '累计消费满' + item.amountTarget + '元';
}
assert.equal(memberLevels.length, 3, '会员等级必须为三档');
assert.equal(memberLevels.map(item => item.name).join('|'), '时光卡|星享卡|挚友卡', '等级名称必须完整');
assert.equal(
  memberLevels.map(item => memberCondition(item)).join('|'),
  '注册即得|累计消费满300元|累计消费满2000元',
  '升级条件必须只按金额且完整'
);
assert.equal(memberLevels.map(item => item.discount).join('|'), '8折|7折|6折', '折扣必须完整');
for (const level of memberLevels) {
  assert.ok(Array.isArray(level.benefits) && level.benefits.length, `${level.level} 必须包含结构化权益`);
  for (const benefit of level.benefits) {
    assert.ok(
      benefit.icon && typeof benefit.text === 'string' && benefit.text,
      `${level.level} 权益必须包含图标与文案`
    );
  }
}
assert.ok(!memberLevels.some(item => item.cupTarget !== undefined), '等级数据不得再包含杯数阈值');
assert.ok(!memberLevels.some(item => item.growthTarget !== undefined), '等级数据不得再包含冗余成长值阈值');
const lv3Benefits = memberLevels.find(item => item.level === 'Lv3').benefits.map(item => item.text);
assert.ok(
  lv3Benefits.includes('专属优惠券') && lv3Benefits.includes('时光币1.5倍') && lv3Benefits.includes('生日免费饮品'),
  'Lv3 核心权益必须完整'
);
assert.ok(!lv3Benefits.some(text => text.includes('3张')), 'Lv3 不得叠加 Lv2 的 3 张券');

// ===== 会员价必须按等级折扣计算（回归：代码/名称口径不一致导致恒为原价）=====
// 历史 bug：后端 app_user.vip_level 存的是等级代码（Lv1），
// 而 getUserLevel 只按 name（时光卡）匹配，永远命中兜底等级 discount=1，
// 表现为「等级显示正常、会员价却从不打折」。此处同时锁死两种写法。
const { calcMemberPrice, getUserLevel } = require(path.join(root, 'utils/member-level.js'));
const { buildLevelMeta, setMemberLevels } = require(path.join(root, 'utils/member-level.js'));
// 等级数据来自数据库 seed：同步注入后再做成长值判定
setMemberLevels(memberLevels.map(item => ({
  levelCode: item.level,
  name: item.name,
  amountTarget: item.amountTarget * 100,
  discount: item.discount,
  benefits: JSON.stringify(item.benefits)
})));
const cases = [
  [{ totalSpend: 10 }, '时光卡'],
  [{ totalSpend: 320 }, '星享卡'],
  [{ totalSpend: 2000 }, '挚友卡'],
  [{ totalSpend: 0 }, '时光卡']
];
for (const [profile, expected] of cases) {
  const meta = buildLevelMeta(profile);
  assert.equal(meta.currentName, expected, `实付累计 ${profile.totalSpend} 元应判定为 ${expected}`);
  assert.equal(meta.currentGrowth, Math.floor(profile.totalSpend), '成长值必须等于累计实付金额向下取整');
}
// 等级代码与名称都必须能取到正确折扣（不能落到兜底 discount=1）
assert.equal(getUserLevel('Lv1').name, '时光卡', '传等级代码 Lv1 必须匹配到时光卡');
assert.equal(getUserLevel('时光卡').name, '时光卡', '传等级名称时光卡同样必须命中');
assert.equal(getUserLevel('Lv3').name, '挚友卡', '传等级代码 Lv3 必须匹配到挚友卡');

// 会员价 = 原价 × 等级折扣（seed：时光卡8折 / 星享卡7折 / 挚友卡6折）
assert.equal(calcMemberPrice(18, 'Lv1'), 14.4, 'Lv1(8折) 18 元会员价必须为 14.4');
assert.equal(calcMemberPrice(18, '时光卡'), 14.4, '按名称传等级时结果必须一致');
assert.equal(calcMemberPrice(18, 'Lv2'), 12.6, 'Lv2(7折) 必须取到 7 折而非兜底');
assert.equal(calcMemberPrice(18, 'Lv3'), 10.8, 'Lv3(6折) 必须取到 6 折而非兜底');

// 边界：等级缺失时不得误打折，也不得产生 NaN
assert.equal(calcMemberPrice(18, ''), 18, '无等级时必须回退原价');
assert.equal(calcMemberPrice(18, 'Lv9'), 18, '未知等级必须回退原价');
assert.equal(calcMemberPrice(0, 'Lv1'), 0, '价格为 0 时必须为 0');
const metaLv2 = buildLevelMeta({ totalSpend: 320 });
assert.equal(metaLv2.progressTarget, 2000, 'Lv2 进度目标必须为下一档 2000');
assert.equal(metaLv2.progressLabel, '再消费 1680 元升级', 'Lv2 升级提示必须为金额差');
assert.equal(metaLv2.levels.length, 3, '等级列表必须为三档');
assert.equal(metaLv2.axis.map(item => item.value).join(','), '0,300,2000', '进度轴必须为 0/300/2000 三节点');
assert.ok(metaLv2.privileges.length > 0 && metaLv2.privilegesTitle.includes('星享卡'), '当前档特权必须可渲染');
assert.ok(
  metaLv2.privileges.every(item => item.mutedIcon && item.mutedIcon.endsWith('-muted')),
  '每档权益必须包含灰色图标变体'
);

// 页面数据装配
function createPage(definition) {
  const instance = Object.assign({}, definition);
  instance.data = JSON.parse(JSON.stringify(definition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}
const { saveUserProfile } = require(path.join(root, 'utils/user-profile.js'));
saveUserProfile({ totalSpend: 320, points: 0 });
const rightsPage = createPage(memberRightsDefinition);
// 等级数据改为接口异步拉取，onShow 需等待 Promise 完成后再断言
await memberRightsDefinition.onShow.call(rightsPage);
assert.equal(rightsPage.data.currentLevel, 'Lv2', '会员权益页必须同步当前等级');
assert.equal(rightsPage.data.levels.length, 3, '会员权益页必须下发三档等级');
assert.equal(rightsPage.data.axis.length, 3, '会员权益页必须下发三个进度节点');

const memberPage = createPage(memberDefinition);
await memberDefinition.onShow.call(memberPage);
assert.equal(memberPage.data.currentLevel, 'Lv2', '会员专区必须同步当前等级');
assert.ok(memberJs.includes('selected: 2'), '会员专区必须同步 Tab 选中态');
assert.ok(!memberJs.includes('pointsEarningRules'), '会员专区不得再引用时光币规则数据');

// 设计规范
assert.ok(!panelWxml.includes('<button'), '会员面板不得使用原生 button');
assert.ok(
  panelWxss.includes('var(--brand-green)') &&
    panelWxss.includes('var(--radius-lg)') &&
    panelWxss.includes('var(--shadow-card)'),
  '会员面板必须遵守设计 token'
);
assert.ok(
  panelWxss.includes('.member-card.is-locked') && panelWxss.includes('.member-card.is-active'),
  '会员面板 WXSS 必须包含锁定与选中态规则'
);
assert.ok(panelWxss.includes('height: 320rpx'), '会员卡高度必须增加到 320rpx');
assert.ok(!panelWxss.includes('translateY'), '选中态不得位移以避免描边被裁切');
assert.ok(
  panelWxss.includes('.member-privilege__preview') && panelWxss.includes('.privilege-item.is-muted'),
  '会员面板 WXSS 必须包含特权预览与灰态规则'
);
assert.ok(
  panelWxss.includes('color: var(--text-main)') && panelWxss.includes('color: var(--dark-fill)'),
  '卡片等级与品牌文字必须使用深色提高对比度'
);
assert.ok(
  panelWxss.includes('.member-card__badge--permanent') &&
    panelWxss.includes('color: #ffffff') &&
    panelWxss.includes('background: var(--brand-green)') &&
    panelWxss.includes('font-weight: 600'),
  '永久有效胶囊必须使用品牌绿高对比样式'
);
assert.ok(!/\b\d+px\b/.test(panelWxss), '会员面板 WXSS 不得使用 px');

console.log('会员专区样式、等级说明入口、成长值逻辑与特权渲染测试通过');
