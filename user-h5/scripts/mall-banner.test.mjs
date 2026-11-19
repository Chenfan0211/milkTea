import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mallBanner = require(path.join(root, 'utils/mall-banner.js'));

const resolveAssetUrl = value => {
  const raw = String(value || '').trim();
  return raw.indexOf('/api/') === 0 ? `https://api.example.com${raw}` : raw;
};

assert.deepEqual(
  mallBanner.normalizeBanners(
    {
      banners: [
        {
          id: 2,
          imageUrl: '/api/v1/files/public/first.jpg',
          altText: '第一张',
          targetType: 'mallProduct',
          targetId: '27'
        },
        { id: 3, imageUrl: '', altText: '空图', targetType: 'none', targetId: '' },
        {
          id: 4,
          imageUrl: '/assets/images/3x/mall-category-placeholder.jpg',
          altText: '纯展示',
          targetType: 'none',
          targetId: '99'
        },
        {
          id: 5,
          imageUrl: '/assets/images/3x/mall-category-placeholder.jpg',
          altText: '旧跳转',
          targetType: 'menuProduct',
          targetId: '9'
        }
      ]
    },
    resolveAssetUrl
  ),
  [
    {
      id: '2',
      imageUrl: 'https://api.example.com/api/v1/files/public/first.jpg',
      altText: '第一张',
      targetType: 'mallProduct',
      targetId: '27'
    },
    {
      id: '4',
      imageUrl: '/assets/images/3x/mall-category-placeholder.jpg',
      altText: '纯展示',
      targetType: 'none',
      targetId: ''
    },
    {
      id: '5',
      imageUrl: '/assets/images/3x/mall-category-placeholder.jpg',
      altText: '旧跳转',
      targetType: 'none',
      targetId: ''
    }
  ],
  '商城轮播必须过滤空图，并且只保留不跳转和商城商品两种跳转'
);

assert.deepEqual(mallBanner.normalizeBanners({ banners: [] }, resolveAssetUrl), [], '没有配置时必须隐藏轮播');

const remaining = mallBanner.removeFailedBanner(
  [
    { id: 'a', imageUrl: '/a.jpg', altText: 'A' },
    { id: 'b', imageUrl: '/b.jpg', altText: 'B' }
  ],
  0
);
assert.deepEqual(
  remaining.map(item => item.id),
  ['b'],
  '单张图片失败时必须只移除该张'
);
assert.deepEqual(
  mallBanner.removeFailedBanner([{ id: 'only', imageUrl: '/only.jpg', altText: 'Only' }], 0),
  [],
  '全部图片失败时必须隐藏轮播'
);

const availableEvents = [];
const availableResult = await mallBanner.openBannerTarget(
  { targetType: 'mallProduct', targetId: '27' },
  {
    fetchMallProductDetail: async id => {
      availableEvents.push(`detail:${id}`);
      return { id, soldOut: false, availableStock: 5 };
    },
    navigateTo: url => availableEvents.push(`navigate:${url}`),
    showToast: ({ title }) => availableEvents.push(`toast:${title}`)
  }
);
assert.equal(availableResult, 'opened');
assert.deepEqual(availableEvents, ['detail:27', 'navigate:/packageMall/pages/product-detail/product-detail?id=27']);

const unavailableEvents = [];
const unavailableResult = await mallBanner.openBannerTarget(
  { targetType: 'mallProduct', targetId: '28' },
  {
    fetchMallProductDetail: async () => ({ id: 28, soldOut: false, availableStock: 0 }),
    navigateTo: url => unavailableEvents.push(`navigate:${url}`),
    showToast: ({ title }) => unavailableEvents.push(`toast:${title}`)
  }
);
assert.equal(unavailableResult, 'unavailable');
assert.deepEqual(unavailableEvents, ['toast:商品已售罄，看看其他商品']);

const removedEvents = [];
const removedResult = await mallBanner.openBannerTarget(
  { targetType: 'mallProduct', targetId: '29' },
  {
    fetchMallProductDetail: async () => {
      throw new Error('not found');
    },
    navigateTo: url => removedEvents.push(`navigate:${url}`),
    showToast: ({ title }) => removedEvents.push(`toast:${title}`)
  }
);
assert.equal(removedResult, 'unavailable');
assert.deepEqual(removedEvents, ['toast:商品已下架或暂不可用']);

const noopCalls = [];
assert.equal(
  await mallBanner.openBannerTarget(
    { targetType: 'none', targetId: '' },
    {
      fetchMallProductDetail: async () => {
        throw new Error('不应请求商品');
      },
      navigateTo: url => noopCalls.push(`navigate:${url}`),
      showToast: options => noopCalls.push(`toast:${options.title}`)
    }
  ),
  'noop'
);
assert.deepEqual(noopCalls, [], '无跳转轮播不得触发请求或导航');

console.log('mall-banner.test.mjs: passed');
