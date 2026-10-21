import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const exists = rel => fs.existsSync(path.join(root, rel));
const cssRule = (css, selector) => {
  const start = css.indexOf(selector);
  if (start < 0) return '';
  const open = css.indexOf('{', start);
  const end = css.indexOf('}', open);
  return open >= 0 && end > open ? css.slice(open + 1, end) : '';
};
const app = JSON.parse(read('app.json'));
const healthPackage = (app.subpackages || []).find(item => item.root === 'packageHealth');
assert.ok(healthPackage, 'app.json 必须注册 packageHealth 分包');
assert.deepEqual(
  healthPackage.pages,
  ['pages/capture/capture', 'pages/report/report', 'pages/history/history'],
  'packageHealth 必须包含拍摄、报告、记录三页（入口在舌诊 Tab）'
);

// ---- 舌诊 Tab：体质健康管理入口（参考图 1/10） ----
const tongueJs = read('pages/tongue/tongue.js');
const tongueWxml = read('pages/tongue/tongue.wxml');
const tongueJson = JSON.parse(read('pages/tongue/tongue.json'));
assert.ok(!tongueWxml.includes('<cart-bar') && !tongueWxml.includes('<cart-sheet'), '舌诊页不得渲染购物车浮条或弹层');
assert.ok(
  !tongueJson.usingComponents['cart-bar'] && !tongueJson.usingComponents['cart-sheet'],
  '舌诊页不得注册购物车组件'
);
assert.ok(!tongueJs.includes('recommendation-cart-state'), '舌诊页不得接管推荐购物车状态');
assert.ok(tongueJs.includes('selected: 2'), '舌诊 Tab 必须同步 Tab 选中态');
assert.ok(tongueJs.includes("require('../../utils/tongue-profile')"), '舌诊入口必须复用本地舌诊资料');
assert.ok(tongueJs.includes('loginGuard.requireLogin'), '未登录时必须先登录再进入拍摄页与记录页');
assert.ok(tongueJson.usingComponents && tongueJson.usingComponents['navigation-bar'], '必须注册自定义导航栏组件');
assert.ok(
  tongueJson.usingComponents && tongueJson.usingComponents['health-profile-sheet'],
  '个人信息弹窗必须注册为组件'
);
assert.ok(tongueWxml.includes('title="体质健康管理"'), '舌诊 Tab 顶部标题必须是体质健康管理');
assert.ok(tongueWxml.includes('中医体质辨识'), '必须保留左侧中医体质辨识功能卡');
assert.ok(tongueWxml.includes('调理方案'), '必须保留右侧调理方案功能卡');
assert.ok(
  ['舌面分析', '中医体质', '健康状态'].every(text => tongueWxml.includes(text)),
  '左侧中医体质辨识三条说明必须与参考图一致'
);
assert.ok(
  ['饮食调理', '形体运动', '穴位保健'].every(text => tongueWxml.includes(text)),
  '右侧调理方案三条说明必须与参考图一致'
);
assert.ok(tongueWxml.includes('开始测试'), '必须有开始测试主按钮');
assert.ok(tongueWxml.includes('饭后半小时再测试'), '必须展示饭后测试提示');
assert.ok(tongueWxml.includes('勿食有色食品'), '必须展示饮食提示');
assert.ok(tongueWxml.includes('体质辨识记录'), '底部记录入口文案按参考图保持「体质辨识记录」');
assert.ok(tongueWxml.includes('tabbar-safe-space'), '舌诊 Tab 必须预留 TabBar 安全区');
assert.ok(tongueWxml.includes('aria-label="开始测试"'), '开始测试按钮必须有无障碍标签');
assert.equal(
  (tongueWxml.match(/\/assets\/icons\/lucide\/check-brand\.svg/g) || []).length,
  6,
  '六条功能说明必须统一使用 Lucide 勾选图标'
);
assert.ok(tongueWxml.includes('/assets/icons/lucide/coffee-brand.svg'), '调理方案必须使用 Lucide 茶杯图标');
assert.ok(tongueWxml.includes('/assets/icons/lucide/chevron-right-brand.svg'), '两张功能卡之间必须有导向箭头');
assert.ok(tongueWxml.includes('aria-hidden="true"'), '装饰性导向箭头必须对辅助技术隐藏');
assert.ok(tongueWxml.includes('/assets/icons/lucide/list-muted.svg'), '记录入口必须使用弱化色 Lucide 列表图标');
assert.ok(!tongueWxml.includes('health-feature__badge'), '按参考图移除 AI 角标');
assert.ok(!/<navigation-bar[^>]*back=/.test(tongueWxml), '舌诊 Tab 不添加返回按钮');
assert.ok(!/agreed/.test(tongueWxml), '按参考图移除授权勾选');
assert.ok(!/agreed/.test(tongueJs), '按参考图移除授权勾选逻辑');
assert.ok(!tongueWxml.includes('/assets/images/3x/health-feature'), '功能卡图标必须使用 Lucide，不再引用截图裁切素材');

