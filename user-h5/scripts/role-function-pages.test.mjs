import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

for (const page of ['role-verify', 'role-income', 'role-withdraw']) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(fs.existsSync(path.join(root, `pages/${page}/${page}.${extension}`)), `missing ${page}.${extension}`);
  }
}

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
for (const page of [
  'pages/role-verify/role-verify',
  'pages/role-income/role-income',
  'pages/role-withdraw/role-withdraw'
]) {
  assert.ok(appJson.pages.includes(page), `${page} must be registered`);
}

const {
  getVerifyData,
  getIncomeData,
  getWithdrawData,
  getDashboard,
  getBoundStore
} = require(path.join(root, 'utils/roles.js'));

// 权限：未开通/无角色返回 null
assert.equal(getVerifyData('unknown'), null, 'getVerifyData must return null for unknown role');
assert.equal(getIncomeData('unknown'), null, 'getIncomeData must return null for unknown role');
assert.equal(getWithdrawData('unknown'), null, 'getWithdrawData must return null for unknown role');

// 演示数据：active 角色返回字段完整
const verify = getVerifyData('store');
assert.ok(verify && verify.pool.length >= 3 && verify.records, 'store verify data must exist');
assert.ok(
  verify.pool.every(item => item.pickupCode && item.orderNo && item.product && item.amount),
  'verify pool fields must be complete'
);

const income = getIncomeData('store');
assert.ok(
  income && income.title && income.trend.length >= 3 && income.records.length >= 2,
  'store income data must exist'
);
assert.ok(
  income.trend.every(item => item.label && item.value),
  'income trend fields must be complete'
);

const withdraw = getWithdrawData('store');
assert.ok(
  withdraw && withdraw.balance && withdraw.pending && withdraw.deposit,
  'withdraw data must contain balance/pending/deposit'
);

// 分享：三页均为私密且有标题
const { getShareTitle, isPrivatePage, PAGE_SHARE_TITLES } = require(path.join(root, 'utils/share.js'));
for (const route of [
  'pages/role-verify/role-verify',
  'pages/role-income/role-income',
  'pages/role-withdraw/role-withdraw'
]) {
  assert.ok(isPrivatePage(route), `${route} must be private`);
  assert.ok(PAGE_SHARE_TITLES[route], `${route} must define a title`);
  assert.notEqual(getShareTitle(route), '五零时光新中式养生茶饮', `${route} must not fall back to default title`);
}

// 路由：profile 按 actionId 跳转三个功能页，不再进工作台
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(profileJs.includes('/pages/role-verify/role-verify'), 'profile must route verify');
assert.ok(profileJs.includes('/pages/role-income/role-income'), 'profile must route income');
assert.ok(profileJs.includes('/pages/role-withdraw/role-withdraw'), 'profile must route withdraw');

// 「我的」页余额改储值余额
assert.ok(profileJs.includes("label: '储值余额'"), 'profile balance label must be 储值余额');
assert.ok(profileJs.includes('/pages/stored-value/stored-value'), 'balance tap must still navigate to stored-value');

// role-workbench handleAction 同样跳转
const workbenchJs = fs.readFileSync(path.join(root, 'pages/role-workbench/role-workbench.js'), 'utf8');
assert.ok(workbenchJs.includes('/pages/role-verify/role-verify'), 'workbench must route verify');

// 核销页 WXML 含扫码/输码/确认核销
const verifyWxml = fs.readFileSync(path.join(root, 'pages/role-verify/role-verify.wxml'), 'utf8');
assert.ok(
  verifyWxml.includes('handleScan') && verifyWxml.includes('handleQuery'),
  'verify page must support scan and query'
);
assert.ok(verifyWxml.includes('scan-line.svg'), 'verify page must use scan icon');

// 核销页接入兑换核销
const verifyJs = fs.readFileSync(path.join(root, 'pages/role-verify/role-verify.js'), 'utf8');
assert.ok(verifyJs.includes('verifyExchange'), 'verify page must support exchange redemption');
assert.ok(
  verifyJs.includes("mode: 'order'") && verifyJs.includes('switchMode'),
  'verify page must separate order vs exchange modes'
);
assert.ok(
  verifyJs.includes('verifyOrderByCode') && verifyJs.includes('verifyExchangeByCode'),
  'verify page must have order and exchange verify handlers'
);
assert.ok(
  verifyWxml.includes('点单核销') && verifyWxml.includes('兑换核销'),
  'verify page must show order and exchange tabs'
);
assert.ok(
  verifyWxml.includes('record-row__image') &&
    verifyWxml.includes('订单号 {{item.orderNo}}') &&
    verifyWxml.includes('核销时间 {{item.time}}'),
  'verify records must show product image, order no, and exact time'
);
assert.ok(
  verify.records.every(item => item.orderNo && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(item.time) && item.image),
  'verified mock records must contain order no, exact time, and image'
);
assert.ok(verifyWxml.includes("type === 'exchange'"), 'verify records must tag exchange vs order type');

