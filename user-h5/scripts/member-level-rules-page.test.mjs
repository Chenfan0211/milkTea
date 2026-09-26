import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageRoot = path.join(root, 'pages/member-level-rules/member-level-rules');

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少等级说明页文件: member-level-rules.${extension}`);
}

let definition;
const storage = {};
globalThis.wx = {
  showShareMenu() {},
  navigateTo() {},
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

assert.doesNotThrow(() => require(`${pageRoot}.js`), '等级说明页脚本必须能正常加载');
assert.ok(definition && definition.data, '等级说明页必须注册 Page 实例');

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/member-level-rules/member-level-rules'), 'app.json 必须注册等级说明页');

const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8');
const wxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8');

assert.ok(wxml.includes('title="等级说明"') && wxml.includes('back="{{true}}"'), '等级说明页必须提供标题与返回');
assert.ok(
  wxml.includes('wx:for="{{levels}}"') && wxml.includes('{{item.condition}}') && wxml.includes('{{item.discountText}}'),
  '等级说明页必须渲染等级、条件与折扣'
);
assert.ok(
  wxml.includes('升级条件') && wxml.includes('会员价折扣') && wxml.includes('核心权益'),
  '等级说明页必须包含条件、折扣与权益分区'
);
assert.ok(wxml.includes('wx:for="{{item.benefits}}"'), '等级说明页必须渲染每档核心权益');
assert.ok(wxml.includes('{{index === currentIndex'), '等级说明页必须标记当前等级');
assert.ok(wxml.includes('成长值') && wxml.includes('时光币'), '等级说明页必须解释成长值与时光币口径');

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
assert.equal(memberLevels.length, 3, '等级说明必须覆盖三档');
assert.equal(memberLevels.map(item => item.level).join('|'), 'Lv1|Lv2|Lv3', '等级说明必须按顺序列出 Lv1-Lv3');
assert.equal(memberLevels.map(item => item.name).join('|'), '时光卡|星享卡|挚友卡', '等级说明必须包含三档名称');
assert.equal(memberLevels.map(item => item.discount).join('|'), '8折|7折|6折', '等级说明必须包含三档折扣');
for (const level of memberLevels) {
  assert.ok(!String(memberCondition(level)).includes('杯'), `${level.level} 升级条件不得包含杯数`);
  assert.ok(Array.isArray(level.benefits) && level.benefits.length > 0, `${level.level} 必须有核心权益`);
}

// 两处入口都必须能跳转等级说明页
for (const page of ['member/member', 'member-rights/member-rights']) {
  const js = fs.readFileSync(path.join(root, `pages/${page}.js`), 'utf8');
  const wxmlOfPage = fs.readFileSync(path.join(root, `pages/${page}.wxml`), 'utf8');
  assert.ok(js.includes('/pages/member-level-rules/member-level-rules'), `${page} 必须跳转等级说明页`);
  assert.ok(wxmlOfPage.includes('bind:openrules'), `${page} 必须接通等级说明入口`);
}

const shareSource = fs.readFileSync(path.join(root, 'utils/share.js'), 'utf8');
assert.ok(shareSource.includes("'pages/member-level-rules/member-level-rules'"), '等级说明页必须配置分享标题');

assert.ok(!wxml.includes('<button'), '等级说明页不得使用原生 button');
assert.ok(
  wxss.includes('var(--brand-green)') && wxss.includes('var(--radius-lg)') && wxss.includes('var(--shadow-card)'),
  '等级说明页必须遵守设计 token'
);
assert.ok(!/\b\d+px\b/.test(wxss), '等级说明页 WXSS 不得使用 px');

console.log('等级说明页结构、三档条件、折扣与权益测试通过');
