const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');

s = s.replace(
  "const mock = require('../../user-h5/data/mock.js');\nconst catalog = require('./data/menu-catalog.cjs');",
  [
    "const catalog = require('./data/menu-catalog.cjs');",
    "",
    "// 积分商品 / 礼品卡等「展示元数据」原先只存在于 user-h5/data/mock.js。",
    "// 阶段 C 后 mock.js 已清空业务数据，这里改为内置常量，生成结果落到 V16。",
    "const POINTS_PRODUCTS = [",
    "  { id: 'points-pet-food', category: 'pet', purchaseLimit: 0, displayType: 'fixed', amount: 0, condition: '不限', badgeInImage: false },",
    "  { id: 'points-matcha-buy-one', category: 'coupon', purchaseLimit: 1, displayType: 'buyone', amount: 0, condition: '不限', badgeInImage: false },",
    "  { id: 'points-single-cup', category: 'coupon', purchaseLimit: 1, displayType: 'fixed', amount: 3, condition: '不限', badgeInImage: true },",
    "  { id: 'points-second-cup-half', category: 'coupon', purchaseLimit: 1, displayType: 'halfprice', amount: 0, condition: '不限', badgeInImage: true }",
    "];",
    "",
    "const GIFT_CARD_GROUPS = [",
    "  { id: 'popular', title: '人气礼品卡', cards: [",
    "    { id: 'gift-001', name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg' },",
    "    { id: 'gift-002', name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg' }",
    "  ] },",
    "  { id: 'limited', title: '限定心意卡', cards: [",
    "    { id: 'gift-003', name: '限定心意', image: '/assets/images/3x/gift-card-limited.jpg' }",
    "  ] }",
    "];",
    "",
    "const GIFT_CARD_DENOMINATIONS = [",
    "  { faceValue: 100, salePrice: 100 },",
    "  { faceValue: 200, salePrice: 200 },",
    "  { faceValue: 500, salePrice: 500 }",
    "];"
  ].join('\n')
);
fs.writeFileSync(p, s, 'utf8');
console.log('step1 done:', !s.includes('user-h5/data/mock.js'));