// ===== 独立授权登录页（替代 login-sheet 弹层）=====
const authDir = path.join(root, 'pages/auth-login');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(authDir, `auth-login.${extension}`)),
    `missing auth-login.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/auth-login/auth-login'),
  'auth-login must be registered'
);

const authWxml = fs.readFileSync(path.join(authDir, 'auth-login.wxml'), 'utf8');
const authWxss = fs.readFileSync(path.join(authDir, 'auth-login.wxss'), 'utf8');
const authJs = fs.readFileSync(path.join(authDir, 'auth-login.js'), 'utf8');

// 品牌区：文字品牌名 + 副标题
assert.ok(
  authWxml.includes('五零时光') && authWxml.includes('新中式养生茶饮'),
  'auth page must show the brand name and slogan'
);

// 主操作：微信手机号快捷登录（唯一主按钮）
assert.ok(
  authWxml.includes('getPhoneNumber') && authWxml.includes('微信手机号快捷登录'),
  'auth page must offer WeChat phone login'
);

// 降级路径默认折叠
assert.ok(
  authWxml.includes('fallbackVisible') && authJs.includes('toggleFallback'),
  'auth page must collapse the SMS fallback by default'
);
assert.ok(
  /fallbackVisible:\s*false/.test(authJs),
  'the SMS fallback must start collapsed'
);
assert.ok(
  authWxml.includes('wx:if="{{fallbackVisible}}"'),
  'the SMS fallback must be rendered conditionally'
);

// 手机号 / 验证码校验
assert.ok(
  /function isValidPhone\(value\)/.test(authJs) && authJs.includes('/^1[3-9]\\d{9}$/'),
  'auth page must validate the phone number'
);
assert.ok(
  /function isValidCode\(value\)/.test(authJs) && authJs.includes('/^\\d{6}$/'),
  'auth page must validate the SMS code'
);
assert.ok(
  authJs.includes('canSubmit') && authWxml.includes('auth-submit {{canSubmit'),
  'auth page must reflect the submit state'
);

// 协议提示
assert.ok(
  authWxml.includes('登录即代表同意') &&
    authWxml.includes('《用户协议》') &&
    authWxml.includes('《隐私政策》'),
  'auth page must show the agreement notice'
);

// 暂不登录
assert.ok(
  authWxml.includes('handleSkip') && authWxml.includes('暂不登录'),
  'auth page must offer a skip action'
);

// 样式合规
assert.ok(
  /flex:\s*1[\s\S]*?min-height:\s*0/.test(authWxss) &&
    /\.auth-scroll\s*\{[^}]*flex:\s*1/.test(authWxss),
  'auth page scroll area must keep flex:1 and min-height:0'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(authWxss),
  'auth page WXSS must use design tokens only'
);
// 主按钮与降级提交不得同为实心绿底（避免出现两个同级主操作）
const primaryRule = (authWxss.match(/\.auth-primary\s*\{([^}]*)\}/) || [])[1] || '';
assert.ok(
  primaryRule.indexOf('var(--brand-green)') >= 0,
  'the WeChat login button must be the single primary action'
);

// ===== 授权跳转机制 =====
const guardSource = fs.readFileSync(path.join(root, 'utils/login-guard.js'), 'utf8');
assert.ok(
  guardSource.includes("'/pages/auth-login/auth-login'"),
  'login guard must navigate to the standalone auth page'
);
assert.ok(
  guardSource.includes('wx.navigateTo'),
  'login guard must navigate instead of opening an in-page sheet'
);
assert.ok(
  !guardSource.includes('current.openLoginSheet'),
  'login guard must no longer delegate to an in-page sheet'
);
// 内存中的 pendingAction 必须在跳转后仍可续跑
assert.ok(
  /let pendingAction = null/.test(guardSource) &&
    guardSource.includes('function flushPendingAction()') &&
    guardSource.includes('function clearPendingAction()'),
  'pending action must stay in module memory so it survives navigation'
);
// 重复跳转去重
assert.ok(
  guardSource.includes('function hasAuthPage()') && guardSource.includes('pages/auth-login/auth-login'),
  'login guard must dedupe repeated navigations to the auth page'
);
// 页面栈溢出兜底
assert.ok(
  guardSource.includes('MAX_PAGE_STACK') && guardSource.includes('wx.redirectTo'),
  'login guard must fall back to redirectTo when the page stack is full'
);
// 跳转失败不得静默
assert.ok(
  /navigateTo\(\{[\s\S]*?fail\(\)[\s\S]*?toast/.test(guardSource),
  'a failed navigation must surface a toast rather than fail silently'
);

// 授权页：未完成时清理待执行动作，完成后续跑
assert.ok(
  authJs.includes('onUnload') && authJs.includes('guard.clearPendingAction()'),
  'auth page must clear the pending action when the user leaves early'
);
assert.ok(
  authJs.includes('guard.flushPendingAction()'),
  'auth page must resume the pending action after binding'
);
assert.ok(
  /this\.completed = false/.test(authJs) && /this\.completed = true/.test(authJs),
  'auth page must track completion to decide whether to clear the pending action'
);

// 授权页不得离线在构建：7 个原弹层使用页必须已完成迁移
for (const page of [
  'order-confirm',
  'stored-value',
  'points-exchange',
  'gift-card-purchase',
  'role-apply',
  'role-withdraw',
  'profile'
]) {
  const js = fs.readFileSync(path.join(root, `pages/${page}/${page}.js`), 'utf8');
  const wxml = fs.readFileSync(path.join(root, `pages/${page}/${page}.wxml`), 'utf8');
  const json = fs.readFileSync(path.join(root, `pages/${page}/${page}.json`), 'utf8');
  assert.ok(!/loginSheet/.test(js), `${page} must not keep login-sheet state`);
  assert.ok(!/login-sheet/.test(wxml), `${page} must not render login-sheet`);
  assert.ok(!/login-sheet/.test(json), `${page} must not register login-sheet`);
}
// 组件目录必须已移除
assert.ok(
  !fs.existsSync(path.join(root, 'components/login-sheet')),
  'the legacy login-sheet component must be removed'
);
// 授权页列入私密名单，不参与分享
const shareSource = fs.readFileSync(path.join(root, 'utils/share.js'), 'utf8');
assert.ok(
  shareSource.includes("'pages/auth-login/auth-login'"),
  'auth page must be registered as private'
);
assert.ok(isPrivatePage('pages/auth-login/auth-login'), 'auth page must not be shareable');

// ===== 协议独立页面（用户协议 / 隐私政策）=====
const legalDir = path.join(root, 'pages/legal');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(path.join(legalDir, `legal.${extension}`)), `missing legal.${extension}`);
}
assert.ok(appJson.pages.includes('pages/legal/legal'), 'legal page must be registered');

const legalWxml = fs.readFileSync(path.join(legalDir, 'legal.wxml'), 'utf8');
const legalWxss = fs.readFileSync(path.join(legalDir, 'legal.wxss'), 'utf8');
const legalJs = fs.readFileSync(path.join(legalDir, 'legal.js'), 'utf8');

// 单页面按 type 取文档
assert.ok(
  legalJs.includes('getLegalDoc') && legalJs.includes("options.type"),
  'legal page must resolve the document from the type query'
);
assert.ok(
  legalWxml.includes('doc.sections') &&
    legalWxml.includes('item.clauses') &&
    legalWxml.includes('doc.footnotes'),
  'legal page must render sections, clauses and footnotes'
);
assert.ok(
  legalWxml.includes('{{doc.updatedAt}}') && legalWxml.includes('{{doc.subject}}'),
  'legal page must render the effective date and subject'
);
// 未知 type 不得白屏
assert.ok(
  legalWxml.includes('wx:if="{{!doc}}"') && legalWxml.includes('empty-state'),
  'legal page must fall back to an empty state for unknown types'
);
// 骨架与 token
assert.ok(
  /\.legal-scroll\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/.test(legalWxss),
  'legal page scroll area must keep flex:1 and min-height:0'
);
assert.ok(!/#[0-9A-Fa-f]{3,8}\b/.test(legalWxss), 'legal page WXSS must use design tokens only');

// 文档数据：两份齐全、结构完整、未知类型返回 null
const { getLegalDoc, getLegalTypes } = require(path.join(root, 'data/legal.js'));
assert.deepEqual(getLegalTypes().sort(), ['agreement', 'privacy'], 'two legal docs must exist');
assert.equal(getLegalDoc('unknown'), null, 'unknown legal type must resolve to null');

for (const type of ['agreement', 'privacy']) {
  const doc = getLegalDoc(type);
  assert.ok(doc && doc.title && doc.updatedAt && doc.subject && doc.lead, `${type} doc must be complete`);
  assert.ok(doc.sections.length >= 8, `${type} must cover at least eight sections`);
  assert.ok(
    doc.sections.every(section => section.id && section.title && section.clauses.length),
    `${type} sections must be complete`
  );
  assert.ok(
    doc.sections.every(section => section.clauses.every(clause => typeof clause === 'string' && clause.length > 0)),
    `${type} clauses must be non-empty strings`
  );
  assert.ok(doc.footnotes.length >= 1, `${type} must carry footnotes`);
}
// 返回副本，调用方修改不得污染源数据
const legalCopy = getLegalDoc('agreement');
legalCopy.sections[0].clauses.push('污染测试');
assert.ok(
  getLegalDoc('agreement').sections[0].clauses.indexOf('污染测试') === -1,
  'legal doc getter must return a defensive copy'
);
// 合规必备章节
const privacyClauses = getLegalDoc('privacy')
  .sections.map(section => section.title)
  .join(' ');
assert.ok(
  privacyClauses.includes('收集') &&
    privacyClauses.includes('使用') &&
    privacyClauses.includes('共享') &&
    privacyClauses.includes('您的权利') &&
    privacyClauses.includes('未成年人'),
  'privacy policy must cover collection, usage, sharing, user rights and minors'
);
const agreementClauses = getLegalDoc('agreement')
  .sections.map(section => section.title)
  .join(' ');
assert.ok(
  agreementClauses.includes('账号') &&
    agreementClauses.includes('免责') &&
    agreementClauses.includes('争议解决'),
  'user agreement must cover accounts, disclaimer and dispute resolution'
);

// 授权页协议链接必须跳转到独立页面，且携带类型
const authWxmlForLegal = fs.readFileSync(path.join(root, 'pages/auth-login/auth-login.wxml'), 'utf8');
const authJsForLegal = fs.readFileSync(path.join(root, 'pages/auth-login/auth-login.js'), 'utf8');
assert.ok(
  authWxmlForLegal.includes('data-type="agreement"') && authWxmlForLegal.includes('data-type="privacy"'),
  'auth page must tag both agreement links with a type'
);
assert.ok(
  authJsForLegal.includes('/pages/legal/legal?type='),
  'auth page must navigate to the legal page'
);
assert.ok(
  !/openAgreement[\s\S]{0,200}showToast/.test(authJsForLegal),
  'auth page must not fall back to a toast for agreements'
);
// 协议页不参与分享
assert.ok(isPrivatePage('pages/legal/legal'), 'legal page must be private');

// ===== 客服中心 =====
const serviceDir = path.join(root, 'pages/service');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(path.join(serviceDir, `service.${extension}`)), `missing service.${extension}`);
}
assert.ok(appJson.pages.includes('pages/service/service'), 'service page must be registered');

const serviceWxml = fs.readFileSync(path.join(serviceDir, 'service.wxml'), 'utf8');
const serviceWxss = fs.readFileSync(path.join(serviceDir, 'service.wxss'), 'utf8');
const serviceJs = fs.readFileSync(path.join(serviceDir, 'service.js'), 'utf8');

// profile 入口必须跳到客服中心，而不是落到兜底 Toast
const profileScript = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(
  profileScript.includes("id === 'service'") &&
    profileScript.includes("'/pages/service/service'"),
  'profile must route the service entry to the service page'
);

// 微信原生客服会话：必须用 button open-type="contact"（原生 button 的合规例外）
assert.ok(
  serviceWxml.includes('open-type="contact"'),
  'the online service entry must use the native contact capability'
);
assert.ok(
  /<button[^>]*open-type="contact"/.test(serviceWxml),
  'open-type="contact" must sit on a native button element'
);
assert.ok(
  serviceWxss.includes('.service-hero__button::after') && serviceWxss.includes('border: none'),
  'the native contact button must reset its default ::after border'
);

// 客服信息 / 常见问题 / 门店电话
const serviceData = require(path.join(root, 'data/service.js'));
const info = serviceData.getServiceInfo();
const faqs = serviceData.getServiceFaqs();
const serviceStores = serviceData.getServiceStores();
assert.ok(info.hotline && info.serviceHours && info.onlineNote, 'service info must be complete');
assert.ok(faqs.length >= 4, 'at least four FAQs must be provided');
assert.ok(
  faqs.every(item => item.id && item.question && item.answer),
  'FAQ entries must be complete'
);
assert.ok(serviceStores.length > 0, 'store contacts must be provided');
assert.ok(
  serviceStores.every(item => item.id && item.name && item.phone),
  'store contacts must carry name and phone'
);
const catalogStores = require(path.join(root, 'data/mock.js')).stores;
assert.ok(
  serviceStores.every(item => catalogStores.some(store => store.id === item.id)),
  'store contacts must come from the real store catalog'
);
// 返回副本，调用方修改不得污染源数据
const faqCopy = serviceData.getServiceFaqs();
faqCopy[0].question = '污染测试';
assert.ok(
  serviceData.getServiceFaqs()[0].question !== '污染测试',
  'FAQ getter must return a defensive copy'
);

// 页面渲染与交互
assert.ok(
  serviceWxml.includes('{{info.hotline}}') &&
    serviceWxml.includes('{{info.serviceHours}}'),
  'service page must render the hotline and hours'
);
assert.ok(
  serviceWxml.includes('toggleFaq') && serviceWxml.includes('expandedFaqId'),
  'service page must offer collapsible FAQs'
);
assert.ok(
  serviceJs.includes("this.data.expandedFaqId === id ? '' : id"),
  'tapping an open FAQ must collapse it again'
);
assert.ok(
  serviceWxml.includes('callPhone') && serviceJs.includes('wx.makePhoneCall'),
  'phone entries must dial through makePhoneCall'
);
assert.ok(
  /const phone = String\([^)]*\)\.trim\(\)/.test(serviceJs) && serviceJs.includes('暂无联系电话'),
  'dialling must guard against an empty phone number'
);

// 骨架与 token
assert.ok(
  /\.service-scroll\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/.test(serviceWxss),
  'service page scroll area must keep flex:1 and min-height:0'
);
assert.ok(!/#[0-9A-Fa-f]{3,8}\b/.test(serviceWxss), 'service page WXSS must use design tokens only');
// 客服页不参与分享
assert.ok(isPrivatePage('pages/service/service'), 'service page must be private');

// ===== 设计 token 合规（全量排查后固化）=====
// 字号必须取自 token 档位；仅允许验收测试/参考图已固化的字面量。
const FONT_TOKENS = ['--font-caption', '--font-sm', '--font-base', '--font-md', '--font-lg', '--font-xl', '--font-display'];
const TOKEN_FONT_RPX = ['20rpx', '24rpx', '28rpx', '30rpx', '34rpx', '38rpx', '48rpx'];
// 已被验收测试显式固化的字号字面量（design-system 档位豁免）
const FROZEN_FONT_RPX = ['18rpx'];

for (const page of appJson.pages) {
  const wxssPath = path.join(root, `${page}.wxss`);
  if (!fs.existsSync(wxssPath)) continue;
  const wxss = fs.readFileSync(wxssPath, 'utf8');
  for (const match of wxss.matchAll(/font-size:\s*(\d+)rpx/g)) {
    const literal = match[1] + 'rpx';
    assert.ok(
      TOKEN_FONT_RPX.indexOf(literal) >= 0 || FROZEN_FONT_RPX.indexOf(literal) >= 0,
      `${page} 字号 ${literal} 不在 token 档位内，也未登记为豁免值`
    );
  }
}

// 签到页的连续天数必须走 token，不得写死 40rpx
const signinWxss = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.wxss'), 'utf8');
const signinDaysRule = (signinWxss.match(/\.signin-card__days\s*\{([^}]*)\}/) || [])[1] || '';
assert.ok(
  signinDaysRule.indexOf('font-size: var(--font-xl)') >= 0,
  '签到天数必须使用字号 token，不得写死 40rpx'
);
assert.ok(
  signinDaysRule.indexOf('40rpx') === -1,
  '签到天数不得回退为 40rpx 字面量'
);

// 点单页商品区分组上边距必须落在档位内
const menuWxssForSpacing = fs.readFileSync(path.join(root, 'pages/menu/menu.wxss'), 'utf8');
const productSectionRule = (menuWxssForSpacing.match(/\.product-section\s*\{([^}]*)\}/) || [])[1] || '';
assert.ok(
  productSectionRule.indexOf('padding-top: 24rpx') >= 0,
  '点单页商品区上边距必须为档位值 24rpx'
);
assert.ok(
  productSectionRule.indexOf('22rpx') === -1,
  '点单页商品区不得回退为非档位值 22rpx'
);

// 非档位间距仅允许已登记豁免值
const SPACING_ALLOWED = [4, 8, 12, 16, 20, 24, 32, 40];
// 已固化 / 已登记豁免的非档位间距（含负值取绝对值）
const SPACING_FROZEN = [
  6, 9, 13, 14, 15, 18, 28, 72, 120, 127, 280, // 验收测试显式固化
  58, 62, 100 // design-system 已登记豁免值
];
for (const page of appJson.pages) {
  const wxssPath = path.join(root, `${page}.wxss`);
  if (!fs.existsSync(wxssPath)) continue;
  const wxss = fs.readFileSync(wxssPath, 'utf8');
  for (const match of wxss.matchAll(/(?:margin|padding|gap)(?:-[a-z]+)?:\s*([^;}]+)/g)) {
    for (const value of match[1].matchAll(/(-?\d+)rpx/g)) {
      const n = Math.abs(Number(value[1]));
      if (n === 0) continue;
      assert.ok(
        SPACING_ALLOWED.indexOf(n) >= 0 || SPACING_FROZEN.indexOf(n) >= 0,
        `${page} 间距 ${value[1]}rpx 既不在档位内，也未登记为豁免值`
      );
    }
  }
}

// 设计文档必须登记当前豁免清单，防止非档位值继续扩散
const designDoc = fs.readFileSync(path.join(root, 'docs/design-system.md'), 'utf8');
assert.ok(
  designDoc.indexOf('当前已登记的间距豁免值') >= 0 &&
    designDoc.indexOf('padding: 0 62rpx') >= 0 &&
    designDoc.indexOf('margin-right: 58rpx') >= 0 &&
    designDoc.indexOf('100rpx') >= 0,
  '设计文档必须登记已确认的间距豁免值'
);

// 首页 / 我的页根节点缩进必须体现层级（根 view 比 scroll-view 深一级）
for (const page of ['pages/home/home.wxml', 'pages/profile/profile.wxml']) {
  const lines = fs.readFileSync(path.join(root, page), 'utf8').split('\n');
  const scrollIndex = lines.findIndex(line => /^<scroll-view\b/.test(line));
  assert.ok(scrollIndex >= 0, `${page} 必须以 scroll-view 作为根节点`);
  const rootView = lines[scrollIndex + 1] || '';
  assert.ok(
    /^\s{2}<view\b/.test(rootView),
    `${page} 的内容根节点必须比 scroll-view 缩进一级`
  );
}

// 提现页 WXML 含可提现余额/待结算/押金/规则/输入/提交
const withdrawWxml = fs.readFileSync(path.join(root, 'pages/role-withdraw/role-withdraw.wxml'), 'utf8');
assert.ok(
  withdrawWxml.includes('可提现余额') && withdrawWxml.includes('待结算') && withdrawWxml.includes('押金'),
  'withdraw page must show balance/pending/deposit'
);
assert.ok(
  withdrawWxml.includes('提现规则') && withdrawWxml.includes('提交提现'),
  'withdraw page must show rules and submit'
);
assert.ok(
  withdrawWxml.includes('本次到账') && withdrawWxml.includes('手续费') && withdrawWxml.includes('arrivalText'),
  'withdraw page must show arrival and fee preview'
);
// 「全部提现」只允许保留一处（输入框内的快捷填充），不得重复堆叠
assert.equal(
  (withdrawWxml.match(/全部提现/g) || []).length,
  1,
  'withdraw page must define the fill-all affordance exactly once'
);
assert.equal(
  (withdrawWxml.match(/fillAll/g) || []).length,
  1,
  'withdraw page must bind fillAll exactly once'
);
// 规则与记录只能是入口按钮，不得在提现页直接铺数据
assert.ok(
  withdrawWxml.includes('openRules') && withdrawWxml.includes('openRecords'),
  'withdraw page must expose rules and records entries'
);
assert.ok(
  !withdrawWxml.includes('ruleItems') && !withdrawWxml.includes('recordsSummary ==='),
  'withdraw page must not render rule or record payloads inline'
);
assert.ok(
  !withdrawWxml.includes('{{item.amount}}') && !withdrawWxml.includes('withdraw-record__amount'),
  'withdraw page must not render the record list inline'
);

// 提现规则 mock 必须带图标分条
const { getWithdrawRule, WITHDRAW_STATUS_TEXT: withdrawStatusText } = require(path.join(root, 'utils/roles.js'));
const withdrawRuleData = getWithdrawRule();
assert.ok(
  withdrawRuleData && withdrawRuleData.items.length >= 4,
  'withdraw rule must expose at least four icon items'
);
assert.ok(
  withdrawRuleData.items.every(item => item.id && item.icon && item.title && item.description),
  'withdraw rule items must be complete'
);
assert.ok(
  withdrawRuleData.items.every(item => item.icon.startsWith('/assets/icons/lucide/')),
  'withdraw rule icons must come from Lucide'
);

// 提现记录必须是真实演示数据且字段完整
assert.ok(
  withdraw.records.length >= 2 &&
    withdraw.records.every(item => item.id && item.amount && item.status && item.time && item.note),
  'withdraw mock records must contain amount/status/time/note'
);
assert.ok(
  withdraw.records.every(item => ![undefined, ''].includes(withdrawStatusText[item.status])),
  'withdraw mock records must map to a known status label'
);

// 提现记录页：注册 / 骨架 / 筛选 / 空状态
const withdrawRecordsDir = path.join(root, 'pages/role-withdraw-records');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(withdrawRecordsDir, `role-withdraw-records.${extension}`)),
    `missing role-withdraw-records.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/role-withdraw-records/role-withdraw-records'),
  'role-withdraw-records must be registered'
);
const withdrawRecordsWxml = fs.readFileSync(
  path.join(withdrawRecordsDir, 'role-withdraw-records.wxml'),
  'utf8'
);
const withdrawRecordsWxss = fs.readFileSync(
  path.join(withdrawRecordsDir, 'role-withdraw-records.wxss'),
  'utf8'
);
assert.ok(
  withdrawRecordsWxml.includes('switchCategory') && withdrawRecordsWxml.includes('empty-state'),
  'withdraw records page must support filtering and an empty state'
);
assert.ok(
  withdrawRecordsWxml.includes('withdraw-record__amount') == false ||
    withdrawRecordsWxml.includes('{{item.amount}}'),
  'withdraw records page must render the amount'
);
assert.ok(
  withdrawRecordsWxml.includes('{{item.time}}') && withdrawRecordsWxml.includes('{{item.note}}'),
  'withdraw records page must render time and note'
);

