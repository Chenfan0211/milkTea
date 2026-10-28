import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDir, '../../..');

function pathOf(relativePath) {
  return resolve(projectRoot, relativePath);
}

function read(relativePath) {
  const absolutePath = pathOf(relativePath);
  assert.equal(existsSync(absolutePath), true, `missing ${relativePath}`);
  return readFileSync(absolutePath, 'utf8');
}

test('mall category admin exposes direct upload with square and 5MB validation', () => {
  const page = read('src/views/mall/category/index.vue');
  const shell = read('src/views/_shared/AdminListPage.vue');

  assert.match(page, /uploadImage/, '分类表单必须使用 uploadImage 字段');
  assert.match(page, /iconImage/, '分类页面必须读写 iconImage');
  assert.match(page, /1:1/, '分类图片必须提示 1:1 规格');
  assert.match(page, /5\s*\*\s*1024\s*\*\s*1024/, '分类图片必须限制 5MB');
  assert.match(shell, /移除图片/, '编辑分类必须支持移除图片');
  assert.match(shell, /field\.type === 'uploadImage'/, '通用表单必须实现 uploadImage 分支');
  assert.match(shell, /uploadAdminImage/, '分类图片必须走真实文件服务上传');
});

test('mall category thumbnail and edit form include icon image', () => {
  const page = read('src/views/mall/category/index.vue');
  assert.match(page, /title: '分类图'/);
  assert.match(page, /iconImage: row\.iconImage/, '编辑表单必须回填图片');
  assert.match(page, /iconImage:\s*String\(data\.iconImage/, '提交必须归一化图片地址');
});
