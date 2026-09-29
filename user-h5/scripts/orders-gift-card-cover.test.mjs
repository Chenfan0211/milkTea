import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const orders = require(path.join(root, 'utils/orders.js'));
const giftCard = require(path.join(root, 'utils/gift-card.js'));
const config = require(path.join(root, 'config.js'));

/**
 * 订单列表「礼品卡订单」封面 / 标题回归。
 *
 * 背景（真实故障）：礼品卡订单没有 items，卡面与卡名存放在 cardImage / cardName。
 * decorateOrder 的 coverImage 取值链原先只为储值订单写了兜底，
 * 礼品卡于是落到空串，<image src=""> 在订单列表渲染成灰色空块；
 * title 同时退化成泛称「订单」。
 *
 * 锁定：礼品卡封面必须取自卡面，且任何情况下都不得为空串。
 */

function decorateGiftCardOrder(overrides) {
  const remote = Object.assign(
    {
      id: 2,
      orderNo: 'GC202609220010001',
      category: 'gift-card',
      denominationId: 4,
      amount: 10000,
      payStatus: 'PAID',
      status: 'PAID',
      createTime: '2026-09-22 10:30:00'
    },
    overrides
  );
  orders.setOrdersForTest([orders.normalizeAuxOrder(remote, 'gift-card')]);
  return orders.decorateOrder(orders.getOrders()[0], Date.now());
}

// 1) 有卡面：封面必须是该卡面，标题必须是卡名
const withImage = decorateGiftCardOrder({
  cardName: '春日抹茶卡',
  cardImage: '/assets/images/3x/gift-card-matcha.jpg'
});
assert.equal(
  withImage.coverImage,
  '/assets/images/3x/gift-card-matcha.jpg',
  '礼品卡封面必须取自 cardImage'
);
assert.equal(withImage.title, '春日抹茶卡', '礼品卡标题必须取自 cardName，不能退化成「订单」');

// 2) 缺卡面：必须回落到默认卡面，绝不能是空串（空 src 会渲染成灰块）
const withoutImage = decorateGiftCardOrder({ cardName: '春日抹茶卡' });
assert.notEqual(withoutImage.coverImage, '', '卡面缺失时封面不得为空串');
assert.equal(
  withoutImage.coverImage,
  giftCard.DEFAULT_GIFT_CARD_IMAGE,
  '卡面缺失时必须回落到默认卡面'
);

// 3) 后台远程卡面：必须解析成小程序可访问的绝对地址
const remoteImage = '/api/v1/files/public/11111111-1111-1111-1111-111111111111.jpg';
const withRemoteImage = decorateGiftCardOrder({ cardImage: remoteImage });
assert.equal(
  withRemoteImage.coverImage,
  String(config.BASE_URL || '').replace(/\/+$/, '') + remoteImage,
  '后台远程卡面必须转换为绝对地址'
);

// 4) 无卡名无卡面：两项都要走兜底，不得出现空封面
const bare = decorateGiftCardOrder({});
assert.notEqual(bare.coverImage, '', '字段全缺时封面仍不得为空串');
assert.equal(bare.coverImage, giftCard.DEFAULT_GIFT_CARD_IMAGE);
assert.equal(bare.title, giftCard.DEFAULT_GIFT_CARD_NAME, '字段全缺时标题回落到默认卡名');

// 5) 门店订单不受影响：封面仍取首个商品图
orders.setOrdersForTest([
  orders.normalizeAuxOrder(
    {
      id: 9,
      orderNo: 'WX1',
      category: 'store',
      status: 'PAID',
      payStatus: 'PAID',
      createTime: '2026-09-22 10:30:00',
      items: [{ id: 1, name: '青提茉莉冰茶', image: '/assets/images/3x/menu-product.jpg', quantity: 1 }]
    },
    'store'
  )
]);
const storeOrder = orders.decorateOrder(orders.getOrders()[0], Date.now());
assert.equal(
  storeOrder.coverImage,
  '/assets/images/3x/menu-product.jpg',
  '门店订单封面仍取首个商品图，不得被礼品卡分支影响'
);

console.log('礼品卡订单封面与标题（cardImage 解析 + 默认卡面兜底）测试通过');