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
