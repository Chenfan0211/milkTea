import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const captureMedia = require(path.join(root, 'utils/tongue-capture.js'));

// ---- 1) 取消识别：微信取消不应弹错误 ----
assert.equal(captureMedia.isUserCancel({ errMsg: 'chooseMedia:fail cancel' }), true);
assert.equal(captureMedia.isUserCancel({ errMsg: 'chooseImage:fail cancel' }), true);
assert.equal(captureMedia.isUserCancel(new Error('chooseMedia:fail system error')), false);
assert.equal(captureMedia.isUserCancel(null), false);
// ---- 1.1) 开发者工具优先走遗留 chooseImage，真机优先 chooseMedia ----
assert.equal(captureMedia.usesLegacyPickerFirst('devtools'), true);
assert.equal(captureMedia.usesLegacyPickerFirst('ios'), false);
assert.equal(captureMedia.usesLegacyPickerFirst('android'), false);

// ---- 2) 开发者工具拍照降级为相册 ----
assert.deepEqual(captureMedia.effectiveSourceType('devtools', ['camera']), {
  sourceType: ['album'],
  downgraded: true
});
assert.deepEqual(captureMedia.effectiveSourceType('ios', ['camera']), {
  sourceType: ['camera'],
  downgraded: false
});
assert.deepEqual(captureMedia.effectiveSourceType('devtools', ['album']), {
  sourceType: ['album'],
  downgraded: false
});
assert.deepEqual(captureMedia.effectiveSourceType('ios', []), {
  sourceType: ['album'],
  downgraded: false
});

// ---- 3) 失败文案按来源区分 ----
assert.equal(captureMedia.pickFailureMessage(['camera']), '拍照失败，请检查相机权限');
assert.equal(captureMedia.pickFailureMessage(['album']), '选择图片失败，请重试');
assert.equal(
  captureMedia.isPrivacyDeclarationError({
    errMsg: 'chooseMedia:fail api scope is not declared in the privacy agreement'
  }),
  true
);
assert.equal(captureMedia.isPrivacyDenied({ errMsg: 'requirePrivacyAuthorize:fail user deny' }), true);
assert.equal(captureMedia.isCameraPermissionError({ errMsg: 'authorize:fail auth deny' }), true);
assert.equal(
  captureMedia.pickFailureMessage(['album'], {
    errMsg: 'chooseMedia:fail api scope is not declared in the privacy agreement'
  }),
  '隐私声明未配置，请联系管理员'
);
assert.equal(
  captureMedia.pickFailureMessage(['album'], { errMsg: 'requirePrivacyAuthorize:fail user deny' }),
  '需同意隐私授权后才能使用拍照/相册'
);
assert.equal(
  captureMedia.pickFailureMessage(['camera'], { errMsg: 'authorize:fail auth deny' }),
  '相机权限未开启，请前往设置开启'
);
assert.equal(captureMedia.errorMessage({ errMsg: 'chooseMedia:fail cancel' }), 'chooseMedia:fail cancel');
assert.equal(
  captureMedia.describeFailure(['album'], { errMsg: 'chooseImage:fail mock failure' }),
  '选择图片失败，请重试（chooseImage:fail mock failure）'
);
assert.equal(
  captureMedia.describeFailure(['camera'], { errMsg: 'chooseImage:fail deny' }),
  '拍照失败，请检查相机权限（chooseImage:fail deny）'
);

