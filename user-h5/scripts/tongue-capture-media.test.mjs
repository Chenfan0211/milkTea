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
assert.match(
  capturePageRule,
  /background-image:\s*var\(--health-page-gradient\)/,
  '拍摄页必须引用完整的舌诊渐变 token'
);

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
