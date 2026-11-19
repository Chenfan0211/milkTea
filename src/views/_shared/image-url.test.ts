import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveAdminImageUrl } from './image-url';

test('首页装修预览使用 API 域名读取上传的相对图片地址', () => {
  const stored = '/api/v1/files/public/5dac1b753cdb4ee5b317d2470583dfd7.jpg';
  assert.equal(
    resolveAdminImageUrl(stored, 'https://api.wulingshiguang.top/'),
    'https://api.wulingshiguang.top/api/v1/files/public/5dac1b753cdb4ee5b317d2470583dfd7.jpg'
  );
  assert.equal(resolveAdminImageUrl(stored, '/proxy-default'), `/proxy-default${stored}`);
});

test('已有完整地址保持原样，空地址不发起预览', () => {
  const absolute = 'https://cdn.example.com/banner.jpg';
  assert.equal(resolveAdminImageUrl(absolute, 'https://api.wulingshiguang.top'), absolute);
  assert.equal(resolveAdminImageUrl('', 'https://api.wulingshiguang.top'), '');
});

test('后台静态资源和内嵌图片保持在当前页面上下文', () => {
  const api = 'https://api.wulingshiguang.top';
  assert.equal(
    resolveAdminImageUrl('/assets/images/3x/mall-category-tea-goods.jpg', api),
    '/assets/images/3x/mall-category-tea-goods.jpg'
  );
  assert.equal(resolveAdminImageUrl('data:image/png;base64,AAAA', api), 'data:image/png;base64,AAAA');
  assert.equal(resolveAdminImageUrl('blob:https://example.test/1', api), 'blob:https://example.test/1');
});
