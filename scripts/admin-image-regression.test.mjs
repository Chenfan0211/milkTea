import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

const imageViews = [
  'src/views/_shared/AdminListPage.vue',
  'src/views/mall/product/index.vue',
  'src/views/mall/product/ProductImageUpload.vue',
  'src/views/mall/category/index.vue',
  'src/views/marketing/gift/index.vue',
  'src/views/marketing/gift/GiftCardEditor.vue',
  'src/views/trade/order/index.vue',
  'src/views/trade/order-detail/index.vue',
  'src/views/product/list/index.vue',
  'src/views/marketing/coupon/index.vue',
  'src/views/marketing/points/index.vue',
  'src/views/user/list/index.vue',
  'src/views/system/home-decor/index.vue',
  'src/views/system/home-decor/HomeBannerEditor.vue',
  'src/views/marketing/profile-banner/index.vue',
  'src/views/marketing/profile-banner/ProfileBannerEditor.vue'
];

for (const file of imageViews) {
  const source = read(file);
  assert.ok(
    /import\s*\{[^}]*useAdminImageUrl[^}]*\}\s*from\s*['"](?:@\/views\/_shared\/use-admin-image-url|\.\/use-admin-image-url)['"]/.test(
      source
    ),
    `${file} 必须使用统一的后台图片地址解析器`
  );
  assert.match(source, /const\s+adminImageUrl\s*=\s*useAdminImageUrl\(\)/, `${file} 必须创建图片地址解析器`);
  assert.match(source, /fallback(?:Src|-src)/, `${file} 必须为加载失败的图片提供占位图`);
}

const packageJson = JSON.parse(read('package.json'));
for (const command of ['dev', 'dev:prod', 'build', 'build:test', 'build:prod-ip']) {
  assert.match(
    packageJson.scripts[command],
    /sync-admin-images\.mjs/,
    `${command} 必须先同步小程序静态素材到后台 public`
  );
}

const crudApi = read('src/service/api/crud.ts');
const compression = read('src/utils/image-compression.ts');
assert.match(crudApi, /compressAdminImage\(file\)/, '所有管理员图片上传必须在统一 API 入口自动压缩');
assert.match(crudApi, /data\.append\(['"]file['"], uploadFile\)/, '上传 API 必须发送压缩后的文件');
assert.match(compression, /500 \* 1024/, '管理员图片压缩目标必须为 500KB');
assert.match(compression, /5 \* 1024 \* 1024/, '管理员图片原文件上限必须为 5MB');
assert.match(compression, /DIMENSION_FACTORS = \[1, 0\.9, 0\.8, 0\.7\]/, '质量压缩不足时最多缩小到 70% 长边');

const fileConfig = read('file-service/src/main/resources/application.yml');
assert.match(fileConfig, /FILE_MAX_IMAGE_DIMENSION:16000/, '文件服务必须支持商品详情长图高度');
assert.match(fileConfig, /FILE_MAX_IMAGE_PIXELS:40000000/, '长图放行不能取消总像素上限');

const md5 = file => crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
const requiredAssets = [
  'mall-category-placeholder.jpg',
  'mall-category-tea-goods.jpg',
  'mall-category-living-goods.jpg',
  'mall-category-gift-box.jpg',
  'gift-card-matcha.jpg',
  'gift-card-jasmine.jpg',
  'gift-card-limited.jpg',
  'home-hero.jpg',
  'profile-banner.jpg',
  'profile-avatar.jpg'
];

for (const name of requiredAssets) {
  const source = path.join(root, 'user-h5/assets/images/3x', name);
  const target = path.join(root, 'public/assets/images/3x', name);
  assert.ok(fs.existsSync(source), `小程序素材不存在: ${name}`);
  assert.ok(fs.existsSync(target), `后台缺少静态素材: ${name}`);
  assert.equal(md5(target), md5(source), `后台静态素材与小程序不一致: ${name}`);
}

console.log('admin-image-regression.test.mjs: passed');
