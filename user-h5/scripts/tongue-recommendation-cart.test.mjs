import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const recommendation = require(path.join(root, 'utils/recommendation-cart.js'));
const cart = require(path.join(root, 'utils/cart.js'));
const productListing = require(path.join(root, 'utils/product-listing.js'));

function menuWith(products) {
  return [
    {
      id: 'menu',
      label: '点单',
      groups: [
        {
          id: 'all',
          label: '全部',
          categories: [{ id: 'tea', label: '茶饮', products }]
        }
      ]
    }
  ];
}

function product(id, name, extra = {}) {
  return Object.assign(
    {
      id,
      name,
      price: 1290,
      originalPrice: 1290,
      storedValuePrice: 0,
      image: 'https://cdn.example.com/' + id + '.jpg'
    },
    extra
  );
}

// ---- 1) 归一化：只保留已匹配商城的推荐商品 ----
const normalized = recommendation.normalizeRequestItems([
  { productId: 'p1', name: '滇绿', image: 'a.jpg', reason: '清热', priceMode: 'sale' },
  { goodsId: 'vendor-only', name: '未匹配' },
  { productId: '', name: '空编号' }
]);
assert.deepEqual(normalized, [{ productId: 'p1', name: '滇绿', image: 'a.jpg', reason: '清热', priceMode: 'sale' }]);
const deduped = recommendation.normalizeRequestItems([
  { productId: 'p1', name: '滇绿' },
  { productId: 'p1', name: '重复匹配' }
]);
assert.equal(deduped.length, 1, '同一商城商品的重复推荐只保留一次');

// ---- 2) 门店可售探测：all / missing / unknown 三态，且探测失败不能误判缺货 ----
const stores = [
  { id: 's-all', subjectId: 11, distanceValue: 1.2 },
  { id: 's-missing', subjectId: 22, distanceValue: 2.5 },
  { id: 's-fail', subjectId: 33, distanceValue: 3.1 }
];
let inflight = 0;
let maxInflight = 0;
const menuByStore = {
  11: menuWith([product('p1', '滇绿'), product('p2', '葛根枳椇茶')]),
  22: menuWith([product('p1', '滇绿')])
};
const probe = await recommendation.probeStoreAvailability(stores, ['p1', 'p2'], {
  concurrency: 2,
  cache: new Map(),
  fetchMenu: subjectId => {
    inflight += 1;
    maxInflight = Math.max(maxInflight, inflight);
    return new Promise((resolve, reject) => {
      setTimeout(() => {
        inflight -= 1;
        if (subjectId === 33) {
          reject(new Error('network down'));
          return;
        }
        resolve(menuByStore[subjectId]);
      }, 5);
    });
  }
});
assert.equal(maxInflight <= 2, true, '探测并发不得超过配置上限');
assert.equal(probe.get('s-all').status, 'all');
assert.equal(probe.get('s-missing').status, 'missing');
assert.deepEqual(probe.get('s-missing').missingProductIds, ['p2']);
assert.equal(probe.get('s-fail').status, 'unknown', '探测失败必须记 unknown，不能当成缺货');
assert.equal(probe.get('s-all').availableCount, 2, '全有售必须记录有售数量');
assert.equal(probe.get('s-all').totalCount, 2, '探测结果必须记录推荐总数');
assert.equal(probe.get('s-missing').availableCount, 1, '部分缺货必须记录有售数量');
assert.equal(probe.get('s-fail').availableCount, null, '探测失败的有售数量必须未知');
assert.equal(probe.get('s-fail').totalCount, 2, '探测失败也要记录推荐总数');