// ---- 4) 拍摄页源码：开发者工具 chooseImage 优先，真机 chooseMedia 优先 ----
const captureJs = fs.readFileSync(path.join(root, 'packageHealth/pages/capture/capture.js'), 'utf8');
const captureWxml = fs.readFileSync(path.join(root, 'packageHealth/pages/capture/capture.wxml'), 'utf8');
const captureWxss = fs.readFileSync(path.join(root, 'packageHealth/pages/capture/capture.wxss'), 'utf8');
assert.ok(captureJs.includes("require('../../../utils/tongue-capture')"), '拍摄页必须复用拍摄兜底工具');
assert.ok(captureJs.includes('wx.showActionSheet'), '必须保留系统拍照/相册选择层');
assert.ok(captureJs.includes('wx.chooseMedia'), '必须保留 chooseMedia 路径');
assert.ok(captureJs.includes('wx.chooseImage'), '必须保留 chooseImage 路径');
assert.ok(captureJs.includes('usesLegacyPickerFirst'), '开发者工具必须优先走遗留 chooseImage');
assert.ok(captureJs.includes('wx.requirePrivacyAuthorize'), '选图前必须触发微信隐私授权');
assert.ok(captureJs.includes('wx.getPrivacySetting'), '选图前必须记录隐私声明状态');
assert.ok(captureJs.includes('privacyContractName'), '隐私诊断必须打印协议名称');
assert.ok(captureJs.includes('服务内容声明'), '隐私声明缺失时必须提示后台配置入口');
assert.ok(captureJs.includes("scope: 'scope.camera'"), '拍照前必须申请相机权限');
assert.ok(captureJs.includes('wx.openSetting'), '相机权限拒绝后必须提供去设置入口');
assert.ok(captureJs.includes('wx.showModal'), '相机权限拒绝后必须提示用户前往设置');
assert.ok(captureJs.includes('runChooseImage') && captureJs.includes('runChooseMedia'), '两种选图 API 必须可互相兜底');
assert.ok(captureJs.includes("sizeType: ['original']"), '选图必须只取原图，压缩交给 wx.compressImage');
assert.ok(!captureJs.includes("['compressed']"), '选图不得再请求 compressed，避免开发者工具失败');
assert.ok(captureJs.includes('captureMedia.isUserCancel'), '取消选择必须被识别且不提示');
assert.ok(captureJs.includes('captureMedia.effectiveSourceType'), '来源必须经 devtools 归一');
assert.ok(captureJs.includes('captureMedia.pickFailureMessage'), '真机失败必须给友好提示');
assert.ok(captureJs.includes('captureMedia.describeFailure'), '开发者工具失败必须带真实 errMsg');
assert.ok(captureJs.includes("console.warn('[capture] 选择图片失败'"), '失败必须写 console 便于定位');
assert.ok(
  captureJs.includes("'[capture] chooseImage 失败'") && captureJs.includes("'[capture] chooseMedia 失败'"),
  '两个 API 的真实错误都必须打日志'
);
assert.ok(captureJs.includes("title: '未能打开拍照/相册，请重试'"), 'action sheet 失败必须有 toast');
assert.ok(captureJs.includes("title: '开发者工具不支持直接拍照，已为你打开相册'"), '开发者工具拍照必须有降级提示');
assert.ok(captureJs.includes("devtoolsHint: '开发者工具不支持直接拍照，请从相册选择'"), '开发者工具页面必须有常驻提示');
assert.ok(!/fail:\s*\(\)\s*=>\s*\{\s*\}/.test(captureJs), '不得再保留空 fail 回调');
assert.ok(
  captureWxml.includes('capture-devtools-hint') && captureWxml.includes('{{devtoolsHint}}'),
  '拍摄页必须渲染开发者工具提示'
);
assert.ok(captureWxss.includes('.capture-devtools-hint'), '拍摄页必须提供开发者工具提示样式');

const capturePageRule = (captureWxss.match(/\.capture-page\s*\{([^}]*)\}/) || [])[1] || '';
assert.match(capturePageRule, /background-color:\s*var\(--health-gradient-top\)/, '拍摄页必须有舌诊绿色背景兜底');
assert.match(capturePageRule, /position:\s*relative/, '拍摄页必须建立背景层级上下文');
assert.match(capturePageRule, /z-index:\s*0/, '拍摄页根节点必须建立背景层级');
assert.ok(!capturePageRule.includes('linear-gradient'), '拍摄页不得继续依赖 Skyline 不支持的 CSS 渐变');
assert.ok(captureWxml.includes('class="health-page__background"'), '拍摄页必须渲染渐变图片层');
assert.ok(captureWxml.includes('src="/assets/images/3x/health-gradient-bg.png"'), '拍摄页必须引用舌诊渐变运行图');
assert.ok(captureWxml.includes('mode="scaleToFill"'), '拍摄页背景图必须铺满页面');
assert.ok(captureWxml.includes('aria-hidden="true"'), '拍摄页背景图必须对辅助技术隐藏');

assert.ok(captureWxml.includes('/assets/images/3x/tongue-capture-guide.jpg'), '空态必须引用 3x 舌象引导图');
assert.ok(!captureWxml.includes('/assets/icons/lucide/camera.svg'), '空态不得继续使用相机图标');
assert.ok(captureWxml.includes('请保证光线充足、舌头未被染色'), '第一条提示必须与参考图一致');
assert.ok(captureWxml.includes('请拍摄完整舌头（包含舌尖）'), '第二条提示必须与参考图一致');
assert.ok(
  captureWxss.includes('.capture-guide__image') &&
    captureWxss.includes('width: 424rpx') &&
    captureWxss.includes('height: 482rpx'),
  '引导图必须按参考图渲染为 424×482rpx'
);
assert.ok(captureWxss.includes('background: var(--dark-fill)'), '提示编号必须使用深色底');
assert.ok(/\.capture-heading\s*\{[\s\S]*?margin-top:\s*72rpx;/.test(captureWxss), '拍摄舌头标题顶部间距必须为 72rpx');
assert.ok(
  /\.capture-heading\s*\{[\s\S]*?font-size:\s*var\(--font-xl\);/.test(captureWxss),
  '拍摄舌头标题必须使用 --font-xl'
);
assert.ok(
  captureWxss.includes('padding: 0 32rpx 16rpx') &&
    /\.capture-guide\s*\{[\s\S]*?margin-top:\s*96rpx;/.test(captureWxss),
  '空态引导图顶部间距必须为 96rpx'
);
assert.ok(
  /\.capture-guide\s*\+\s*\.capture-tips\s*\{[\s\S]*?margin-top:\s*16rpx;/.test(captureWxss),
  '引导图到两条提示的间距必须为 16rpx'
);
console.log('tongue-capture-media.test.mjs: passed');
