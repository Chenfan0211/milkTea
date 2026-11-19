import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');
const exists = relativePath => fs.existsSync(path.join(root, relativePath));

assert.ok(exists('src/views/mall/product/ProductGalleryImages.vue'), '必须新增商品多主图编辑器');
const component = read('src/views/mall/product/ProductGalleryImages.vue');
assert.match(component, /MAX_GALLERY_IMAGES\s*=\s*10/, '商品主图最多只能维护 10 张');
assert.match(component, /uploadAdminImage/, '商品主图必须复用统一上传与压缩接口');
assert.match(component, /封面/, '第一张商品主图必须标记为封面');
assert.match(component, /上移/, '商品主图必须支持上移排序');
assert.match(component, /下移/, '商品主图必须支持下移排序');
assert.match(component, /移除/, '商品主图必须支持移除');

const page = read('src/views/mall/product/index.vue');
assert.match(page, /ProductGalleryImages/, '商品表单必须接入多主图编辑器');
assert.match(page, /galleryImages:\s*string\[\]/, '商品表单状态必须包含 galleryImages');
assert.match(page, /detail\.galleryImages/, '编辑商品时必须加载已有主图');
assert.match(page, /detail\.coverImage/, '旧商品没有多图时必须使用封面图初始化');
assert.match(page, /form\.galleryImages/, '保存时必须使用独立表单主图状态');
assert.doesNotMatch(page, /ProductImageUpload v-model="form\.coverImage"/, '不应继续保留单独主图上传入口');
assert.match(page, /至少上传一张商品主图/, '保存商品前必须校验至少一张主图');
assert.match(page, /商品主图轮播/, '后台必须展示多主图字段标题');

const api = read('src/service/api/mall.ts');
assert.equal((api.match(/galleryImages\?: string\[\]/g) || []).length >= 2, true, '后台商品详情和保存类型必须支持多图');

const cardDto = read('product-service/src/main/java/com/wuling/product/dto/MallProductCardDTO.java');
assert.match(cardDto, /List<String> galleryImages/, '商城列表 DTO 必须返回多主图');

console.log('mall-product-gallery-admin.test.mjs: passed');