// ---- 3) 排序与徽标：5公里内有售优先（数量降序→距离），其余按距离 ----
const annotated = recommendation.annotateAndSortStores(
  [
    { id: 's-2of2', subjectId: 1, distanceValue: 4.2 },
    { id: 's-1of2', subjectId: 2, distanceValue: 1.1 },
    { id: 's-1of2-near', subjectId: 3, distanceValue: 0.8 },
    { id: 's-boundary-in', subjectId: 4, distanceValue: 5.0 },
    { id: 's-0of2', subjectId: 5, distanceValue: 0.5 },
    { id: 's-unknown', subjectId: 6, distanceValue: 2.2 },
    { id: 's-unprobed', subjectId: 7, distanceValue: 6.6 },
    { id: 's-boundary-out', subjectId: 8, distanceValue: 5.1 },
    { id: 's-far-2of2', subjectId: 9, distanceValue: 25.0 }
  ],
  new Map([
    ['s-2of2', { status: 'all', availableCount: 2, totalCount: 2, missingProductIds: [] }],
    ['s-1of2', { status: 'missing', availableCount: 1, totalCount: 2, missingProductIds: ['p2'] }],
    ['s-1of2-near', { status: 'missing', availableCount: 1, totalCount: 2, missingProductIds: ['p2'] }],
    ['s-boundary-in', { status: 'missing', availableCount: 1, totalCount: 2, missingProductIds: ['p2'] }],
    ['s-0of2', { status: 'missing', availableCount: 0, totalCount: 2, missingProductIds: ['p1', 'p2'] }],
    ['s-unknown', { status: 'unknown', availableCount: null, totalCount: 2, missingProductIds: [] }],
    ['s-boundary-out', { status: 'all', availableCount: 2, totalCount: 2, missingProductIds: [] }],
    ['s-far-2of2', { status: 'all', availableCount: 2, totalCount: 2, missingProductIds: [] }]
  ])
);
assert.deepEqual(
  annotated.map(store => store.id),
  [
    's-2of2',
    's-1of2-near',
    's-1of2',
    's-boundary-in',
    's-0of2',
    's-unknown',
    's-boundary-out',
    's-unprobed',
    's-far-2of2'
  ],
  '5公里内有售门店必须优先；组内数量降序、数量相同按距离；0可售与5公里外按距离'
);
assert.equal(annotated[0].recommendationBadge.text, '推荐商品 2/2 有售');
assert.equal(annotated[0].recommendationBadge.tone, 'success');
assert.equal(annotated[1].recommendationBadge.text, '推荐商品 1/2 有售');
assert.equal(annotated[1].recommendationBadge.tone, 'warning');
assert.equal(annotated[3].recommendationPriority, true, '5.0 公里必须包含在优先范围内');
assert.equal(annotated[3].recommendationBadge.text, '推荐商品 1/2 有售');
assert.equal(annotated[4].recommendationBadge.text, '推荐商品 0/2', '0 可售必须显示数量且落在后面');
assert.equal(annotated[4].recommendationPriority, false, '0 可售不得进入优先组');
assert.equal(annotated[5].recommendationBadge.text, '可售待确认', '探测失败显示待确认，不当成 0 可售');
assert.equal(annotated[6].recommendationPriority, false, '超过5公里即使有售也不进入优先组');
assert.equal(annotated[6].recommendationBadge, null, '超过5公里不展示可售徽标');
assert.equal(annotated[7].recommendationStatus, '', '未探测门店不得伪造可售状态');
assert.equal(annotated[7].recommendationBadge, null, '未探测门店不展示可售徽标');
assert.equal(annotated[8].recommendationPriority, false, '25公里门店只能按距离排序');

// ---- 4) 一键加购条目：默认规格 + 缺货清单 ----
const built = recommendation.buildRecommendationCartItems(
  menuWith([
    product('p1', '滇绿', {
      specGroups: [
        {
          id: 'size',
          label: '杯型',
          options: [
            { id: 'small', label: '小杯', priceDelta: 0 },
            { id: 'large', label: '大杯', priceDelta: 300, selected: true }
          ]
        }
      ]
    }),
    product('p2', '葛根枳椇茶')
  ]),
  [
    { productId: 'p1', name: '滇绿' },
    { productId: 'p2', name: '葛根枳椇茶' },
    { productId: 'p9', name: '缺货茶' }
  ]
);
assert.equal(built.items.length, 2);
assert.equal(built.items[0].id, 'p1-large', '必须优先使用后端标记 selected 的默认规格');
assert.equal(built.items[0].spec, '大杯');
assert.equal(built.items[0].price, 15.9, '默认规格加价必须计入会员价');
assert.deepEqual(
  built.missing.map(item => item.name),
  ['缺货茶']
);