const tongueWxss = read('pages/tongue/tongue.wxss');
assert.ok(!cssRule(tongueWxss, '.health-features').includes('padding: 0 40rpx'), '功能卡区域不得再有 40rpx 横向内边距');
assert.match(cssRule(tongueWxss, '.health-avatar'), /margin-right:\s*16rpx/, '头像必须贴近参考图右侧位置');
assert.match(cssRule(tongueWxss, '.health-avatar__image'), /width:\s*72rpx/, '头像尺寸必须与参考图一致');
assert.match(cssRule(tongueWxss, '.health-feature__tile'), /width:\s*160rpx/, '功能卡图标底尺寸必须为 160rpx');
assert.match(cssRule(tongueWxss, '.health-feature__tile'), /height:\s*160rpx/, '功能卡图标底高度必须为 160rpx');
assert.match(cssRule(tongueWxss, '.health-feature__image'), /width:\s*112rpx/, '功能卡图标尺寸必须为 112rpx');
assert.match(cssRule(tongueWxss, '.health-feature__line'), /min-height:\s*40rpx/, '功能说明行高必须为 40rpx');
assert.match(cssRule(tongueWxss, '.health-cta-wrap'), /margin-top:\s*152rpx/, '主按钮上方留白必须为 152rpx');
assert.match(cssRule(tongueWxss, '.health-history-entry'), /margin-top:\s*120rpx/, '记录入口上方留白必须为 120rpx');

const appWxss = read('app.wxss');
for (const token of ['--health-feature-pill-bg', '--health-cta-shell', '--health-cta-shadow']) {
  assert.ok(appWxss.includes(token), `app.wxss 必须提供舌诊样式 token ${token}`);
}

// ---- 底部导航：文案「舌诊」+ 图标 tongue/tongue-active ----
const tabBarJs = read('custom-tab-bar/index.js');
assert.ok(tabBarJs.includes("pagePath: '/pages/tongue/tongue'"), '底部导航第三项必须指向舌诊页');
assert.ok(tabBarJs.includes("text: '舌诊'"), '底部导航第三项文案必须是舌诊');
assert.ok(
  tabBarJs.includes("icon: '/assets/icons/lucide/tongue.svg'") &&
    tabBarJs.includes("activeIcon: '/assets/icons/lucide/tongue-active.svg'"),
  '舌诊 Tab 必须使用 tongue / tongue-active 图标'
);