// 搜索只匹配提现单号
const withdrawRecordsJs = fs.readFileSync(
  path.join(withdrawRecordsDir, 'role-withdraw-records.js'),
  'utf8'
);
assert.ok(
  withdrawRecordsWxml.includes('placeholder="搜索提现单号"'),
  'withdraw records search must target the order no'
);
assert.ok(
  !/placeholder="[^"]*时间[^"]*"/.test(withdrawRecordsWxml),
  'withdraw records search must not accept a time keyword'
);
assert.ok(
  /function matchKeyword\(record, keyword\)/.test(withdrawRecordsJs) &&
    withdrawRecordsJs.includes('record.orderNo'),
  'withdraw records search must be a pure order-no matcher'
);
assert.ok(
  withdrawRecordsWxml.includes('clearKeyword') && withdrawRecordsJs.includes('clearKeyword'),
  'withdraw records search must be clearable'
);

// 时间筛选：独立的开始/结束选择器 + 底部弹层 picker-view
assert.ok(
  withdrawRecordsWxml.includes('data-field="start"') && withdrawRecordsWxml.includes('data-field="end"'),
  'withdraw records must provide separate start and end date triggers'
);
assert.ok(
  withdrawRecordsWxml.includes('选择开始时间') && withdrawRecordsWxml.includes('选择结束时间'),
  'withdraw records date triggers must be labelled'
);
assert.ok(
  withdrawRecordsWxml.includes('openDatePicker') && withdrawRecordsJs.includes('openDatePicker'),
  'withdraw records must open a date picker'
);
assert.ok(
  withdrawRecordsWxml.includes('<picker-view') &&
    withdrawRecordsWxml.includes('picker-view-column') &&
    withdrawRecordsWxml.includes('bindchange="handleDateChange"'),
  'withdraw records date picker must use picker-view columns'
);
assert.ok(
  withdrawRecordsWxml.includes('confirmDate') && withdrawRecordsWxml.includes('resetDates'),
  'withdraw records date picker must confirm and be resettable'
);
assert.ok(
  /function matchDateRange\(record, start, end\)/.test(withdrawRecordsJs) &&
    withdrawRecordsJs.includes('dateKey < start') &&
    withdrawRecordsJs.includes('dateKey > end'),
  'withdraw records must filter by an inclusive date range'
);
assert.ok(
  withdrawRecordsJs.includes('recordDateKey') && withdrawRecordsJs.includes('.slice(0, 10)'),
  'withdraw records must derive the date key from the record time'
);
assert.ok(
  withdrawRecordsJs.includes('daysInMonth') && withdrawRecordsJs.includes('pickerDays'),
  'withdraw records date picker must adapt days per month'
);
assert.ok(
  withdrawRecordsJs.includes('this.data.dateEnd < picked') &&
    withdrawRecordsJs.includes('this.data.dateStart > picked'),
  'withdraw records must keep the start/end range ordered'
);
assert.ok(
  /\.withdraw-record-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(withdrawRecordsWxss),
  'withdraw records scroll area must keep the three-section layout contract'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(withdrawRecordsWxss),
  'withdraw records WXSS must use design tokens only'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(
    fs.readFileSync(path.join(root, 'pages/role-withdraw/role-withdraw.wxss'), 'utf8')
  ),
  'withdraw page WXSS must use design tokens only'
);
assert.ok(
  !fs.readFileSync(path.join(root, 'pages/role-withdraw/role-withdraw.wxml'), 'utf8').includes('需接入后端接口'),
  'withdraw page must not expose developer notes'
);