// ---- 5) 购物车合并：同商品同规格累加数量，不重复插入 ----
const merged = recommendation.mergeRecommendationItems(built.items, [
  Object.assign({}, built.items[0], { quantity: 2 })
]);
assert.equal(merged.length, 2, '同规格合并后不得新增条目');
assert.equal(merged[0].quantity, 3);

// ---- 6) 换店后旧下架条目取消勾选但不删除 ----
const relisted = cart.applyStoreListing(merged, ['p1']);
assert.equal(relisted.length, 2);
assert.equal(relisted[0].listed, true);
assert.equal(relisted[1].listed, false);
assert.equal(relisted[1].selected, false, '下架条目必须取消勾选，避免阻塞结算');
assert.equal(merged[1].selected, true, '不得原地修改原数组');

// ---- 7) 探测不得污染点单页的全局菜单镜像 ----
const source = fs.readFileSync(path.join(root, 'utils/recommendation-cart.js'), 'utf8');
assert.ok(!/require\(['"]\.\/product-listing/.test(source), '推荐探测不得依赖 product-listing 镜像模块');
assert.ok(!/refreshMenuFromRemote\s*\(/.test(source), '推荐探测不得调用会覆盖镜像的 refreshMenuFromRemote');
assert.equal(typeof productListing.getMenuCatalog, 'function');

// ---- 8) 立即购买 / 详情购物车：按指定规格和数量在门店菜单重建条目 ----
const selectedMenu = menuWith([
  product('p1', '滇绿', {
    price: 1290,
    originalPrice: 1500,
    specGroups: [
      {
        id: 'size',
        label: '杯型',
        options: [
          { id: 'small', label: '小杯', priceDelta: 0 },
          { id: 'large', label: '大杯', priceDelta: 300, selected: true }
        ]
      },
      {
        id: 'topping',
        label: '加料（必选1份）',
        options: [
          { id: 'none', label: '不加马蹄粉圆', priceDelta: 0, selected: true },
          { id: 'horseshoe', label: '加马蹄粉圆', priceDelta: 200 }
        ]
      }
    ]
  })
]);
const selectedBuilt = recommendation.buildSelectedRecommendationCartItems(selectedMenu, [
  {
    productId: 'p1',
    name: '滇绿',
    selectedOptionIds: ['small', 'horseshoe'],
    quantity: 3,
    priceMode: 'sale',
    cartItemId: 'cart-p1-small'
  },
  { productId: 'missing', name: '缺货茶', selectedOptionIds: [], quantity: 1, cartItemId: 'cart-missing' }
]);
assert.equal(selectedBuilt.items.length, 1);
assert.equal(selectedBuilt.items[0].id, 'p1-small-horseshoe', '必须保留用户选择的小杯和加料规格');
assert.equal(selectedBuilt.items[0].quantity, 3, '必须保留用户选择的数量');
assert.equal(selectedBuilt.items[0].price, 14.9, 'sale 模式必须保留最终售价并叠加加料');
assert.equal(selectedBuilt.items[0].priceMode, 'sale', '门店重建不得丢失最终售价模式');
assert.equal(selectedBuilt.items[0].cartItemId, 'cart-p1-small', '必须保留详情购物车原条目 ID');
assert.deepEqual(
  selectedBuilt.missing.map(item => item.cartItemId),
  ['cart-missing']
);

const legacyBuilt = recommendation.buildSelectedRecommendationCartItems(selectedMenu, [
  { productId: 'p1', name: '滇绿', selectedOptionIds: ['small'], quantity: 1 }
]);
assert.equal(legacyBuilt.items[0].price, 15, '默认会员价模式必须保持原有价格口径');
console.log('tongue-recommendation-cart.test.mjs: passed');