// ---- 拍摄页（参考图 2/3） ----
const captureJs = read('packageHealth/pages/capture/capture.js');
const captureWxml = read('packageHealth/pages/capture/capture.wxml');
const captureJson = JSON.parse(read('packageHealth/pages/capture/capture.json'));
assert.ok(!captureWxml.includes('<cart-bar') && !captureWxml.includes('<cart-sheet'), '拍摄页不得渲染购物车组件');
assert.ok(
  !captureJson.usingComponents['cart-bar'] && !captureJson.usingComponents['cart-sheet'],
  '拍摄页不得注册购物车组件'
);
assert.ok(captureJson.usingComponents['navigation-bar'], '拍摄页必须注册自定义导航栏组件');
assert.ok(captureWxml.includes('title="体质健康管理"'), '拍摄页导航标题必须是体质健康管理');
assert.ok(
  captureWxml.includes('capture-heading') && captureWxml.includes('拍摄舌头'),
  '拍摄页内容区必须有绿色拍摄舌头标题'
);
assert.ok(!captureWxml.includes('title="拍摄舌头"'), '拍摄舌头不得继续放在导航栏标题中');
assert.ok(captureWxml.includes('本服务为AI生成内容，结果仅供参考'), '拍摄页必须展示 AI 免责声明');
assert.ok(captureWxml.includes('capture-shutter'), '拍摄页必须有底部快门按钮');
assert.ok(captureJs.includes('wx.showActionSheet'), '快门必须弹出拍照/相册选择（参考图 3）');
assert.ok(captureJs.includes("['拍照', '从手机相册选择']"), '选择项文案必须为拍照 / 从手机相册选择');
assert.ok(captureJs.includes('wx.chooseMedia'), '拍摄页必须支持拍照与相册选图');
assert.ok(captureJs.includes('wx.compressImage'), '拍摄页必须压缩舌照');
assert.ok(captureJs.includes('2.5 * 1024 * 1024'), '拍摄页必须限制压缩后图片为 2.5MB');
assert.ok(captureJs.includes('api.detectTongue'), '拍摄页必须调用统一上传接口');
assert.ok(captureJs.includes('wx.redirectTo'), '检测成功后必须用 redirectTo 进详情，避免返回重复提交');
assert.ok(
  captureJs.includes('tongueProfile.resolveTongueProfile()'),
  '拍摄页必须使用弹层保存的本地舌诊资料，无缓存时才回退账号资料'
);

// ---- 记录页（参考图 4） ----
const historyJs = read('packageHealth/pages/history/history.js');
const historyWxml = read('packageHealth/pages/history/history.wxml');
assert.ok(historyWxml.includes('title="体质识别记录"'), '记录页标题必须是体质识别记录');
assert.ok(historyWxml.includes('查看详情'), '记录卡必须有查看详情入口');
assert.ok(historyWxml.includes('检测时间'), '记录卡必须展示检测时间');
assert.ok(historyWxml.includes('<empty-state'), '记录页空状态必须复用 empty-state');
assert.ok(
  historyJs.includes('profileSummaryOf(report)') && historyJs.includes('report.sex'),
  '记录卡必须用该次检测的接口 sex/age 拼摘要'
);
assert.ok(!historyJs.includes('deleteTongueReport'), '按参考图移除删除入口');

// ---- 详情页（参考图 5-9） ----
const modelJs = read('utils/tongue-report-model.js');
const reportJs = read('packageHealth/pages/report/report.js');
const reportWxml = read('packageHealth/pages/report/report.wxml');
assert.ok(reportWxml.includes('title="详情"'), '详情页标题必须是详情');
assert.ok(reportWxml.includes('检查记录'), '详情页必须展示检查记录卡');
assert.ok(reportWxml.includes('报告详情'), '详情页必须展示报告详情标题');
assert.ok(reportWxml.includes('内容由AI生成，结果仅供参考'), '详情页必须展示 AI 免责声明');
assert.ok(reportWxml.includes('结果仅供健康参考'), '详情页必须保留健康免责声明');
for (const tab of ['overview', 'diet', 'acupoint', 'emotion', 'sport']) {
  assert.ok(modelJs.includes(`key: '${tab}'`), `详情页必须包含 Tab ${tab}`);
}
for (const label of ['概况', '饮食', '穴位', '情志', '运动']) {
  assert.ok(modelJs.includes(`label: '${label}'`), `详情页 Tab 文案必须为 ${label}`);
}
assert.ok(modelJs.includes('tabKey'), '详情页必须按后端 tabKey 归入 Tab，未命中分组追加为额外 Tab');
assert.ok(modelJs.includes('tabKeyOfTitle'), '后端未下发 tabKey 时，前端必须按标题关键字兜底归组');
assert.ok(modelJs.includes("key: group.key + '-' + index"), '未命中的分组必须追加为额外 Tab，避免内容丢失');
assert.ok(modelJs.includes('matched'), '商品卡必须区分命中商城与未命中的文字卡');
assert.ok(reportWxml.includes('product.price'), '商品卡必须展示商城价格');
assert.ok(modelJs.includes('WEEKDAYS'), '一周食谱必须按周X 渲染 pill');
assert.ok(modelJs.includes('SEASONS'), '四季节气进补必须按节气渲染 pill');
assert.ok(reportWxml.includes('report-pill'), 'pill 切换必须在模板中实现');
assert.ok(reportWxml.includes('report-analysis__indices'), '舌象指数必须使用 3+2 网格');
assert.ok(reportWxml.includes('<video'), '视频必须使用 video 组件延迟创建');
assert.ok(reportJs.includes('createInnerAudioContext'), '音频必须使用 InnerAudioContext');
assert.ok(reportJs.includes('destroy()'), '页面卸载或切换媒体时必须销毁播放器');
assert.ok(reportJs.includes('MAX_POLLS = 5'), '报告生成中最多自动刷新 5 次');
assert.ok(reportJs.includes("require('../../../utils/tongue-report-model')"), '详情页必须复用可回归的渲染模型');
assert.ok(reportJs.includes('POLL_INTERVAL = 3000'), '报告轮询间隔必须为 3 秒');
assert.ok(!reportWxml.includes('refresh-cw'), '按参考图移除手动刷新按钮');
assert.ok(!reportJs.includes('toggleGroup'), '按参考图移除折叠分组交互');
assert.ok(!reportWxml.includes('tongueImagePath'), '按参考图详情页不再展示舌照卡');