// 记录数据：单号 / 手续费 / 到账额 / 收款方式 / 流转链路
assert.ok(
  withdraw.records.every(item => item.orderNo && item.feeText && item.arrivalText && item.channel),
  'withdraw mock records must expose order no, fee, arrival and channel'
);
assert.ok(
  withdraw.records.every(item => /^WD\d+$/.test(item.orderNo)),
  'withdraw order no must follow the WD + digits format'
);
assert.ok(
  withdraw.records.every(item => Array.isArray(item.timeline) && item.timeline.length >= 3),
  'withdraw mock records must carry a timeline'
);
assert.ok(
  withdraw.records.every(item =>
    item.timeline.every(step => step.id && step.title && step.description && step.state)
  ),
  'withdraw timeline steps must be complete'
);
assert.ok(
  withdraw.records.every(item => {
    const activeCount = item.timeline.filter(step => step.state === 'active').length;
    const doneCount = item.timeline.filter(step => step.state === 'done').length;
    return doneCount >= 1 && activeCount <= 1;
  }),
  'withdraw timeline must have a single active step and at least one done step'
);
assert.ok(
  withdraw.records
    .filter(item => item.status === 'failed')
    .every(item => item.failReason && item.timeline.some(step => step.id === 'rejected')),
  'failed records must carry a reason and a rejected step'
);
assert.ok(
  withdraw.records
    .filter(item => item.status === 'success')
    .every(item => item.timeline.every(step => step.state === 'done')),
  'successful records must have a fully completed timeline'
);

// 提现详情页：注册 / 结构 / 时间线三态 / 信息字段
const withdrawDetailDir = path.join(root, 'pages/role-withdraw-detail');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(withdrawDetailDir, `role-withdraw-detail.${extension}`)),
    `missing role-withdraw-detail.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/role-withdraw-detail/role-withdraw-detail'),
  'role-withdraw-detail must be registered'
);
const withdrawDetailWxml = fs.readFileSync(
  path.join(withdrawDetailDir, 'role-withdraw-detail.wxml'),
  'utf8'
);
const withdrawDetailWxss = fs.readFileSync(
  path.join(withdrawDetailDir, 'role-withdraw-detail.wxss'),
  'utf8'
);
assert.ok(
  withdrawDetailWxml.includes('record.timeline') &&
    withdrawDetailWxml.includes('timeline-item--{{item.state}}'),
  'withdraw detail must render the status timeline'
);
assert.ok(
  withdrawDetailWxml.includes('{{record.statusLabel}}') &&
    withdrawDetailWxml.includes('{{record.statusNote}}'),
  'withdraw detail must render status label and note'
);
assert.ok(
  withdrawDetailWxml.includes('{{record.orderNo}}') &&
    withdrawDetailWxml.includes('{{record.feeText}}') &&
    withdrawDetailWxml.includes('{{record.arrivalText}}') &&
    withdrawDetailWxml.includes('{{record.channel}}'),
  'withdraw detail must render order no, fee, arrival and channel'
);
assert.ok(
  withdrawDetailWxml.includes('record.failReason'),
  'withdraw detail must surface the failure reason'
);
assert.ok(
  /\.withdraw-detail-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(withdrawDetailWxss),
  'withdraw detail scroll area must keep the three-section layout contract'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(withdrawDetailWxss),
  'withdraw detail WXSS must use design tokens only'
);

// 记录卡必须可点跳详情
assert.ok(
  withdrawRecordsWxml.includes('bindtap="openDetail"') &&
    withdrawRecordsWxml.includes('chevron-right.svg'),
  'withdraw record cards must be tappable and hint navigation'
);
assert.ok(
  withdrawRecordsWxml.includes('{{item.orderNo}}') && withdrawRecordsWxml.includes('单号'),
  'withdraw records must show the order no'
);
assert.ok(
  withdrawRecordsWxml.includes('catchtap="copyOrderNo"'),
  'withdraw records must copy the order no without bubbling'
);
assert.ok(
  withdrawRecordsJs.includes("'/pages/role-withdraw-detail/role-withdraw-detail'"),
  'withdraw records must route to the detail page'
);

// 详情数据来源与权限
const { getWithdrawRecordDetail } = require(path.join(root, 'utils/roles.js'));
assert.equal(getWithdrawRecordDetail('unknown', 'w-i-1'), null, 'detail must respect role permission');
assert.equal(getWithdrawRecordDetail('investor', 'missing'), null, 'detail must reject unknown ids');
const detailSample = getWithdrawRecordDetail('investor', 'w-i-1');
assert.ok(
  detailSample && detailSample.statusLabel && detailSample.statusNote && detailSample.timeline.length >= 3,
  'detail must expose label, note and timeline'
);
assert.equal(detailSample.statusLabel, '审核中', 'detail label must map from the status');

// 提现规则页：注册 / 骨架 / 渲染 ruleItems / 不含提现记录数据
const withdrawRulesDir = path.join(root, 'pages/role-withdraw-rules');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(withdrawRulesDir, `role-withdraw-rules.${extension}`)),
    `missing role-withdraw-rules.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/role-withdraw-rules/role-withdraw-rules'),
  'role-withdraw-rules must be registered'
);
const withdrawRulesWxml = fs.readFileSync(
  path.join(withdrawRulesDir, 'role-withdraw-rules.wxml'),
  'utf8'
);
const withdrawRulesWxss = fs.readFileSync(
  path.join(withdrawRulesDir, 'role-withdraw-rules.wxss'),
  'utf8'
);
const withdrawRulesJs = fs.readFileSync(
  path.join(withdrawRulesDir, 'role-withdraw-rules.js'),
  'utf8'
);
assert.ok(
  withdrawRulesWxml.includes('ruleItems') && withdrawRulesWxml.includes('{{item.icon}}'),
  'withdraw rules page must render icon rule items'
);
assert.ok(
  withdrawRulesJs.includes('getWithdrawRule') && withdrawRulesJs.includes('instantLimit'),
  'withdraw rules page must reuse the shared withdraw rule data'
);
assert.ok(
  /\.withdraw-rules-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(withdrawRulesWxss),
  'withdraw rules scroll area must keep the three-section layout contract'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(withdrawRulesWxss),
  'withdraw rules WXSS must use design tokens only'
);
assert.ok(
  !withdrawRulesWxml.includes('{{item.amount}}'),
  'withdraw rules page must not render withdraw records'
);

// ===== 门店选品上下架 =====
// product-listing 依赖 wx 存储；测试环境注入内存桩后再引入。
const listingMemory = {};
global.wx = {
  getStorageSync: key => (key in listingMemory ? listingMemory[key] : ''),
  setStorageSync: (key, value) => {
    listingMemory[key] = value;
  }
};
const listingPath = path.join(root, 'utils/product-listing.js');
assert.ok(fs.existsSync(listingPath), 'missing utils/product-listing.js');
const listing = require(listingPath);

