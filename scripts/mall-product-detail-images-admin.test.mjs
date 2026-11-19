import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');
const exists = relativePath => fs.existsSync(path.join(root, relativePath));

const detailMigration = read('server/src/main/resources/db/migration/V109__mall_product_detail_images.sql');
assert.ok(detailMigration.includes('detail_images'), 'V109 必须新增 detail_images 字段');
assert.ok(detailMigration.includes('JSON'), 'detail_images 必须保存为 JSON 数组');
assert.ok(detailMigration.includes('mall_product'), 'V109 必须修改 mall_product');
assert.ok(detailMigration.includes('gallery_images'), 'V109 必须保留 gallery_images 不受影响');

const seedMigration = read('server/src/main/resources/db/migration/V110__mall_banner_seed.sql');
assert.ok(seedMigration.includes('NOT EXISTS'), 'V110 必须仅在商城轮播为空时初始化');
assert.ok(seedMigration.includes('DEMO-MALL-005'), '初始化轮播必须按商品编码关联演示商品');
assert.ok(seedMigration.includes("THEN 'none'"), '演示商品不存在时必须降级为不跳转');
for (const marker of [
  '/assets/images/3x/profile-banner.jpg',
  '/assets/images/3x/menu-banner.jpg',
  '/assets/images/3x/stored-value-banner.jpg',
  '抹茶轻茉莉随行礼盒',
  '一杯暖身心',
  '金秋入茶限定上新'
]) {
  assert.ok(seedMigration.includes(marker), `V110 缺少初始化内容: ${marker}`);
}

const entity = read('product-service/src/main/java/com/wuling/product/entity/MallProduct.java');
const adminDto = read('product-service/src/main/java/com/wuling/product/dto/MallProductAdminDTO.java');
const detailDto = read('product-service/src/main/java/com/wuling/product/dto/MallProductDetailDTO.java');
const saveRequest = read('product-service/src/main/java/com/wuling/product/dto/MallProductSaveRequest.java');
for (const source of [entity, adminDto, detailDto, saveRequest]) {
  assert.ok(source.includes('detailImages'), '商品模型、DTO 和保存请求必须统一使用 detailImages');
}

assert.ok(exists('src/views/mall/product/ProductDetailImages.vue'), '必须新增详情长图维护组件');
const component = read('src/views/mall/product/ProductDetailImages.vue');
assert.ok(component.includes('uploadAdminImage'), '详情长图必须复用管理员图片上传接口');
assert.ok(component.includes('5 * 1024 * 1024'), '详情长图单张必须限制 5MB');
assert.ok(component.includes('20'), '详情长图必须限制最多 20 张');
assert.ok(component.includes('上移'), '详情长图必须支持上移排序');
assert.ok(component.includes('下移'), '详情长图必须支持下移排序');
assert.ok(component.includes('移除'), '详情长图必须支持移除');
assert.match(component, /:preview-disabled="false"/, '详情长图后台预览必须可查看原图');

const page = read('src/views/mall/product/index.vue');
assert.ok(page.includes('ProductDetailImages'), '商品编辑抽屉必须接入详情长图组件');
assert.ok(page.includes('detailImages:'), '商品表单状态必须包含 detailImages');
assert.ok(page.includes('form.detailImages'), '商品保存必须提交当前表单中的详情长图');
assert.ok(page.includes('detail.detailImages'), '编辑商品时必须加载后端详情长图');
assert.ok(page.includes('详情长图'), '后台必须展示详情长图字段标题');

const api = read('src/service/api/mall.ts');
assert.ok(api.includes('detailImages?: string[]'), '后台 API 类型必须声明 detailImages');

const docs = read('docs/data-schema.md');
assert.ok(docs.includes('detail_images'), '数据规范必须记录商品详情长图字段');

console.log('mall-product-detail-images-admin.test.mjs: passed');