// ---- 内容清洗与数据来源（本轮修复）----
assert.ok(modelJs.includes('toPlainText'), '前端必须对描述/推荐理由做纯文本兜底');
assert.ok(modelJs.includes('text: toPlainText('), '描述项必须使用纯文本字段 text');
assert.ok(modelJs.includes('function toPlainText'), '描述类文本必须走纯文本清洗');
assert.ok(reportWxml.includes('{{item.text}}'), '描述必须绑定纯文本字段');
assert.ok(!reportWxml.includes('{{item.content}}'), '描述不得再直接渲染 content，否则会把 <p>/&nbsp; 当文字显示');
assert.ok(reportWxml.includes('report-empty') && reportWxml.includes('内容暂未生成'), '每个 Tab 必须有空态文案');
assert.ok(reportWxml.includes('{{product.reason}}'), '商品卡必须展示推荐理由');
assert.ok(reportWxml.includes('推荐理由：'), '推荐理由必须有固定文案前缀');
assert.ok(reportWxml.includes('一键加购'), '养生饮茶区必须有整区一键加购入口');
assert.ok(reportWxml.includes('bindtap="openProductDetail"'), '商品卡必须可点击进入商品详情');
assert.ok(
  reportWxml.includes('report-product__detail') && reportWxml.includes('catchtap="openProductDetail"'),
  '每张养生茶饮卡必须有独立的查看详情按钮'
);
assert.ok(reportWxml.includes('report-product__constitutions'), '商品卡必须展示「适宜体质」行');
assert.ok(!reportWxml.includes('report-product--text'), '按参考图口径：匹配不到就不渲染商品，不得再降级为文字卡');
assert.ok(modelJs.includes('product.matched'), '商品卡两态必须依据接口 matched 字段');
assert.ok(modelJs.includes('.filter(product => product && product.matched)'), '只渲染命中商城的商品（匹配不到不显示）');
assert.ok(modelJs.includes('reason: productReason(product.reason)'), '商品卡必须输出清洗后的推荐理由');
assert.ok(modelJs.includes('function productReason'), '推荐理由必须统一去【推荐理由】前缀');
assert.ok(
  modelJs.includes('function filterRenderableBlocks') && modelJs.includes('function blockHasRenderableContent'),
  '模型必须提供空分区过滤'
);
assert.ok(modelJs.includes('filterRenderableBlocks(injectProducts('), 'buildTabs 必须在商品注入后过滤空分区');
assert.ok(
  reportJs.includes('filterRenderableBlocks') && /refreshBlocks[\s\S]*filterRenderableBlocks/.test(reportJs),
  'report.js 重建 blocks 必须复用空分区过滤器'
);
assert.ok(
  reportJs.includes('pendingRecommendationAdd') &&
    reportJs.includes("navigateTo({ url: '/pages/recommendation-stores/recommendation-stores' })"),
  '一键加购必须进入独立门店页并交接推荐商品'
);
assert.ok(
  modelJs.includes('profileSummaryOf') && modelJs.includes('report.sex') && modelJs.includes('report.age'),
  '详情头部必须优先使用接口下发的检测时性别年龄'
);
assert.ok(
  historyJs.includes('report.sex') && historyJs.includes('report.age'),
  '记录列表必须优先使用接口下发的检测时性别年龄'
);