// 选品页文件与注册
const productsDir = path.join(root, 'pages/role-products');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(productsDir, `role-products.${extension}`)),
    `missing role-products.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/role-products/role-products'),
  'role-products must be registered'
);
const productsWxml = fs.readFileSync(path.join(productsDir, 'role-products.wxml'), 'utf8');
const productsWxss = fs.readFileSync(path.join(productsDir, 'role-products.wxss'), 'utf8');
const productsJs = fs.readFileSync(path.join(productsDir, 'role-products.js'), 'utf8');

// 门店角色入口 + 3 处 routeMap
const storeDashboard = getDashboard('store');
assert.ok(
  storeDashboard.actions.some(action => action.id === 'products'),
  'store dashboard must expose the products action'
);
assert.ok(
  storeDashboard.boundStoreId === 'store-001',
  'store role must bind an operating store'
);
assert.equal(getBoundStore('investor'), null, 'non-store roles must not bind a store');
assert.equal(getBoundStore('resource'), null, 'non-store roles must not bind a store');
assert.ok(
  getBoundStore('store') && getBoundStore('store').name,
  'store role must expose the bound store name'
);

for (const [file, label] of [
  ['pages/role-workbench/role-workbench.js', 'workbench'],
  ['pages/profile/profile.js', 'profile']
]) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  assert.ok(
    source.includes("products: '/pages/role-products/role-products'"),
    `${label} must route to role-products`
  );
}

// 选品页：图标 / 搜索 / 双维筛选 / 开关 / 批量
const productsAction = getDashboard('store').actions.find(action => action.id === 'products');
assert.ok(productsAction, 'store dashboard must expose the products action');
assert.ok(
  productsAction.icon === '/assets/icons/lucide/shopping-bag.svg',
  'products action must use the dark shopping-bag icon, not the white one'
);
assert.ok(
  !/white\.svg$/.test(productsAction.icon),
  'products action icon must stay visible on a white card'
);
const shoppingBagIcon = fs.readFileSync(
  path.join(root, 'assets/icons/lucide/shopping-bag.svg'),
  'utf8'
);
assert.ok(
  shoppingBagIcon.indexOf('#FFFFFF') === -1 && shoppingBagIcon.indexOf('#747570') !== -1,
  'shopping-bag icon must be rendered in the dark neutral tone'
);

assert.ok(
  productsWxml.includes('placeholder="搜索商品名称或编号"') && productsWxml.includes('handleKeyword'),
  'products page must provide a search field'
);
assert.ok(
  /function matchKeyword\(product, keyword\)/.test(productsJs) &&
    productsJs.includes('product.name') &&
    productsJs.includes('product.id'),
  'products search must match both name and id'
);

// 双维筛选：分类 + 状态，各自独立渲染
assert.ok(
  productsWxml.includes('catTabs') && productsWxml.includes('switchCat'),
  'products page must offer category filtering'
);
assert.ok(
  productsWxml.includes('statusTabs') && productsWxml.includes('switchStatus'),
  'products page must offer status filtering'
);
assert.ok(
  /const STATUS_TABS = \[/.test(productsJs) && productsJs.includes('buildCatTabs'),
  'products page must derive both tab dimensions'
);
assert.ok(
  productsJs.includes('activeCatId') &&
    productsJs.includes('activeStatusId') &&
    productsJs.includes("item.categoryLabel === activeCatId"),
  'category and status filters must both apply'
);

// 布局：分类行与状态行分离，统计行独立
// 状态 Tab 必须用下划线样式，不得用绿色胶囊（会圆角溢出遮挡相邻文字）
// 精确取出 .is-active 规则块（不含 ::after 伪元素）判断是否误用绿底胶囊
const activeRule = (productsWxss.match(/\.products-status__item\.is-active\s*\{([^}]*)\}/) || [])[1] || '';
assert.ok(
  activeRule.length > 0 && activeRule.indexOf('background') === -1,
  'status tabs must not use a solid green pill background'
);
assert.ok(
  /\.products-status__item\.is-active::after[\s\S]*?background:\s*var\(--brand-green\)/.test(productsWxss),
  'status tabs must indicate selection with an underline'
);
assert.ok(
  /\.products-status\s*\{[\s\S]*?overflow:\s*hidden/.test(productsWxss),
  'status tab strip must clip so no tab can bleed onto its neighbour'
);
assert.ok(
  /\.products-tabs-scroll\s*\{[\s\S]*?height:\s*76rpx/.test(productsWxss),
  'category tab strip must declare an explicit height'
);

// 商品详情：入口 + 独立页面
assert.ok(
  productsWxml.includes('bindtap="openDetail"') && productsWxml.includes('product-row__arrow'),
  'product rows must be tappable and hint navigation'
);
assert.ok(
  productsWxml.includes('catchtap="toggleListing"') && productsWxml.includes('bindtap="toggleSelect"'),
  'row actions must stop propagation so the detail tap is not swallowed'
);
assert.ok(
  productsJs.includes("'/pages/role-product-detail/role-product-detail'"),
  'products page must route to the product detail page'
);

const productDetailDir = path.join(root, 'pages/role-product-detail');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(
    fs.existsSync(path.join(productDetailDir, `role-product-detail.${extension}`)),
    `missing role-product-detail.${extension}`
  );
}
assert.ok(
  appJson.pages.includes('pages/role-product-detail/role-product-detail'),
  'role-product-detail must be registered'
);
const productDetailWxml = fs.readFileSync(
  path.join(productDetailDir, 'role-product-detail.wxml'),
  'utf8'
);
const productDetailWxss = fs.readFileSync(
  path.join(productDetailDir, 'role-product-detail.wxss'),
  'utf8'
);
const productDetailJs = fs.readFileSync(
  path.join(productDetailDir, 'role-product-detail.js'),
  'utf8'
);
assert.ok(
  productDetailWxml.includes('{{product.galleryImage}}') &&
    productDetailWxml.includes('{{product.name}}') &&
    productDetailWxml.includes('{{product.price}}'),
  'product detail must render the hero image, name and price'
);
assert.ok(
  productDetailWxml.includes('{{product.id}}') &&
    productDetailWxml.includes('{{product.categoryLabel}}') &&
    productDetailWxml.includes('{{product.tabLabel}}'),
  'product detail must render id, category and menu origin'
);
assert.ok(
  productDetailWxml.includes('product.platformListed') && productDetailWxml.includes('product.listed'),
  'product detail must distinguish platform status from store status'
);
assert.ok(
  productDetailWxml.includes('product.specGroups') && productDetailWxml.includes('option.priceDelta'),
  'product detail must render spec groups with price deltas'
);
assert.ok(
  productDetailWxml.includes('toggleListing') && productDetailJs.includes('setListed'),
  'product detail must allow listing changes'
);
assert.ok(
  productDetailJs.includes('getBoundStore') && productDetailJs.includes('getProductDetail'),
  'product detail must scope to the bound store and reuse the shared lookup'
);
assert.ok(
  /flex:\s*1[\s\S]*?min-height:\s*0/.test(productDetailWxss),
  'product detail scroll area must keep flex:1 and min-height:0'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(productDetailWxss),
  'product detail WXSS must use design tokens only'
);

// 详情数据来源：仅平台已上架商品可查
const { getProductDetail } = listing;
assert.equal(getProductDetail('store-001', 'classic-002'), null, 'off-sale platform products must not open');
assert.equal(getProductDetail('store-001', 'missing'), null, 'unknown products must not open');
const productSample = getProductDetail('store-001', 'classic-001');
assert.ok(
  productSample &&
    productSample.name &&
    productSample.categoryLabel &&
    productSample.tabLabel &&
    productSample.specGroups.length,
  'product detail must expose name, category, menu origin and specs'
);
assert.ok(
  productSample.specGroups.every(group =>
    group.options.every(option => option.id && option.label && typeof option.priceDelta === 'number')
  ),
  'spec options must carry label and price delta'
);
assert.equal(
  getProductDetail('store-999', 'classic-001').listed,
  true,
  'detail must read that store listing state'
);

// 商品详情页分享：私密 + 有标题
const productDetailRoute = 'pages/role-product-detail/role-product-detail';
assert.ok(isPrivatePage(productDetailRoute), `${productDetailRoute} must be private`);
assert.ok(PAGE_SHARE_TITLES[productDetailRoute], `${productDetailRoute} must define a title`);

// 布局：分类行与状态行分离，统计行独立
assert.ok(
  /\.products-tabs[\s\S]*?display:\s*inline-flex/.test(productsWxss),
  'category tabs must size to content so they cannot overflow the viewport'
);
assert.ok(
  /\.products-summary[\s\S]*?padding:\s*16rpx/.test(productsWxss),
  'summary row must be separated from the tab rows'
);
assert.ok(
  !/min-width:\s*750rpx/.test(productsWxss),
  'products toolbar must not force the tab strip to 750rpx'
);
assert.ok(
  productsWxml.includes('products-status') && productsWxml.includes('products-tab'),
  'category and status tabs must be distinct rows'
);

assert.ok(
  productsWxml.includes('toggleListing') && productsWxml.includes('product-row__switch'),
  'products page must offer a per-product listing switch'
);
assert.ok(
  productsWxml.includes('batchUpdate') && productsWxml.includes('批量上架') && productsWxml.includes('批量下架'),
  'products page must support batch listing'
);
assert.ok(
  productsJs.includes('wx.showModal'),
  'batch listing must confirm before applying'
);
assert.ok(
  productsJs.includes('getBoundStore'),
  'products page must scope listing to the bound store'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(productsWxss),
  'products WXSS must use design tokens only'
);
assert.ok(
  /\.products-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(productsWxss),
  'products scroll area must keep the three-section layout contract'
);

// 运营后台商品池：仅 onSale === 'on' 的商品可被门店选择
const catalog = require(path.join(root, 'utils/product-catalog.js'));
assert.ok(
  catalog.getPlatformProductIds().length > 0,
  'platform catalog must not be empty'
);
assert.ok(
  catalog.getPlatformUnlistedIds().length > 0,
  'platform catalog must include at least one off-sale product to prove filtering'
);
assert.equal(catalog.isPlatformListed('classic-002'), false, 'classic-002 must be off-sale upstream');
assert.equal(catalog.isPlatformListed('classic-001'), true, 'classic-001 must be on-sale upstream');
assert.equal(
  catalog.isPlatformListed('herbal-001'),
  false,
  'products missing from the platform catalog must not be selectable'
);

const selectable = listing.getAllProducts();
const selectableIds = selectable.map(item => item.id);
assert.ok(
  selectable.every(item => catalog.isPlatformListed(item.id)),
  'store selectable products must all be on-sale in the platform catalog'
);
assert.ok(
  selectableIds.indexOf('classic-002') === -1,
  'off-sale platform products must not appear in store selectable products'
);
assert.equal(
  selectable.length,
  catalog.getPlatformListedIds().length,
  'store selectable products must equal the on-sale platform products'
);

// 过滤逻辑：默认全上架 / 门店隔离 / 空分类隐藏
const allProducts = listing.getAllProducts();
assert.ok(allProducts.length >= 5, 'product listing must expose the platform-approved catalog');
assert.ok(
  allProducts.every(item => item.id && item.name && item.tabId && item.categoryLabel),
  'flattened products must carry tab and category metadata'
);

const defaultStats = listing.getListingStats('store-001');
assert.equal(defaultStats.listed, defaultStats.total, 'products must default to listed');
assert.equal(defaultStats.unlisted, 0, 'products must default to listed');

listing.setListed('store-001', 'classic-004', false);
const afterUnlist = listing.getListingStats('store-001');
assert.equal(afterUnlist.unlisted, 1, 'unlisting must be reflected in stats');
assert.equal(
  listing.getListingStats('store-002').unlisted,
  0,
  'listing state must be isolated per store'
);
assert.equal(
  listing.isProductListed('store-001', 'classic-004'),
  false,
  'unlisted product must report as unlisted'
);
assert.equal(
  listing.isProductListed('store-002', 'classic-004'),
  true,
  'other stores must keep the product listed'
);
// 平台级下架对任何门店都不可选，门店无法把它改回上架。
assert.equal(
  listing.isProductListed('store-001', 'classic-002'),
  false,
  'platform off-sale products must stay unselectable'
);
listing.setListed('store-001', 'classic-002', true);
assert.ok(
  listing.getAllProducts().every(item => item.id !== 'classic-002'),
  'a store must not resurrect a platform off-sale product'
);

// 消费端菜单：过滤掉下架商品
const listedTabs = listing.getListedMenuTabs('store-001');
const listedIds = [];
listedTabs.forEach(tab =>
  tab.groups.forEach(group =>
    group.categories.forEach(category => category.products.forEach(p => listedIds.push(p.id)))
  )
);
assert.ok(
  listedIds.indexOf('classic-004') === -1,
  'products unlisted by the store must not appear in the consumer menu'
);
assert.ok(
  listedIds.indexOf('classic-003') !== -1,
  'listed products must remain in the consumer menu'
);
assert.ok(
  listedIds.indexOf('classic-002') === -1,
  'platform off-sale products must never reach the consumer menu'
);

// 空分类 / 空分组 / 空 Tab 自动隐藏
// 当前可选商品都落在 recommend 分组，逐一处理整组以验证空分组隐藏。
const leafIds = allProducts.filter(p => p.groupId === 'recommend').map(p => p.id);
listing.setListedBatch('store-001', leafIds, false);
const prunedTabs = listing.getListedMenuTabs('store-001');
const remainingGroups = [];
prunedTabs.forEach(tab => tab.groups.forEach(group => remainingGroups.push(group.id)));
assert.ok(
  remainingGroups.indexOf('recommend') === -1,
  'a group whose products are all unlisted must be hidden'
);
assert.ok(
  prunedTabs.every(tab => tab.groups.length && tab.groups.every(group => group.categories.length)),
  'empty groups and categories must never reach the consumer menu'
);

// 极端情况：全部下架时回落到默认菜单，不至于渲染空页面
const everyId = allProducts.map(p => p.id);
listing.setListedBatch('store-001', everyId, false);
assert.ok(
  listing.getListedMenuTabs('store-001').length > 0,
  'the consumer menu must never collapse to an empty tab list'
);

// 复位
listing.resetListing('store-001');
assert.equal(
  listing.getListingStats('store-001').listed,
  listing.getListingStats('store-001').total,
  'reset must restore every product to listed'
);

// 点单页与适用商品页必须走统一过滤入口
const menuRuntime = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
assert.ok(
  menuRuntime.includes('getListedMenuTabs') && menuRuntime.includes('isProductListed'),
  'menu page must consume the shared listing filter'
);
assert.ok(
  menuRuntime.includes('buildListingUpdates'),
  'menu page must refresh the listing when the store changes'
);
assert.ok(
  menuRuntime.includes('hasUnlistedInCart'),
  'menu page must flag unlisted products sitting in the cart'
);
assert.ok(
  /buildCartUpdates\(storeId\)/.test(menuRuntime) && menuRuntime.includes('listed: isProductListed'),
  'cart items must carry a listed flag without being auto-removed'
);
assert.ok(
  !/cartItems\.filter\([^)]*listed/.test(menuRuntime),
  'unlisted cart items must not be silently dropped'
);

const cartSheetWxml = fs.readFileSync(
  path.join(root, 'components/cart-sheet/cart-sheet.wxml'),
  'utf8'
);
assert.ok(
  cartSheetWxml.includes('cart-item__unlisted') && cartSheetWxml.includes('item.listed === false'),
  'cart sheet must label unlisted products'
);

const couponRuntime = fs.readFileSync(path.join(root, 'pages/coupon-products/coupon-products.js'), 'utf8');
assert.ok(
  couponRuntime.includes('getListedMenuTabs'),
  'coupon products page must consume the shared listing filter'
);

// 选品页分享：私密 + 有标题
const productsRoute = 'pages/role-products/role-products';
assert.ok(isPrivatePage(productsRoute), `${productsRoute} must be private`);
assert.ok(PAGE_SHARE_TITLES[productsRoute], `${productsRoute} must define a title`);

// ===== 资源方门店提成 =====
const {
  getResourceBoundStores,
  getResourceOrders,
  INCOME_STATUS_TEXT: resourceStatusText
} = require(path.join(root, 'utils/roles.js'));

// 绑定门店必须与真实门店数据对齐
const boundStores = getResourceBoundStores('resource');
assert.ok(boundStores.length >= 2, 'resource role must bind at least two stores');
assert.ok(
  boundStores.every(store => store.id && store.name && store.storeType),
  'bound stores must be complete'
);
const realStores = require(path.join(root, 'data/mock.js')).stores;
assert.ok(
  boundStores.every(store => realStores.some(real => real.id === store.id)),
  'bound store ids must match the real store catalog'
);
assert.deepEqual(getResourceBoundStores('store'), [], 'non-resource roles must not have bound stores');

// 非资源方不得读取提成
assert.equal(getResourceOrders('store'), null, 'non-resource roles must not read commission orders');
assert.equal(getResourceOrders('investor'), null, 'non-resource roles must not read commission orders');

const commission = getResourceOrders('resource');
assert.ok(commission, 'resource commission data must exist');
assert.ok(commission.count > 0, 'resource commission must contain orders');
assert.ok(
  commission.groups.every(group => group.count === group.orders.length),
  'each commission group must report its own count'
);
assert.ok(
  commission.groups.every(group =>
    group.orders.every(order => order.storeId === group.id && order.storeName === group.name)
  ),
  'commission orders must belong to their group store'
);
assert.ok(
  commission.groups.every(group =>
    group.orders.every(order => order.orderNo && order.title && order.spec && order.amount && order.status && order.time)
  ),
  'commission orders must be complete'
);
assert.ok(
  commission.groups.every(group => group.orders.every(order => /^DO\d+$/.test(order.orderNo))),
  'commission order no must follow the DO + digits format'
);
assert.ok(
  commission.groups.every(group =>
    group.orders.every(order => Array.isArray(order.timeline) && order.timeline.length >= 3)
  ),
  'commission orders must carry a timeline'
);
assert.ok(
  commission.groups.every(group =>
    group.orders.every(order => order.timeline.every(step => !step.time || step.time.indexOf('NaN') < 0))
  ),
  'commission timelines must never render a NaN timestamp'
);
assert.ok(
  commission.groups.every(group =>
    group.orders.every(order => ![undefined, ''].includes(resourceStatusText[order.status]))
  ),
  'commission statuses must map to known labels'
);

// 合计校验：各门店合计之和必须等于总合计
const sumOfGroups = commission.groups.reduce(
  (sum, group) => sum + Number(group.totalText.replace('+', '')),
  0
);
assert.ok(
  Math.abs(sumOfGroups - Number(commission.totalText.replace('+', ''))) < 0.001,
  'group totals must add up to the overall total'
);
assert.equal(
  commission.count,
  commission.groups.reduce((sum, group) => sum + group.count, 0),
  'overall count must equal the sum of group counts'
);

// 按门店筛选
const firstStoreId = commission.stores[0].id;
const filtered = getResourceOrders('resource', firstStoreId);
assert.ok(
  filtered.groups.every(group => group.id === firstStoreId),
  'filtering by store must only keep that store'
);
assert.ok(filtered.count <= commission.count, 'filtered count must not exceed the total');

// 只读：资源方不提供绑定 / 解绑能力
const rolesSource = fs.readFileSync(path.join(root, 'utils/roles.js'), 'utf8');
assert.ok(
  !/function\s+(bindStore|unbindStore|bindResourceStore)/.test(rolesSource),
  'resource role must not expose store binding or unbinding'
);
const ordersJs = fs.readFileSync(path.join(root, 'pages/resource-orders/resource-orders.js'), 'utf8');
assert.ok(
  !/bind|unbind|handleBind|handleUnbind/.test(ordersJs),
  'resource orders page must not implement binding or unbinding'
);

// 提成页：Hero / 门店筛选 / 分组 / 详情弹层
const ordersWxml = fs.readFileSync(path.join(root, 'pages/resource-orders/resource-orders.wxml'), 'utf8');
const ordersWxss = fs.readFileSync(path.join(root, 'pages/resource-orders/resource-orders.wxss'), 'utf8');
assert.ok(
  ordersWxml.includes('orders-hero') && ordersWxml.includes('{{totalText}}') && ordersWxml.includes('{{pendingCount}}'),
  'resource orders page must show the commission hero'
);
assert.ok(
  ordersWxml.includes('storeTabs') && ordersWxml.includes('switchStore'),
  'resource orders page must offer store filtering'
);
assert.ok(
  ordersWxml.includes('order-group') && ordersWxml.includes('{{item.totalText}}'),
  'resource orders page must group orders by store'
);
assert.ok(
  ordersWxml.includes('{{order.orderNo}}') && ordersWxml.includes('catchtap="copyOrderNo"'),
  'resource order cards must show the order no and copy it without bubbling'
);
assert.ok(
  ordersWxml.includes('bindtap="openOrder"') && ordersWxml.includes('detailVisible'),
  'resource order cards must open a detail sheet'
);
assert.ok(
  ordersWxml.includes('detail.timeline') && ordersWxml.includes('timeline-item--{{item.state}}'),
  'resource order detail must render the settlement timeline'
);
assert.ok(
  ordersWxml.includes('detail.failReason'),
  'resource order detail must surface the reversal reason'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(ordersWxss) &&
    /\.resource-orders-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(ordersWxss),
  'resource orders page must use tokens and keep the three-section layout contract'
);
assert.ok(
  !ordersWxml.includes('需接入后端接口') && !ordersWxml.includes('演示数据'),
  'resource orders page must not expose developer notes'
);

// 工作台：规则改为入口，且不再内联铺数据
const workbenchWxml = fs.readFileSync(
  path.join(root, 'pages/role-workbench/role-workbench.wxml'),
  'utf8'
);
const workbenchWxss = fs.readFileSync(
  path.join(root, 'pages/role-workbench/role-workbench.wxss'),
  'utf8'
);
const workbenchScript = fs.readFileSync(
  path.join(root, 'pages/role-workbench/role-workbench.js'),
  'utf8'
);
assert.ok(
  workbenchWxml.includes('openIncomeRules') &&
    workbenchWxml.includes('openWithdrawRules') &&
    workbenchScript.includes('/pages/role-income-rules/role-income-rules') &&
    workbenchScript.includes('/pages/role-withdraw-rules/role-withdraw-rules'),
  'workbench must route rules to dedicated pages instead of inlining them'
);
assert.ok(
  !workbenchWxml.includes('withdrawRule.') && !workbenchWxml.includes('settlementNotes'),
  'workbench must not inline rule payloads'
);
assert.ok(
  !workbenchWxml.includes('dashboard.records') && !workbenchWxml.includes('records-card'),
  'workbench must not inline the record list'
);
assert.ok(
  !workbenchWxml.includes('需接入后端接口') && !workbenchWxml.includes('演示数据'),
  'workbench must not expose developer notes'
);
assert.ok(
  workbenchWxml.includes('meta-card') === false,
  'workbench must not reintroduce the legacy meta-card markup'
);
assert.ok(
  /\.metric-grid\s*\{[^}]*gap:\s*16rpx/.test(workbenchWxss),
  'workbench metric grid must use the shared spacing scale'
);
assert.ok(
  /\.action-card\s*\{/.test(workbenchWxss),
  'workbench actions must be grouped in a shared card'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(workbenchWxss),
  'workbench WXSS must use design tokens only'
);

// profile 必须能路由资源方订单页
assert.ok(
  fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8').includes(
    "orders: '/pages/resource-orders/resource-orders'"
  ),
  'profile must route the resource orders page'
);

// ===== 投资人点位申请 =====
const investDir = path.join(root, 'pages/role-invest');
const investApplyDir = path.join(root, 'pages/role-invest-apply');
const investRecordsDir = path.join(root, 'pages/role-invest-records');
const investDetailDir = path.join(root, 'pages/role-invest-detail');

for (const [dir, name] of [
  [investDir, 'role-invest'],
  [investApplyDir, 'role-invest-apply'],
  [investRecordsDir, 'role-invest-records'],
  [investDetailDir, 'role-invest-detail']
]) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(fs.existsSync(path.join(dir, `${name}.${extension}`)), `missing ${name}.${extension}`);
  }
  assert.ok(appJson.pages.includes(`pages/${name}/${name}`), `${name} must be registered`);
  const wxss = fs.readFileSync(path.join(dir, `${name}.wxss`), 'utf8');
  assert.ok(!/#[0-9A-Fa-f]{3,8}\b/.test(wxss), `${name} WXSS must use design tokens only`);
}

// 投资人入口：仪表盘 action + 两处 routeMap
assert.ok(
  getDashboard('investor').actions.some(action => action.id === 'invest'),
  'investor dashboard must expose the invest action'
);
for (const [file, label] of [
  ['pages/role-workbench/role-workbench.js', 'workbench'],
  ['pages/profile/profile.js', 'profile']
]) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  assert.ok(
    source.includes("invest: '/pages/role-invest/role-invest'"),
    `${label} must route to role-invest`
  );
}

// 门店数据：启用状态 + 绑定投资人
const { stores: investStores } = require(path.join(root, 'data/mock.js'));
assert.ok(
  investStores.every(store => store.status === 'enabled' || store.status === 'disabled'),
  'stores must carry an enabled/disabled status'
);
assert.ok(
  investStores.every(store => typeof store.investorId === 'string'),
  'stores must carry an investor binding field'
);
assert.ok(
  investStores.some(store => store.status === 'disabled'),
  'at least one store must be disabled to prove filtering'
);
assert.ok(
  investStores.some(store => store.investorId) && investStores.some(store => !store.investorId),
  'stores must cover both bound and unbound investors'
);

const invest = require(path.join(root, 'utils/invest.js'));
const INV = 'INV-1001';

// 点位列表：展示所有门店，按状态标记
const spots = invest.getSpots(INV);
assert.equal(spots.length, investStores.length, 'spot list must cover every store');
assert.ok(
  spots.every(spot => spot.cityName && spot.address && spot.spotStatus && spot.statusLabel === undefined),
  'spots must carry city, address and status'
);
assert.ok(
  spots.some(spot => spot.spotStatus === 'signed'),
  'the bound investor store must be marked as signed'
);
assert.ok(
  spots.some(spot => spot.spotStatus === 'occupied'),
  'stores bound to another investor must be marked as occupied'
);
assert.ok(
  spots.some(spot => spot.spotStatus === 'disabled'),
  'disabled stores must be marked and not applicable'
);
assert.ok(
  spots.filter(spot => spot.spotStatus === 'available').every(spot => spot.canApply),
  'available spots must be applicable'
);
assert.ok(
  spots.filter(spot => spot.spotStatus !== 'available').every(spot => !spot.canApply),
  'non-available spots must not be applicable'
);

// 统计口径
const statsBefore = invest.getInvestStats(INV);
assert.equal(statsBefore.total, spots.length, 'stats total must match the spot list');
assert.equal(
  statsBefore.available,
  spots.filter(spot => spot.spotStatus === 'available').length,
  'stats available must match the spot list'
);
assert.equal(
  statsBefore.signed,
  spots.filter(spot => spot.spotStatus === 'signed').length,
  'stats signed must match the spot list'
);

// 提交申请：成功 + 单号格式 + 时间线
const availableSpot = spots.find(spot => spot.spotStatus === 'available');
const submission = invest.submitApplication({
  investorId: INV,
  storeId: availableSpot.id,
  budget: '200000',
  contact: '张三',
  phone: '13800008888'
});
assert.ok(submission.ok, 'submitting an available spot must succeed');
assert.ok(/^INV\d+$/.test(submission.record.orderNo), 'application order no must follow INV + digits');
assert.ok(
  submission.record.timeline.length >= 3 &&
    submission.record.timeline.every(step => step.id && step.title && step.description && step.state),
  'application timeline must be complete'
);
assert.ok(
  submission.record.timeline.every(step => !step.time || step.time.indexOf('NaN') < 0),
  'application timeline must never render a NaN timestamp'
);
assert.equal(
  submission.record.timeline.filter(step => step.state === 'active').length,
  1,
  'application timeline must have exactly one active step'
);

// 提交后状态与统计同步
assert.equal(
  invest.getSpotById(availableSpot.id).spotStatus,
  'pending',
  'a submitted spot must move to pending'
);
assert.equal(
  invest.getInvestStats(INV).pending,
  statsBefore.pending + 1,
  'pending count must increase after submitting'
);

// 拦截：重复 / 已绑定 / 停用 / 不存在
assert.equal(invest.submitApplication({ investorId: INV, storeId: availableSpot.id }).ok, false, 'duplicate pending must be rejected');
const signedSpot = spots.find(spot => spot.spotStatus === 'signed');
assert.equal(
  invest.submitApplication({ investorId: INV, storeId: signedSpot.id }).ok,
  false,
  'already signed spots must be rejected'
);
const occupiedSpot = spots.find(spot => spot.spotStatus === 'occupied');
assert.equal(
  invest.submitApplication({ investorId: INV, storeId: occupiedSpot.id }).ok,
  false,
  'spots bound to another investor must be rejected'
);
const disabledSpot = spots.find(spot => spot.spotStatus === 'disabled');
assert.equal(
  invest.submitApplication({ investorId: INV, storeId: disabledSpot.id }).ok,
  false,
  'disabled spots must be rejected'
);
assert.equal(invest.submitApplication({ investorId: INV, storeId: 'nope' }).ok, false, 'unknown spots must be rejected');
assert.equal(invest.submitApplication({ investorId: INV }).ok, false, 'missing store id must be rejected');

// 详情：权限 + 状态文案 + 脱敏手机号
assert.equal(invest.getApplicationDetail('INV-9999', submission.record.id), null, 'detail must respect the investor scope');
assert.equal(invest.getApplicationDetail(INV, 'nope'), null, 'detail must reject unknown ids');
const applicationDetail = invest.getApplicationDetail(INV, submission.record.id);
assert.ok(
  applicationDetail && applicationDetail.statusLabel && applicationDetail.statusNote && applicationDetail.timeline.length,
  'detail must expose label, note and timeline'
);
assert.equal(applicationDetail.statusLabel, '审核中', 'detail label must map from the status');
assert.ok(
  /^\d{3}\*{4}\d{2}$/.test(applicationDetail.phoneText),
  'detail must mask the contact phone'
);

// 主页面：搜索 / 城市筛选 / 入口
const investWxml = fs.readFileSync(path.join(investDir, 'role-invest.wxml'), 'utf8');
const investWxss = fs.readFileSync(path.join(investDir, 'role-invest.wxss'), 'utf8');
const investJs = fs.readFileSync(path.join(investDir, 'role-invest.js'), 'utf8');
assert.ok(
  investWxml.includes('placeholder="搜索点位名称或地址"') && investWxml.includes('handleKeyword'),
  'invest page must provide a search field'
);
assert.ok(
  /function matchKeyword\(spot, keyword\)/.test(investJs) &&
    investJs.includes('spot.name') &&
    investJs.includes('spot.address'),
  'invest search must match both name and address'
);
assert.ok(
  investWxml.includes('cityTabs') && investWxml.includes('switchCity'),
  'invest page must offer city filtering'
);
assert.ok(
  investWxml.includes('openRecords') && investWxml.includes('openSpot'),
  'invest page must expose the records entry and spot selection'
);
assert.ok(
  investWxml.includes('spot-card--{{item.spotStatus}}'),
  'spot cards must reflect their status'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(investWxss) &&
    /\.invest-scroll[\s\S]*?flex:\s*1[\s\S]*?min-height:\s*0/.test(investWxss),
  'invest page must use tokens and keep the three-section layout contract'
);

// 申请表单：仅可申请点位可进入 + 手机号校验
const applyJs = fs.readFileSync(path.join(investApplyDir, 'role-invest-apply.js'), 'utf8');
const applyWxml = fs.readFileSync(path.join(investApplyDir, 'role-invest-apply.wxml'), 'utf8');
assert.ok(
  applyJs.includes("spot.spotStatus !== 'available'"),
  'apply form must reject spots that are not applicable'
);
assert.ok(
  /function isValidPhone\(value\)/.test(applyJs) && applyJs.includes('/^1\\d{10}$/'),
  'apply form must validate the contact phone'
);
assert.ok(
  applyWxml.includes('{{spot.name}}') && applyWxml.includes('{{spot.address}}'),
  'apply form must show the chosen spot'
);
assert.ok(
  applyWxml.includes('canSubmit') && applyWxml.includes('is-disabled'),
  'apply form must reflect the submit state'
);

// 记录页：搜索 + 时间范围 + 状态角标
const recordsWxml = fs.readFileSync(path.join(investRecordsDir, 'role-invest-records.wxml'), 'utf8');
const recordsJs = fs.readFileSync(path.join(investRecordsDir, 'role-invest-records.js'), 'utf8');
assert.ok(
  recordsWxml.includes('placeholder="搜索申请单号或点位"') &&
    recordsJs.includes('record.orderNo') &&
    recordsJs.includes('record.storeName'),
  'invest records search must match order no and store name'
);
assert.ok(
  recordsWxml.includes('data-field="start"') &&
    recordsWxml.includes('data-field="end"') &&
    recordsWxml.includes('<picker-view') &&
    recordsWxml.includes('bindchange="handleDateChange"'),
  'invest records must offer an inclusive date range picker'
);
assert.ok(
  /function matchDateRange\(record, start, end\)/.test(recordsJs),
  'invest records must filter by date range'
);
assert.ok(
  recordsWxml.includes('invest-record-category__count') &&
    recordsJs.includes('matched.filter(r => r.status === item.id)'),
  'invest record category counts must derive from the filtered set'
);
assert.ok(
  recordsWxml.includes('catchtap="copyOrderNo"') && recordsWxml.includes('bindtap="openDetail"'),
  'invest record cards must copy the order no and route to detail'
);

// 详情页：流转时间线
const detailWxml = fs.readFileSync(path.join(investDetailDir, 'role-invest-detail.wxml'), 'utf8');
assert.ok(
  detailWxml.includes('record.timeline') &&
    detailWxml.includes('timeline-item--{{item.state}}') &&
    detailWxml.includes('invest-detail-hero--{{record.status}}'),
  'invest detail must render the status hero and timeline'
);
assert.ok(
  detailWxml.includes('{{record.orderNo}}') &&
    detailWxml.includes('{{record.storeName}}') &&
    detailWxml.includes('{{record.budget}}') &&
    detailWxml.includes('{{record.phoneText}}'),
  'invest detail must render order no, store, budget and masked phone'
);
assert.ok(detailWxml.includes('record.failReason'), 'invest detail must surface the rejection reason');

// 四页分享：私密 + 有标题
for (const route of [
  'pages/role-invest/role-invest',
  'pages/role-invest-apply/role-invest-apply',
  'pages/role-invest-records/role-invest-records',
  'pages/role-invest-detail/role-invest-detail'
]) {
  assert.ok(isPrivatePage(route), `${route} must be private`);
  assert.ok(PAGE_SHARE_TITLES[route], `${route} must define a title`);
}

// ===== 收益模块 =====
const incomeJs = fs.readFileSync(path.join(root, 'pages/role-income/role-income.js'), 'utf8');
const incomeWxml = fs.readFileSync(path.join(root, 'pages/role-income/role-income.wxml'), 'utf8');
const incomeWxss = fs.readFileSync(path.join(root, 'pages/role-income/role-income.wxss'), 'utf8');

// 收益页：Hero + 入口 + 趋势，不再直接铺记录
assert.ok(
  incomeWxml.includes('income-hero') && incomeWxml.includes('income-hero__metrics'),
  'income page must use the shared hero layout'
);
assert.ok(
  incomeWxml.includes('openRecords') && incomeWxml.includes('openRules'),
  'income page must expose records and rules entries'
);
assert.ok(
  !incomeWxml.includes('income.records') && !incomeWxml.includes('{{item.amount}}'),
  'income page must not render the record list inline'
);
assert.ok(
  !incomeWxml.includes('需接入后端接口') && !incomeWxml.includes('演示数据'),
  'income page must not expose developer notes'
);
assert.ok(
  incomeWxml.includes('income-trend__bar') && incomeWxml.includes('item.percent'),
  'income trend must render a proportional bar'
);
assert.ok(
  /function withPercent\(trend\)/.test(incomeJs) && incomeJs.includes('Math.max'),
  'income trend bars must be normalised against the period maximum'
);
assert.ok(
  incomeWxss.includes('income-hero') && !/#[0-9A-Fa-f]{3,8}\b/.test(incomeWxss),
  'income WXSS must use design tokens only'
);
assert.ok(
  /"pages\/role-income-records\/role-income-records"/.test(
    fs.readFileSync(path.join(root, 'app.json'), 'utf8')
  ),
  'income records page must be registered'
);

// 收益数据：单号 / 来源 / 状态 / 时间线
const { getIncomeRecordDetail, getIncomeRule } = require(path.join(root, 'utils/roles.js'));
const incomeForStore = getIncomeData('store');
assert.ok(
  incomeForStore.pending && incomeForStore.settled && incomeForStore.total,
  'income data must expose pending/settled/total'
);
assert.ok(
  incomeForStore.records.every(
    item => item.orderNo && item.source && item.amount && item.status && item.time && item.note
  ),
  'income mock records must be complete'
);
assert.ok(
  incomeForStore.records.every(item => /^IC\d+$/.test(item.orderNo)),
  'income order no must follow the IC + digits format'
);
assert.ok(
  incomeForStore.records.every(item => Array.isArray(item.timeline) && item.timeline.length >= 3),
  'income mock records must carry a timeline'
);
assert.ok(
  incomeForStore.records.every(item =>
    item.timeline.every(step => step.id && step.title && step.description && step.state)
  ),
  'income timeline steps must be complete'
);
assert.ok(
  incomeForStore.records.every(item =>
    item.timeline.every(step => !step.time || step.time.indexOf('NaN') < 0)
  ),
  'income timeline must never render a NaN timestamp'
);
assert.ok(
  incomeForStore.records.every(item => {
    const activeCount = item.timeline.filter(step => step.state === 'active').length;
    const doneCount = item.timeline.filter(step => step.state === 'done').length;
    return doneCount >= 1 && activeCount === 1;
  }),
  'income timeline must have exactly one active step and at least one done step'
);
assert.ok(
  incomeForStore.records
    .filter(item => item.status === 'reversed')
    .every(item => item.timeline.some(step => step.id === 'reversed') && item.failReason),
  'reversed income records must carry a reversal step and reason'
);
// settled：可结算节点为当前进行中，之前的节点均已完成，「已提现」尚未开始。
assert.ok(
  incomeForStore.records
    .filter(item => item.status === 'settled')
    .every(
      item =>
        item.timeline.filter(step => step.state === 'done').length >= 2 &&
        item.timeline.some(step => step.id === 'settled' && step.state === 'active') &&
        item.timeline.some(step => step.id === 'withdrawn' && step.state === 'todo')
    ),
  'settled income records must finish prior steps and leave withdrawal pending'
);
assert.ok(
  incomeForStore.records.every(item => !item.timeline.some(step => step.time && step.time.indexOf('NaN') >= 0)),
  'income timeline timestamps must be valid'
);

// 收益详情：权限 + 状态文案 + 时间线
assert.equal(getIncomeRecordDetail('unknown', 'i-s-1'), null, 'income detail must respect role permission');
assert.equal(getIncomeRecordDetail('store', 'missing'), null, 'income detail must reject unknown ids');
const incomeDetail = getIncomeRecordDetail('store', 'i-s-1');
assert.ok(
  incomeDetail && incomeDetail.statusLabel && incomeDetail.statusNote && incomeDetail.timeline.length >= 3,
  'income detail must expose label, note and timeline'
);
assert.equal(incomeDetail.statusLabel, '待结算', 'income detail label must map from the status');

// 结算说明
const incomeRule = getIncomeRule();
assert.ok(
  incomeRule && incomeRule.items.length >= 4 && incomeRule.footnotes.length >= 2,
  'income rule must expose items and footnotes'
);
assert.ok(
  incomeRule.items.every(item => item.id && item.icon && item.title && item.description),
  'income rule items must be complete'
);
assert.ok(
  incomeRule.items.every(item => item.icon.indexOf('/assets/icons/lucide/') === 0),
  'income rule icons must come from Lucide'
);

// 收益记录页 / 详情页 / 结算说明页：文件 + 骨架 + token
for (const page of ['role-income-records', 'role-income-detail', 'role-income-rules']) {
  const dir = path.join(root, `pages/${page}`);
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(fs.existsSync(path.join(dir, `${page}.${extension}`)), `missing ${page}.${extension}`);
  }
  assert.ok(
    appJson.pages.includes(`pages/${page}/${page}`),
    `${page} must be registered`
  );
  const wxss = fs.readFileSync(path.join(dir, `${page}.wxss`), 'utf8');
  assert.ok(!/#[0-9A-Fa-f]{3,8}\b/.test(wxss), `${page} WXSS must use design tokens only`);
  const scrollRule = wxss.match(/\.[a-z-]*scroll\s*\{([\s\S]*?)\}/);
  assert.ok(scrollRule, `${page} must define a scroll region`);
  assert.ok(
    /flex:\s*1/.test(scrollRule[1]) && /min-height:\s*0/.test(scrollRule[1]),
    `${page} scroll region must keep flex:1 and min-height:0`
  );
}

// 收益记录页：搜索单号 + 时间范围 + 状态角标 + 跳详情
const incomeRecordsWxml = fs.readFileSync(
  path.join(root, 'pages/role-income-records/role-income-records.wxml'),
  'utf8'
);
const incomeRecordsJs = fs.readFileSync(
  path.join(root, 'pages/role-income-records/role-income-records.js'),
  'utf8'
);
assert.ok(
  incomeRecordsWxml.includes('placeholder="搜索收益单号"') &&
    !/placeholder="[^"]*时间[^"]*"/.test(incomeRecordsWxml),
  'income records search must target the order no only'
);
assert.ok(
  incomeRecordsWxml.includes('data-field="start"') && incomeRecordsWxml.includes('data-field="end"'),
  'income records must provide separate start and end date triggers'
);
assert.ok(
  incomeRecordsWxml.includes('<picker-view') && incomeRecordsWxml.includes('bindchange="handleDateChange"'),
  'income records date picker must use picker-view columns'
);
assert.ok(
  incomeRecordsWxml.includes('income-record-category__count') &&
    incomeRecordsJs.includes('matched.filter(r => r.status === item.id)'),
  'income record category counts must derive from the filtered set'
);
assert.ok(
  /function matchDateRange\(record, start, end\)/.test(incomeRecordsJs) &&
    incomeRecordsJs.includes('dateKey < start') &&
    incomeRecordsJs.includes('dateKey > end'),
  'income records must filter by an inclusive date range'
);
assert.ok(
  incomeRecordsWxml.includes('copyOrderNo') &&
    incomeRecordsWxml.includes('catchtap="copyOrderNo"') &&
    incomeRecordsWxml.includes('bindtap="openDetail"'),
  'income record cards must copy the order no and route to detail'
);
assert.ok(
  incomeRecordsJs.includes("'/pages/role-income-detail/role-income-detail'") &&
    incomeRecordsJs.includes('directionOf'),
  'income records must route to detail and derive the amount direction'
);

// 收益详情页：流转 + 字段
const incomeDetailWxml = fs.readFileSync(
  path.join(root, 'pages/role-income-detail/role-income-detail.wxml'),
  'utf8'
);
assert.ok(
  incomeDetailWxml.includes('record.timeline') &&
    incomeDetailWxml.includes('income-detail-hero--{{record.status}}'),
  'income detail must render the settlement timeline and status hero'
);
assert.ok(
  incomeDetailWxml.includes('{{record.orderNo}}') &&
    incomeDetailWxml.includes('{{record.source}}') &&
    incomeDetailWxml.includes('{{record.statusLabel}}'),
  'income detail must render order no, source and status'
);
// 收益详情必须用自有 hero 类名，避免与提现详情的修饰类混用。
assert.ok(
  !/\sclass="detail-hero/.test(incomeDetailWxml) &&
    !/\sdetail-hero--\{\{/.test(incomeDetailWxml.replace(/income-detail-hero/g, '')),
  'income detail must not reuse the withdraw hero class'
);

// 结算说明页：渲染 ruleItems
const incomeRulesWxml = fs.readFileSync(
  path.join(root, 'pages/role-income-rules/role-income-rules.wxml'),
  'utf8'
);
assert.ok(
  incomeRulesWxml.includes('ruleItems') &&
    incomeRulesWxml.includes('{{item.icon}}') &&
    !incomeRulesWxml.includes('{{item.amount}}'),
  'income rules page must render icon rule items only'
);

// 收益三页分享：私密 + 有标题
for (const route of [
  'pages/role-income-records/role-income-records',
  'pages/role-income-detail/role-income-detail',
  'pages/role-income-rules/role-income-rules'
]) {
  assert.ok(isPrivatePage(route), `${route} must be private`);
  assert.ok(PAGE_SHARE_TITLES[route], `${route} must define a title`);
}

// 提现规则/记录页分享：私密 + 有标题
for (const recordsRoute of [
  'pages/role-withdraw-records/role-withdraw-records',
  'pages/role-withdraw-rules/role-withdraw-rules',
  'pages/role-withdraw-detail/role-withdraw-detail'
]) {
  assert.ok(isPrivatePage(recordsRoute), `${recordsRoute} must be private`);
  assert.ok(PAGE_SHARE_TITLES[recordsRoute], `${recordsRoute} must define a title`);
}

console.log('角色功能落地页、核销/收益/提现路由与权限测试通过');