// ---- 个人信息改回本地缓存优先，账号资料仅作首次兜底 ----
const tongueProfileJs = read('utils/tongue-profile.js');
const profileSheetJs = read('components/health-profile-sheet/health-profile-sheet.js');
const profileSheetWxml = read('components/health-profile-sheet/health-profile-sheet.wxml');
assert.ok(
  tongueProfileJs.includes("TONGUE_PROFILE_STORAGE_KEY = 'milkTea:tongue-profile'") &&
    tongueProfileJs.includes('wx.getStorageSync') &&
    tongueProfileJs.includes('wx.setStorageSync'),
  '舌诊资料必须使用 milkTea:tongue-profile 本地缓存'
);
assert.ok(
  tongueProfileJs.includes('api.fetchMe') && tongueProfileJs.includes('resolveTongueProfile'),
  '无缓存时必须回退账号个人资料接口，并统一通过 resolveTongueProfile 解析'
);
assert.ok(!tongueProfileJs.includes('birthdayOfYear'), '舌诊资料不得再折算生日写回用户表');
assert.ok(
  tongueJs.includes('resolveTongueProfile') && tongueJs.includes('saveTongueProfile'),
  '弹层必须加载解析后的资料并只保存舌诊本地缓存'
);
assert.ok(
  !tongueJs.includes('updateProfileFields') && !tongueJs.includes('nickName: user.nickName'),
  '舌诊弹层不得写回用户表或整份覆盖个人资料'
);
assert.ok(!tongueJs.includes('birthYearLocked'), '本地舌诊资料的出生年必须始终可修改');
assert.ok(!profileSheetJs.includes('birthYearLocked'), '弹窗不得再维护出生年只读态');
assert.ok(!profileSheetWxml.includes('生日已填写，不可修改'), '弹窗不得展示用户表生日只读提示');
const profileSheetWxss = read('components/health-profile-sheet/health-profile-sheet.wxss');
assert.ok(profileSheetWxml.includes('为推荐更精准，请填写准确信息'), '个人信息弹层必须展示副标题');
assert.ok(
  profileSheetWxml.includes('/assets/icons/lucide/profile-male.svg') &&
    profileSheetWxml.includes('/assets/icons/lucide/profile-female.svg'),
  '男女选项必须使用本地 Lucide 头像图标'
);
const lucideSyncJs = read('scripts/sync-lucide-icons.mjs');
assert.ok(
  /source: 'mars', output: 'profile-male'/.test(lucideSyncJs) &&
    /source: 'venus', output: 'profile-female'/.test(lucideSyncJs),
  '男女图标必须使用 Lucide 标签库中的 mars / venus'
);
assert.ok(profileSheetWxml.includes('profile-sheet__birth-row'), '出生年必须保留行内选择卡');
assert.ok(
  profileSheetWxml.includes('<picker') &&
    profileSheetWxml.includes('mode="selector"') &&
    profileSheetWxml.includes('range="{{birthYearOptions}}"') &&
    profileSheetWxml.includes('value="{{birthYearIndex}}"') &&
    profileSheetWxml.includes('bindchange="handleYearChange"'),
  '出生年必须使用原生单列年份滚轮'
);
assert.ok(!profileSheetWxml.includes('profile-sheet__input'), '出生年不得继续使用手输 input');
assert.ok(!profileSheetJs.includes('handleYearInput'), '出生年不得保留手输处理逻辑');
assert.ok(profileSheetJs.includes('handleYearChange'), '年份滚轮必须提供选择回调');
assert.ok(profileSheetJs.includes('birthYearOptions'), '年份滚轮必须生成完整年份范围');
const profilePanelRule = (profileSheetWxss.match(/\.profile-sheet__panel\s*\{([\s\S]*?)\}/) || [])[1] || '';
assert.ok(profilePanelRule.includes('width: 620rpx;'), '个人信息面板宽度必须按参考图固化');
assert.ok(
  profilePanelRule.includes('top: 50%;') && profilePanelRule.includes('transform: translate(-50%, -50%);'),
  '个人信息面板必须按参考图居中'
);
assert.ok(profilePanelRule.includes('box-sizing: border-box;'), '个人信息面板必须使用 border-box，避免内边距撑大宽度');
assert.ok(profilePanelRule.includes('z-index: 2;'), '个人信息面板必须位于遮罩之上');
assert.ok(!/\.profile-sheet__panel\s*\{[^}]*bottom:\s*0/.test(profileSheetWxss), '个人信息面板不得再贴底');
assert.ok(/\.profile-sheet__mask\s*\{[\s\S]*?z-index:\s*1/.test(profileSheetWxss), '遮罩必须位于面板下层');
assert.ok(/\.profile-sheet__option\s*\{[\s\S]*?height:\s*176rpx/.test(profileSheetWxss), '性别卡高度必须按参考图固化');
assert.ok(
  /\.profile-sheet__birth-row\s*\{[\s\S]*?height:\s*128rpx/.test(profileSheetWxss),
  '出生年卡高度必须按参考图固化'
);
const profileBirthPickerRule =
  (profileSheetWxss.match(/\.profile-sheet__birth-picker\s*\{([\s\S]*?)\}/) || [])[1] || '';
assert.ok(
  profileBirthPickerRule.includes('display: flex;') &&
    profileBirthPickerRule.includes('align-self: stretch;') &&
    profileBirthPickerRule.includes('align-items: center;') &&
    profileBirthPickerRule.includes('justify-content: flex-end;'),
  '出生年选择器必须撑满行高并将内容靠最右侧'
);
const profileBirthValueRule = (profileSheetWxss.match(/\.profile-sheet__birth-value\s*\{([\s\S]*?)\}/) || [])[1] || '';
assert.ok(
  profileBirthValueRule.includes('align-items: center;') && profileBirthValueRule.includes('justify-content: center;'),
  '年份和箭头必须在自身内容区域内水平垂直居中'
);
assert.ok(
  /\.profile-sheet__submit\s*\{[\s\S]*?background:\s*var\(--health-cta-to\)/.test(profileSheetWxss),
  '保存按钮必须使用参考图浅绿色'
);
assert.ok(!historyJs.includes('tongueProfile'), '记录页不得回落本次弹层资料');
assert.ok(!modelJs.includes('tongueProfile'), '渲染模型不得引用本次弹层资料');
// ---- WXSS 约束：只用设计 token，且舌诊页统一使用渐变背景 token ----
for (const rel of [
  'pages/tongue/tongue.wxss',
  'packageHealth/pages/capture/capture.wxss',
  'packageHealth/pages/report/report.wxss',
  'packageHealth/pages/history/history.wxss',
  'components/health-profile-sheet/health-profile-sheet.wxss'
]) {
  const css = read(rel);
  assert.ok(css.includes('var(--'), `${rel} 必须使用设计 token`);
  assert.ok(!/#[0-9a-f]{3,8}/i.test(css), `${rel} 不允许写死色值`);
  assert.ok(!/\b\d+px\b/.test(css), `${rel} 不允许使用 px 单位`);
}
for (const rel of [
  'pages/tongue/tongue.wxss',
  'packageHealth/pages/capture/capture.wxss',
  'packageHealth/pages/report/report.wxss',
  'packageHealth/pages/history/history.wxss'
]) {
  const css = read(rel);
  assert.ok(css.includes('var(--health-gradient-top)'), `${rel} 必须使用舌诊渐变背景 token`);
}

// ---- 图标与素材 ----
for (const icon of [
  'camera.svg',
  'image.svg',
  'scan-face-brand.svg',
  'coffee-brand.svg',
  'glass-water.svg',
  'list-muted.svg',
  'play-white.svg',
  'tongue.svg',
  'tongue-active.svg'
]) {
  assert.ok(exists(`assets/icons/lucide/${icon}`), `缺少 Lucide 图标 ${icon}`);
}

// ---- 隐私：不得出现第三方域名或密钥 ----
for (const rel of [
  'utils/api.js',
  'pages/tongue/tongue.js',
  'packageHealth/pages/capture/capture.js',
  'packageHealth/pages/report/report.js',
  'packageHealth/pages/history/history.js'
]) {
  const source = read(rel);
  assert.ok(!/appSecret|tcmbrain|linkhealth/i.test(source), `${rel} 不得包含第三方密钥或域名`);
}

console.log('tongue-detection.test.mjs: passed');
