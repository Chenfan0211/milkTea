import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { SetupStoreId } from '@/enum';
import {
  crudCreate,
  crudDelete,
  crudUpdate,
  crudPage,
  reviewComment as reviewCommentApi,
  saveReferralConfigApi,
  fetchReferralConfig,
  saveSigninRule as saveSigninRuleApi,
  toggleSplitRule
} from '@/service/api/crud';

/**
 * 远端资源映射：key = store 内部数据键，value = 后端 CrudRegistry 资源名。
 * 配置类页面（系统配置、主体管理）切换到接口模式后，CRUD 直接落到数据库。
 * 未登记的 key 继续走本地数据（如交易/财务等只读或业务动作接口）。
 */
const REMOTE_RESOURCES: Record<string, string> = {
  // 系统配置
  dictEntries: 'dictEntries',
  cities: 'cities',
  provinces: 'provinces',
  features: 'features',
  storeTypes: 'storeTypes',
  // 主体管理
  subjects: 'subjects',
  users: 'users',
  // 商品配置（第3批）
  productCategories: 'productCategories',
  specs: 'specs',
  specOptions: 'specOptions',
  splitRules: 'splitRules',
  // 营销配置（第4批）
  coupons: 'coupons',
  storedValuePackages: 'storedValuePackages',
  pointsProducts: 'pointsProducts',
  pointsEarningRules: 'pointsEarningRules',
  giftCards: 'giftCards',
  giftCardOrders: 'giftCardOrders',
  memberLevels: 'memberLevels',
  // 授权管理
  roles: 'roles',
  grants: 'grants',
  // 交易
  payments: 'payments',
  verifies: 'verifies',
  // 财务
  fundPool: 'fundPool',
  fundFlows: 'fundFlows',
  snapshots: 'snapshots',
  reconciles: 'reconciles',
  subjectAccounts: 'subjectAccounts',
  withdrawals: 'withdrawals',
  // 审核
  roleApplications: 'roleApplications',
  // 系统
  auditLogs: 'auditLogs',
  // 交易只读视图
  orders: 'orders',
  refunds: 'refunds',
  verifyPool: 'verifyPool',
  verifyRecords: 'verifyRecords',
  exchangeRecords: 'exchangeRecords',
  comments: 'comments'
};

/** 是否使用远端接口读取配置类数据（由页面通过 loadRemote 触发） */
const remoteLoaded = ref<Record<string, boolean>>({});

export type RoleType = 'store' | 'investor' | 'resource';

export interface UserRow {
  id: number;
  userId: string;
  nickName: string;
  openId: string;
  avatar: string;
  birthday: string;
  gender: 'male' | 'female' | 'unknown';
  vipLevel: string;
  points: number;
  balance: number;
  businessRole: RoleType | null;
  boundSubjectId: string | null;
  boundSubjectName: string | null;
}

export interface RoleApplication {
  id: number;
  userId: string;
  nickName: string;
  roleType: RoleType;
  status: 'pending' | 'approved' | 'rejected';
  applyTime: string;
  reviewTime: string | null;
  reviewer: string | null;
  subjectId: string | null;
  subjectName: string | null;
}

export interface SubjectRow {
  id: number;
  code: string;
  name: string;
  type: 'store' | 'resource' | 'investor' | 'supplier' | 'platform';
  appid?: string;
  appSecret?: string;
  status: string;
  createTime: string;
}

export interface AdminData {
  [key: string]: any[];
}

const KEY = 'milkTea:admin:data';

function now() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** 返回 n 天前的日期时间字符串，格式 YYYY-MM-DD HH:mm，用于订单 seed 的 createTime 相对化 */
function daysAgo(days: number, hhmm = '00:00') {
  const d = new Date();
  d.setDate(d.getDate() - days);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${hhmm}`;
}

function seed(): AdminData {
  const storeNames = ['五一广场店', '岳麓店', '平和堂店', '梅溪湖店'];
  const resourceNames = ['资源方甲', '资源方乙', '资源方丙'];
  const investorNames = ['投资人甲', '投资人乙', '投资人丙'];
  const supplierNames = ['供应商一', '供应商二', '供应商三'];

  const subjects: any[] = [
    {
      id: 40,
      code: 'PT-1000',
      name: '五零时光运营平台',
      type: 'platform',
      appid: 'wx1234567890abcdef',
      appSecret: '9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c',
      status: 'active',
      createTime: now(),
      deleted: false,
      boundUserId: null,
      boundUserName: null
    },
    ...storeNames.map((name, i) => {
      const addressList = [
        '湖南省长沙市芙蓉区五一大道100号五一广场',
        '湖南省长沙市岳麓区岳麓大道88号',
        '湖南省长沙市芙蓉区平和堂商厦1层',
        '湖南省长沙市岳麓区梅溪湖路188号'
      ];
      const coords = [
        { latitude: 28.1941, longitude: 112.9779 },
        { latitude: 28.2153, longitude: 112.9426 },
        { latitude: 28.1945, longitude: 112.9812 },
        { latitude: 28.1847, longitude: 112.8836 }
      ];
      return {
        id: i + 1,
        code: `ST-${1000 + i}`,
        name,
        type: 'store',
        storeType: ['奶茶/饮品', '便利店', '餐饮', '健身房'][i % 4],
        status: 'open',
        createTime: now(),
        city: '长沙',
        manager: `店长${i + 1}`,
        location: '长沙市',
        address: addressList[i],
        phone: `0731-8888000${i + 1}`,
        latitude: coords[i].latitude,
        longitude: coords[i].longitude,
        investorId: i % 2 === 0 ? `IV-${1000 + i}` : null,
        investorName: i % 2 === 0 ? `投资人${i + 1}` : '未绑定',
        boundUserId: null,
        boundUserName: null
      };
    }),
    ...resourceNames.map((name, i) => ({
      id: 10 + i,
      code: `RS-${1000 + i}`,
      name,
      type: 'resource',
      status: 'active',
      createTime: now(),
      location: ['长沙市', '武汉市', '广州市'][i % 3],
      storeType: ['奶茶/饮品', '便利店', '餐饮'][i % 3],
      boundStoreIds: i % 2 === 0 ? ['ST-1000'] : [],
      boundStoreCount: i % 2 === 0 ? 1 : 0,
      boundUserId: null,
      boundUserName: null
    })),
    ...investorNames.map((name, i) => ({
      id: 20 + i,
      code: `IV-${1000 + i}`,
      name,
      type: 'investor',
      status: 'signed',
      createTime: now(),
      investableStoreCount: 3 + i,
      relatedStore: i % 2 === 0 ? '五一广场店' : '未绑定',
      relatedStoreIds: i % 2 === 0 ? ['ST-1000'] : [],
      signStatus: 'signed',
      boundUserId: null,
      boundUserName: null
    })),
    ...supplierNames.map((name, i) => ({
      id: 30 + i,
      code: `SU-${1000 + i}`,
      name,
      type: 'supplier',
      status: 'active',
      createTime: now(),
      productCount: 5 + i * 2,
      boundUserId: null,
      boundUserName: null
    }))
  ];

  const genders: Array<'male' | 'female' | 'unknown'> = ['male', 'female', 'unknown', 'male', 'female', 'unknown', 'male', 'female'];
  const users: any[] = Array.from({ length: 8 }, (_, i) => ({
    id: i + 1,
    userId: `U${1000 + i}`,
    nickName: `微信用户${i + 1}`,
    openId: `openid_${1000 + i}`,
    avatar: '/assets/images/3x/profile-avatar.jpg',
    birthday: `19${90 + (i % 10)}-0${(i % 9) + 1}-1${i % 9}`,
    gender: genders[i],
    vipLevel: ['时光卡', '星享卡', '挚友卡'][i % 3],
    points: 500 + i * 100,
    balance: 100 + i * 50,
    businessRole: null,
    boundSubjectId: null,
    boundSubjectName: null,
    deleted: false
  }));

  const roleApplications: any[] = [
    {
      id: 1,
      userId: 'U1000',
      nickName: '微信用户1',
      name: '张三',
      phone: '13800000001',
      roleType: 'store',
      status: 'pending',
      applyTime: now(),
      reviewTime: null,
      reviewer: null,
      subjectId: 'ST-1001',
      subjectName: '岳麓店',
      storeName: '岳麓店',
      storeAddress: '长沙市岳麓区'
    },
    {
      id: 2,
      userId: 'U1001',
      nickName: '微信用户2',
      name: '李四',
      phone: '13800000002',
      roleType: 'investor',
      status: 'pending',
      applyTime: now(),
      reviewTime: null,
      reviewer: null,
      subjectId: 'IV-1001',
      subjectName: '投资人乙',
      investLocation: '五一广场',
      investBudget: '500000'
    },
    {
      id: 3,
      userId: 'U1002',
      nickName: '微信用户3',
      name: '王五',
      phone: '13800000003',
      roleType: 'resource',
      status: 'pending',
      applyTime: now(),
      reviewTime: null,
      reviewer: null,
      subjectId: 'RS-1001',
      subjectName: '资源方乙',
      resourceLocation: '长沙市',
      storeType: '便利店'
    }
  ];

  // 订单与小程序 user-h5/data/mock.js 对齐：orderNo/门店/金额(分)/状态枚举（见 docs/data-schema.md）
  const orders: any[] = [
    {
      id: 1,
      orderNo: 'WX202609160001',
      store: '星沙乐运魔方店',
      user: 'U1000',
      summary: '金桂轻乳茶 x1,五窨茉莉抹茶 x1',
      paidAmount: 2100,
      status: 'COMPLETED',
      payStatus: 'PAID',
      pickupCode: 'A026',
      createTime: daysAgo(2, '13:02'),
      split: {
        costTotal: 14,
        itemCount: 2,
        storeShare: 4,
        channelShare: 2,
        investorShare: 0.1,
        platformShare: 0.9,
        base: 1.00
      },
    },
    {
      id: 2,
      orderNo: 'WX202609150012',
      store: '松雅湖吾悦广场店',
      user: 'U1001',
      summary: '陈皮普洱轻乳茶 x1',
      paidAmount: 990,
      status: 'COMPLETED',
      payStatus: 'PAID',
      pickupCode: 'B012',
      createTime: daysAgo(3, '18:22'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 0,
        investorShare: 0,
        platformShare: 0.9,
        base: 0.90
      },
    },
    {
      id: 3,
      orderNo: 'WX202609150008',
      store: '星沙乐运魔方店',
      user: 'U1002',
      summary: '五窨茉莉抹茶 x1,金桂轻乳茶 x1,青提茉莉冰茶 x1,陈皮普洱轻乳茶 x1',
      paidAmount: 4950,
      status: 'COMPLETED',
      payStatus: 'PAID',
      pickupCode: 'A015',
      createTime: daysAgo(3, '11:40'),
      split: {
        costTotal: 28,
        itemCount: 4,
        storeShare: 8,
        channelShare: 4,
        investorShare: 0.95,
        platformShare: 8.55,
        base: 9.50
      },
    },
    {
      id: 4,
      orderNo: 'WX202609140001',
      store: '长沙高铁南站店',
      user: 'U1003',
      summary: '白桃乌龙 x1',
      paidAmount: 1800,
      status: 'COMPLETED',
      payStatus: 'PAID',
      pickupCode: 'C008',
      createTime: daysAgo(4, '19:15'),
      split: {
        costTotal: 8,
        itemCount: 1,
        storeShare: 2,
        channelShare: 1,
        investorShare: 0.7,
        platformShare: 6.3,
        base: 7.00
      },
    },
    {
      id: 5,
      orderNo: 'WX202609180010',
      store: '长沙金茂览秀城店',
      user: 'U1004',
      summary: '抹茶脑袋必喝套餐 x1,红苹果乌龙冰奶 x1',
      paidAmount: 3480,
      status: 'PAID',
      payStatus: 'PAID',
      pickupCode: '0404',
      createTime: daysAgo(0, '09:12'),
      split: {
        costTotal: 14,
        itemCount: 2,
        storeShare: 4,
        channelShare: 2,
        investorShare: 1.48,
        platformShare: 13.32,
        base: 14.80
      },
    },
    {
      id: 6,
      orderNo: 'WX202609180011',
      store: '长沙金茂览秀城店',
      user: 'U1005',
      summary: '抹茶脑袋必喝套餐 x1',
      paidAmount: 1990,
      status: 'PAID',
      payStatus: 'PAID',
      pickupCode: '0405',
      createTime: daysAgo(0, '09:10'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 1,
        investorShare: 0.99,
        platformShare: 8.91,
        base: 9.90
      },
    },
    {
      id: 7,
      orderNo: 'WX202609180001',
      store: '长沙金茂览秀城店',
      user: 'U1006',
      summary: '抹茶芝士芭乐 x1',
      paidAmount: 1890,
      status: 'VERIFIED',
      payStatus: 'PAID',
      pickupCode: '0402',
      createTime: daysAgo(1, '20:31'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 1,
        investorShare: 0.89,
        platformShare: 8.01,
        base: 8.90
      },
    },
    {
      id: 8,
      orderNo: 'WX202609180002',
      store: '长沙金茂览秀城店',
      user: 'U1007',
      summary: '红苹果乌龙冰奶 x1',
      paidAmount: 1490,
      status: 'VERIFIED',
      payStatus: 'PAID',
      pickupCode: '0403',
      createTime: daysAgo(1, '19:16'),
      split: {
        costTotal: 7.5,
        itemCount: 1,
        storeShare: 2,
        channelShare: 0,
        investorShare: 0,
        platformShare: 5.4,
        base: 5.40
      },
    },
    {
      id: 9,
      orderNo: 'WX202609180003',
      store: '长沙金茂览秀城店',
      user: 'U1008',
      summary: '五窨茉莉抹茶 x1',
      paidAmount: 1390,
      status: 'CREATED',
      payStatus: 'UNPAID',
      pickupCode: '',
      createTime: daysAgo(0, '09:14'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 1,
        investorShare: 0.39,
        platformShare: 3.51,
        base: 3.90
      },
    },
    {
      id: 10,
      orderNo: 'WX202609180004',
      store: '长沙金茂览秀城店',
      user: 'U1009',
      summary: '青提茉莉冰茶 x1',
      paidAmount: 1590,
      status: 'CREATED',
      payStatus: 'UNPAID',
      pickupCode: '',
      createTime: daysAgo(0, '09:05'),
      split: {
        costTotal: 8,
        itemCount: 1,
        storeShare: 2,
        channelShare: 1,
        investorShare: 0.49,
        platformShare: 4.41,
        base: 4.90
      },
    },
    {
      id: 11,
      orderNo: 'WX202609170001',
      store: '五一广场店',
      user: 'U1010',
      summary: '陈皮普洱轻乳茶 x1',
      paidAmount: 1490,
      status: 'REFUNDED',
      payStatus: 'PAID',
      pickupCode: '',
      createTime: daysAgo(1, '15:20'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 0,
        investorShare: 0,
        platformShare: 5.9,
        base: 5.90
      },
    },
    {
      id: 12,
      orderNo: 'WX202609170002',
      store: '五一广场店',
      user: 'U1011',
      summary: '金桂轻乳茶 x1',
      paidAmount: 1390,
      status: 'REFUNDED',
      payStatus: 'PAID',
      pickupCode: '',
      createTime: daysAgo(1, '14:05'),
      split: {
        costTotal: 7,
        itemCount: 1,
        storeShare: 2,
        channelShare: 0,
        investorShare: 0,
        platformShare: 4.9,
        base: 4.90
      }
    }
  ];

  const refundSeedTpl = [
    { title: '抹茶芝士芭乐 x1', product: '抹茶芝士芭乐', user: 'U1000' },
    { title: '红苹果乌龙冰奶 x2', product: '红苹果乌龙冰奶', user: 'U1001' },
    { title: '五窨茉莉抹茶 x1', product: '五窨茉莉抹茶', user: 'U1002' },
    { title: '金桂轻乳茶 x1', product: '金桂轻乳茶', user: 'U1003' },
    { title: '青提茉莉冰茶 x1', product: '青提茉莉冰茶', user: 'U1004' },
    { title: '陈皮普洱轻乳茶 x1', product: '陈皮普洱轻乳茶', user: 'U1005' }
  ];
  const refunds: any[] = Array.from({ length: 6 }, (_, i) => ({
    id: i + 1,
    refundNo: `R${100000 + i}`,
    orderNo: `WX2026091800${String(10 + i).padStart(2, '0')}`,
    user: refundSeedTpl[i].user,
    title: refundSeedTpl[i].title,
    product: refundSeedTpl[i].product,
    amount: 1890 + i * 100,
    status: ['SUCCESS', 'SUCCESS', 'SUCCESS', 'SUCCESS', 'REJECTED', 'SUCCESS'][i],
    applyTime: daysAgo(i % 3, `1${i}:${String(20 + i).padStart(2, '0')}`)
  }));

  return {
    subjects,
    users,
    roleApplications,
    roles: [
      { id: 1, code: 'R_SUPER', name: '超级管理员', dataScope: '平台级', createTime: now() },
      { id: 2, code: 'R_OPERATION', name: '运营', dataScope: '平台级', createTime: now() },
      { id: 3, code: 'R_FINANCE', name: '财务', dataScope: '平台级', createTime: now() },
      { id: 4, code: 'R_AUDIT', name: '审计', dataScope: '平台级', createTime: now() }
    ],
    grants: [
      {
        id: 1,
        userId: 'U1000',
        role: '门店',
        subject: '五一广场店',
        dataScope: '本主体',
        grantBy: 'admin',
        grantTime: now(),
        status: 'active'
    },
      {
        id: 2,
        userId: 'U1001',
        role: '投资人',
        subject: '投资人甲',
        dataScope: '本主体',
        grantBy: 'admin',
        grantTime: now(),
        status: 'active'
      }
    ],
    // 商品 ID 保持后台自增；productId 与小程序 user-h5/data/mock.js 对齐（见 docs/data-schema.md）
    products: [
      {
        id: 1,
        productId: 'classic-001',
        code: 'P-1000',
        name: '五窨茉莉抹茶',
        category: '鲜奶茶',
        specCount: 3,
        price: 13.9,
        originalPrice: 16,
        costPrice: 7,
        platformCommission: 0.7,
        description: '草本清香与醇厚茶韵交融，入口清甜顺滑，回甘自然。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'on',
        splitReady: 'ready',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"杯型","options":[{"id":"m","label":"中杯","priceDelta":0},{"id":"l","label":"大杯","priceDelta":3}]},{"id":"temp","label":"温度","options":[{"id":"std","label":"标准冰","priceDelta":0},{"id":"less","label":"少冰","priceDelta":0},{"id":"no","label":"去冰","priceDelta":0}]},{"id":"topping","label":"加料","options":[{"id":"none","label":"不加马蹄粉圆","priceDelta":0},{"id":"horse","label":"加马蹄粉圆","priceDelta":2}]}],
        tags: ['年度热销', '五窨茉莉花茶'],
        storedValuePrice: 12.9,
        badgeIcon: '/assets/icons/lucide/member-gold.svg',
        ingredients: '一级千目抹茶+芒果鲜果+牛乳芝士+HPP冷冻芒果汁',
        allergens: '饮品内含有乳制品、芒果、无花果碎，过敏者请谨慎选择',
        cupCapacity: '杯型容量中杯500ml，标注容量及图片仅供参考',
        promotionText: '周三会员日招牌饮品85折',
        discountRate: 0.85,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考，具体请以实物为准',
        tips: ['抹茶含有天然咖啡因，敏感人群建议酌情选择。','建议2小时内饮用。']
      },
      {
        id: 2,
        productId: 'classic-005',
        code: 'P-1001',
        name: '红苹果乌龙冰奶',
        category: '鲜奶茶',
        specCount: 3,
        price: 14.9,
        originalPrice: 16,
        costPrice: 7.5,
        platformCommission: 0.75,
        description: '浓郁苹果果香糅合岩香乌龙，果香茶香奶香三重交织。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'on',
        splitReady: 'ready',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"份量","options":[{"id":"m","label":"中杯","priceDelta":0}]},{"id":"temp","label":"温度","options":[{"id":"std","label":"标准冰","priceDelta":0},{"id":"less","label":"少冰","priceDelta":0},{"id":"no","label":"去冰","priceDelta":0}]}],
        tags: ['年度热销', '红苹果乌龙'],
        storedValuePrice: 13.9,
        badgeIcon: '',
        ingredients: '冷冻苹果杏沙棘汁+马头岩乌龙茶+牛乳芝士+冰博客牛奶',
        allergens: '饮品内含有乳制品，过敏者请谨慎选择',
        cupCapacity: '杯型容量中杯500ml，标注容量及图片仅供参考',
        promotionText: '',
        discountRate: 1,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考，具体请以实物为准',
        tips: ['果酸遇乳类蛋白会产生轻微絮状分层，属正常现象。','建议2小时内饮用。']
      },
      {
        id: 3,
        productId: 'classic-002',
        code: 'P-1002',
        name: '金桂轻乳茶',
        category: '鲜奶茶',
        specCount: 3,
        price: 13.9,
        originalPrice: 16,
        costPrice: 7,
        platformCommission: 0.7,
        description: '金桂清香与轻乳交融，温润不腻。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'off',
        splitReady: 'ready',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"杯型","options":[{"id":"m","label":"中杯","priceDelta":0},{"id":"l","label":"大杯","priceDelta":3}]},{"id":"sweet","label":"甜度","options":[{"id":"less","label":"少糖","priceDelta":0},{"id":"half","label":"半糖","priceDelta":0},{"id":"full","label":"全糖","priceDelta":0}]}],
        tags: ['金桂', '轻乳茶'],
        storedValuePrice: 12.9,
        badgeIcon: '',
        ingredients: '金桂花蜜+鲜牛乳+轻乳',
        allergens: '饮品内含有乳制品',
        cupCapacity: '杯型容量中杯500ml',
        promotionText: '',
        discountRate: 1,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考',
        tips: ['建议2小时内饮用。']
      },
      {
        id: 4,
        productId: 'classic-003',
        code: 'P-1003',
        name: '青提茉莉冰茶',
        category: '鲜奶茶',
        specCount: 3,
        price: 15.9,
        originalPrice: 18,
        costPrice: 8,
        platformCommission: 0.8,
        description: '青提果香与茉莉茶韵交织，清新爽口。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'on',
        splitReady: 'incomplete',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"杯型","options":[{"id":"m","label":"中杯","priceDelta":0},{"id":"l","label":"大杯","priceDelta":3}]},{"id":"temp","label":"冰量","options":[{"id":"less","label":"少冰","priceDelta":0},{"id":"no","label":"去冰","priceDelta":0}]}],
        tags: ['青提', '茉莉'],
        storedValuePrice: 14.9,
        badgeIcon: '',
        ingredients: '青提果肉+茉莉花茶+鲜牛乳',
        allergens: '饮品内含有乳制品',
        cupCapacity: '杯型容量中杯500ml',
        promotionText: '',
        discountRate: 1,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考',
        tips: ['建议2小时内饮用。']
      },
      {
        id: 5,
        productId: 'classic-004',
        code: 'P-1004',
        name: '陈皮普洱轻乳茶',
        category: '鲜奶茶',
        specCount: 3,
        price: 13.9,
        originalPrice: 16,
        costPrice: 7,
        platformCommission: 0.7,
        description: '陈皮醇香与普洱茶韵融合，回甘悠长。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'on',
        splitReady: 'ready',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"杯型","options":[{"id":"m","label":"中杯","priceDelta":0},{"id":"l","label":"大杯","priceDelta":3}]},{"id":"temp","label":"温度","options":[{"id":"hot","label":"热饮","priceDelta":0},{"id":"std","label":"标准冰","priceDelta":0}]}],
        tags: ['陈皮', '普洱'],
        storedValuePrice: 12.9,
        badgeIcon: '',
        ingredients: '陈皮+普洱茶+鲜牛乳',
        allergens: '饮品内含有乳制品',
        cupCapacity: '杯型容量中杯500ml',
        promotionText: '',
        discountRate: 1,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考',
        tips: ['建议2小时内饮用。']
      },
      {
        id: 6,
        productId: 'featured-001',
        code: 'P-1005',
        name: '五窨茉莉抹茶',
        category: '鲜奶茶',
        specCount: 3,
        price: 13.9,
        originalPrice: 16,
        costPrice: 7,
        platformCommission: 0.7,
        description: '招牌主打，五窨茉莉花茶与抹茶交融。',
        store: '五一广场店',
        stores: ['五一广场店', '岳麓店'],
        onSale: 'on',
        splitReady: 'ready',
        image: '/assets/images/3x/menu-product.jpg',
        specGroups: [{"id":"size","label":"杯型","options":[{"id":"m","label":"中杯","priceDelta":0},{"id":"l","label":"大杯","priceDelta":4}]},{"id":"temp","label":"温度","options":[{"id":"std","label":"标准冰","priceDelta":0},{"id":"less","label":"少冰","priceDelta":0},{"id":"no","label":"去冰","priceDelta":0}]},{"id":"topping","label":"加料","options":[{"id":"none","label":"不加粉圆","priceDelta":0},{"id":"horse","label":"加马蹄粉圆","priceDelta":2}]}],
        tags: ['招牌主打', '五窨茉莉花茶'],
        storedValuePrice: 12.9,
        badgeIcon: '/assets/icons/lucide/member-gold.svg',
        ingredients: '五窨茉莉花茶+抹茶+鲜牛乳',
        allergens: '饮品内含有乳制品',
        cupCapacity: '杯型容量中杯500ml',
        promotionText: '招牌主打',
        discountRate: 0.85,
        galleryImage: '/assets/images/3x/menu-product.jpg',
        imageDisclaimer: '图片与杯型仅供参考',
        tips: ['建议2小时内饮用。']
      },
    ],
    specs: [
      { id: 1, group: '杯型', name: '中杯/大杯', options: ['中杯', '大杯'], order: 1 },
      { id: 2, group: '甜度', name: '少糖/半糖/全糖', options: ['少糖', '半糖', '全糖'], order: 2 },
      { id: 3, group: '冰量', name: '少冰/去冰', options: ['少冰', '去冰'], order: 3 }
    ],
    splitRules: Array.from({ length: 3 }, (_, i) => ({
      id: i + 1,
      code: `SR-${1000 + i}`,
      name: i === 0 ? '全局默认分账' : `商品分账-${i + 1}`,
      scope: i === 0 ? '全局' : '商品',
      platformRatio: 1000,
      storeRatio: 5000,
      channelRatio: 1500,
      investorRatio: 1500,
      supplierRatio: 1000,
      status: 'enabled'
    })),
    orders,
    payments: Array.from({ length: 8 }, (_, i) => ({
      id: i + 1,
      merchantOrderNo: `O${202609180000 + i}`,
      paymentNo: `PAY${100000 + i}`,
      amount: 1890 + i * 100,
      channel: ['WXPAY', 'MOCK'][i % 2],
      thirdStatus: i % 4 === 0 ? 'PENDING' : 'SUCCESS',
      standardStatus: i % 4 === 0 ? 'PAYING' : 'PAID',
      callbackTime: now()
    })),
    refunds,
    verifies: Array.from({ length: 6 }, (_, i) => ({
      id: i + 1,
      verifyCode: `V${10000 + i}`,
      orderNo: `O${202609180000 + i}`,
      store: '五一广场店',
      operator: `店员${i + 1}`,
      device: `POS-${100 + i}`,
      type: i % 3 === 2 ? '兑换' : '订单',
      result: i % 4 === 0 ? 'rejected' : 'success',
      time: now()
    })),
    snapshots: Array.from({ length: 6 }, (_, i) => ({
      id: i + 1,
      snapshotNo: `SN${100000 + i}`,
      orderNo: `O${202609180000 + i}`,
      summary: ['五窨茉莉抹茶 x1', '红苹果乌龙冰奶 x1', '金桂轻乳茶 x1', '青提茉莉冰茶 x1', '陈皮普洱轻乳茶 x1', '五窨茉莉抹茶 x1'][i],
      itemCount: 1,
      supplierAmount: 7,
      storeAmount: 2,
      channelAmount: 1,
      investorAmount: 0.89,
      platformCommission: 0.7,
      platformBonus: 6.31,
      platformAmount: 7.01,
      totalCheck: '一致',
      status: i % 3 === 0 ? 'invalid' : 'valid',
      createTime: now()
    })),
    fundPool: [
      {
        id: 1,
        poolName: '平台资金池',
        totalBalance: 0,
        updateTime: now()
      }
    ],
    subjectAccounts: [
      { id: 1, subjectId: 40, subjectName: '五零时光运营平台', roleType: 'platform', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 2, subjectId: 1, subjectName: '五一广场店', roleType: 'store', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 3, subjectId: 2, subjectName: '岳麓店', roleType: 'store', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 4, subjectId: 3, subjectName: '平和堂店', roleType: 'store', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 5, subjectId: 4, subjectName: '梅溪湖店', roleType: 'store', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 6, subjectId: 10, subjectName: '资源方 A', roleType: 'resource', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 7, subjectId: 11, subjectName: '资源方 B', roleType: 'resource', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 8, subjectId: 12, subjectName: '资源方 C', roleType: 'resource', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 9, subjectId: 20, subjectName: '投资人甲', roleType: 'investor', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 10, subjectId: 21, subjectName: '投资人乙', roleType: 'investor', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 11, subjectId: 22, subjectName: '投资人丙', roleType: 'investor', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 12, subjectId: 30, subjectName: '供应商一', roleType: 'supplier', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 13, subjectId: 31, subjectName: '供应商二', roleType: 'supplier', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() },
      { id: 14, subjectId: 32, subjectName: '供应商三', roleType: 'supplier', availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() }
    ],
    fundFlows: [
      { id: 1, flowNo: 'FF100001', type: 'INCOME', direction: 'in', amount: 13.9, subjectId: 40, subjectName: '五零时光运营平台', roleType: 'platform', orderNo: 'O202609180000', poolBalanceAfter: 13.9, remark: '订单入账（演示）', createTime: now() }
    ],
    reconciles: Array.from({ length: 4 }, (_, i) => ({
      id: i + 1,
      issueType: ['金额差异', '状态不一致', '缺失流水'][i % 3],
      orderNo: `O${202609180000 + i}`,
      systemValue: '¥18.90',
      thirdValue: i % 3 === 0 ? '¥18.00' : '¥18.90',
      diffAmount: i % 3 === 0 ? 90 : 0,
      foundTime: now(),
      status: i % 2 === 0 ? 'open' : 'resolved'
    })),
    features: [
      {
        id: 1,
        code: 'ENABLE_COUPON',
        name: '优惠券',
        defaultStatus: '开启',
        currentStatus: '开启',
        openCondition: '优惠券活动、锁券、核销和优惠承担逻辑完成'
      },
      {
        id: 2,
        code: 'ENABLE_STORED_VALUE',
        name: '储值充值',
        defaultStatus: '开启',
        currentStatus: '开启',
        openCondition: '充值支付、余额账户、消费核销和预收台账完成'
      },
      {
        id: 3,
        code: 'ENABLE_WITHDRAWAL',
        name: '提现',
        defaultStatus: '关闭',
        currentStatus: '关闭',
        openCondition: '提现申请、审核、出款回调和失败解冻完成'
      },
      {
        id: 4,
        code: 'ENABLE_REVIEW',
        name: '评论',
        defaultStatus: '开启',
        currentStatus: '开启',
        openCondition: '评论提交、审核和订单评价状态完成'
      }
    ],
    withdrawals: [
      {
        id: 1,
        userId: 'U1000',
        nickName: '微信用户1',
        roleType: 'store',
        amount: 80,
        status: 'pending',
        applyTime: now(),
        reviewTime: null,
        reviewer: null
      },
      {
        id: 2,
        userId: 'U1001',
        nickName: '微信用户2',
        roleType: 'investor',
        amount: 1806,
        status: 'pending',
        applyTime: now(),
        reviewTime: null,
        reviewer: null
      },
      {
        id: 3,
        userId: 'U1002',
        nickName: '微信用户3',
        roleType: 'resource',
        amount: 3420,
        status: 'pending',
        applyTime: now(),
        reviewTime: null,
        reviewer: null
      }
    ],
    verifyPool: [
      {
        id: 1,
        type: 'order',
        pickupCode: '0404',
        orderNo: 'WX202609180010',
        product: '抹茶脑袋必喝套餐 x1,红苹果乌龙冰奶 x1',
        spec: '标准冰',
        amount: 3480,
        status: 'pending'
      },
      {
        id: 2,
        type: 'order',
        pickupCode: '0405',
        orderNo: 'WX202609180011',
        product: '抹茶脑袋必喝套餐 x1',
        spec: '标准冰',
        amount: 1990,
        status: 'pending'
      },
      {
        id: 4,
        type: 'exchange',
        code: 'CZ20260920001234',
        pickupCode: 'CZ20260920001234',
        orderNo: 'CZ20260920001234',
        product: '五零时光公仔挂件',
        spec: '兑换商品',
        amount: 0,
        points: 500,
        status: 'pending'
      },
      {
        id: 5,
        type: 'exchange',
        code: 'CZ20260920005678',
        pickupCode: 'CZ20260920005678',
        orderNo: 'CZ20260920005678',
        product: '超浓抹茶系列买一送一券',
        spec: '兑换商品',
        amount: 0,
        points: 300,
        status: 'pending'
      }
    ],
    coupons: [
      {
        id: 1,
        title: '【VIP1】五零时光3元代金券（满20）',
        type: 'voucher',
        amount: 3,
        condition: '满20可用',
        expiryText: '2026-09-29 23:59 到期',
        channel: '不限制',
        brand: '五零时光',
        scenes: '买单、堂食(门店就餐)、堂食(打包外带)',
        validityPeriod: '2026-09-15 00:00:00~2026-09-29 23:59:59',
        usageTime: '00:00:00~23:59:59',
        source: '开卡权益',
        image: '/assets/images/3x/menu-product.jpg',
        description: '五零时光送您一张任意饮品3元券（满20可用），可在五零时光全门店使用，有效期15天。',
        applicableStoreIds: ['ST-1000', 'ST-1001'],
        applicableProductIds: ['classic-001', 'classic-002', 'classic-003'],
        quantity: 100,
        paymentRestriction: '',
        status: 'enabled'
      },
      {
        id: 2,
        title: '新客立减5元',
        type: 'voucher',
        amount: 5,
        condition: '满30可用',
        expiryText: '2026-09-30 23:59 到期',
        channel: '小程序',
        brand: '五零时光',
        scenes: '买单、堂食(门店就餐)',
        validityPeriod: '2026-09-01 00:00:00~2026-09-30 23:59:59',
        usageTime: '00:00:00~23:59:59',
        source: '新客礼',
        image: '/assets/images/3x/menu-product.jpg',
        description: '新客专享，满30减5元。',
        applicableStoreIds: ['ST-1000', 'ST-1001', 'ST-1002'],
        applicableProductIds: ['classic-001', 'classic-002'],
        quantity: 200,
        paymentRestriction: '',
        status: 'enabled'
      },
      {
        id: 3,
        title: '老客回馈8折券',
        type: 'discount',
        amount: 0,
        condition: '满40可用',
        expiryText: '2026-10-15 23:59 到期',
        channel: '小程序',
        brand: '五零时光',
        scenes: '堂食(门店就餐)',
        validityPeriod: '2026-09-01 00:00:00~2026-10-15 23:59:59',
        usageTime: '00:00:00~23:59:59',
        source: '老客回馈',
        image: '/assets/images/3x/menu-product.jpg',
        description: '老客回馈，满40享8折。',
        applicableStoreIds: ['ST-1000'],
        applicableProductIds: [],
        quantity: 100,
        paymentRestriction: '',
        status: 'disabled'
      },
      {
        id: 4,
        title: '储值赠送2元代金券',
        type: 'voucher',
        amount: 2,
        condition: '满9.9可用',
        expiryText: '自充值日起365天有效',
        channel: '储值赠送',
        brand: '五零时光',
        scenes: '买单、堂食(门店就餐)',
        validityPeriod: '充值当天起365天',
        usageTime: '00:00:00~23:59:59',
        source: '储值赠送',
        image: '/assets/images/3x/menu-product.jpg',
        description: '储值赠送2元代金券，满9.9可用，单笔订单限用一张。',
        applicableStoreIds: ['ST-1000', 'ST-1001'],
        applicableProductIds: [],
        quantity: 1000,
        paymentRestriction: '',
        status: 'enabled'
      },
      {
        id: 5,
        title: '储值赠送10元代金券',
        type: 'voucher',
        amount: 10,
        condition: '满50可用',
        expiryText: '自充值日起365天有效',
        channel: '储值赠送',
        brand: '五零时光',
        scenes: '买单、堂食(门店就餐)',
        validityPeriod: '充值当天起365天',
        usageTime: '00:00:00~23:59:59',
        source: '储值赠送',
        image: '/assets/images/3x/menu-product.jpg',
        description: '储值赠送10元代金券，满50可用，单笔订单限用一张。',
        applicableStoreIds: ['ST-1000', 'ST-1001'],
        applicableProductIds: [],
        quantity: 1000,
        paymentRestriction: '',
        status: 'enabled'
      },
      {
        id: 6,
        title: '储值赠送20元代金券',
        type: 'voucher',
        amount: 20,
        condition: '满100可用',
        expiryText: '自充值日起365天有效',
        channel: '储值赠送',
        brand: '五零时光',
        scenes: '买单、堂食(门店就餐)',
        validityPeriod: '充值当天起365天',
        usageTime: '00:00:00~23:59:59',
        source: '储值赠送',
        image: '/assets/images/3x/menu-product.jpg',
        description: '储值赠送20元代金券，满100可用，单笔订单限用一张。',
        applicableStoreIds: ['ST-1000', 'ST-1001'],
        applicableProductIds: [],
        quantity: 1000,
        paymentRestriction: '',
        status: 'enabled'
      }
    ],
    storedValuePackages: [
      { id: 1, amount: 50, coupons: [{ couponId: 4, amount: 2, quantity: 1, description: '储值赠送-2元代金券' }], usageParagraphs: ['1、本储值套餐包含储值金额与赠送优惠券，具体以套餐配置为准。', '2、储值赠送的券自充值当天起365天有效，单笔订单仅限使用一张优惠券。', '3、退款规则：成功充值后如需退款，可通过小程序"我的-联系客服"咨询；已使用赠送券的，退款时扣除对应券面额后返还剩余金额。', '最终解释权归五零时光所有。'] },
      {
        id: 2,
        amount: 100,
        coupons: [
          { couponId: 4, amount: 2, quantity: 2, description: '储值赠送-2元代金券' },
          { couponId: 2, amount: 5, quantity: 2, description: '储值赠送-5元代金券' }
        ],
        usageParagraphs: ['1、本储值套餐包含储值金额与赠送优惠券，具体以套餐配置为准。', '2、储值赠送的券自充值当天起365天有效，单笔订单仅限使用一张优惠券。', '3、退款规则：成功充值后如需退款，可通过小程序"我的-联系客服"咨询；已使用赠送券的，退款时扣除对应券面额后返还剩余金额。', '最终解释权归五零时光所有。']
      },
      {
        id: 3,
        amount: 200,
        coupons: [
          { couponId: 2, amount: 5, quantity: 2, description: '储值赠送-5元代金券' },
          { couponId: 5, amount: 10, quantity: 2, description: '储值赠送-10元代金券' }
        ],
        usageParagraphs: ['1、本储值套餐包含储值金额与赠送优惠券，具体以套餐配置为准。', '2、储值赠送的券自充值当天起365天有效，单笔订单仅限使用一张优惠券。', '3、退款规则：成功充值后如需退款，可通过小程序"我的-联系客服"咨询；已使用赠送券的，退款时扣除对应券面额后返还剩余金额。', '最终解释权归五零时光所有。']
      },
      {
        id: 4,
        amount: 500,
        coupons: [
          { couponId: 5, amount: 10, quantity: 3, description: '储值赠送-10元代金券' },
          { couponId: 6, amount: 20, quantity: 2, description: '储值赠送-20元代金券' }
        ],
        usageParagraphs: ['1、本储值套餐包含储值金额与赠送优惠券，具体以套餐配置为准。', '2、储值赠送的券自充值当天起365天有效，单笔订单仅限使用一张优惠券。', '3、退款规则：成功充值后如需退款，可通过小程序"我的-联系客服"咨询；已使用赠送券的，退款时扣除对应券面额后返还剩余金额。', '最终解释权归五零时光所有。']
      }
    ],
    giftCards: [
      { id: 1, name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg', faceValues: [100, 200, 500] },
      { id: 2, name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg', faceValues: [100, 200, 500] },
      { id: 3, name: '五零时光限定', image: '/assets/images/3x/gift-card-limited.jpg', faceValues: [200, 500] }
    ],
    giftCardDenominations: [
      { id: 1, faceValue: 100, salePrice: 100 },
      { id: 2, faceValue: 200, salePrice: 200 },
      { id: 3, faceValue: 500, salePrice: 500 }
    ],
    giftCardOrders: [
      { id: 1, orderNo: 'GC202609180001', cardName: '超浓抹茶', faceValue: 100, quantity: 1, amount: 100, status: 'COMPLETED', buyer: '微信用户1', createTime: now() },
      { id: 2, orderNo: 'GC202609180002', cardName: '相遇很美好', faceValue: 200, quantity: 2, amount: 400, status: 'PAID', buyer: '微信用户2', createTime: now() },
      { id: 3, orderNo: 'GC202609180003', cardName: '五零时光限定', faceValue: 500, quantity: 1, amount: 500, status: 'CREATED', buyer: '微信用户3', createTime: now() }
    ],
    dictEntries: [
      { id: 1, group: 'order_status', groupName: '订单状态', code: 'CREATED', name: '待支付', sort: 1, enabled: true },
      { id: 2, group: 'order_status', groupName: '订单状态', code: 'PAID', name: '已支付', sort: 2, enabled: true },
      { id: 3, group: 'order_status', groupName: '订单状态', code: 'VERIFIED', name: '已核销', sort: 3, enabled: true },
      { id: 4, group: 'order_status', groupName: '订单状态', code: 'COMPLETED', name: '已完成', sort: 4, enabled: true },
      { id: 5, group: 'order_status', groupName: '订单状态', code: 'REFUNDED', name: '已退款', sort: 5, enabled: true },

      { id: 6, group: 'store_status', groupName: '门店状态', code: 'open', name: '营业中', sort: 1, enabled: true },
      { id: 7, group: 'store_status', groupName: '门店状态', code: 'closed', name: '停业', sort: 2, enabled: true },

      { id: 8, group: 'product_sale_status', groupName: '商品上下架', code: 'on', name: '已上架', sort: 1, enabled: true },
      { id: 9, group: 'product_sale_status', groupName: '商品上下架', code: 'off', name: '已下架', sort: 2, enabled: true },

      { id: 10, group: 'store_type', groupName: '门店类型', code: 'convenience', name: '便利店', sort: 1, enabled: true },
      { id: 11, group: 'store_type', groupName: '门店类型', code: 'restaurant', name: '餐饮', sort: 2, enabled: true },
      { id: 12, group: 'store_type', groupName: '门店类型', code: 'gym', name: '健身房', sort: 3, enabled: true },
      { id: 13, group: 'store_type', groupName: '门店类型', code: 'milk-tea', name: '奶茶/饮品', sort: 4, enabled: true },

      { id: 14, group: 'review_status', groupName: '审核状态', code: 'pending', name: '待审核', sort: 1, enabled: true },
      { id: 15, group: 'review_status', groupName: '审核状态', code: 'approved', name: '已通过', sort: 2, enabled: true },
      { id: 16, group: 'review_status', groupName: '审核状态', code: 'rejected', name: '已驳回', sort: 3, enabled: true },

      { id: 17, group: 'refund_status', groupName: '退款状态', code: 'PENDING', name: '待审核', sort: 1, enabled: true },
      { id: 18, group: 'refund_status', groupName: '退款状态', code: 'APPROVED', name: '已通过', sort: 2, enabled: true },
      { id: 19, group: 'refund_status', groupName: '退款状态', code: 'SUCCESS', name: '退款成功', sort: 3, enabled: true },
      { id: 20, group: 'refund_status', groupName: '退款状态', code: 'REJECTED', name: '已驳回', sort: 4, enabled: true },

      { id: 21, group: 'payment_status', groupName: '支付状态', code: 'PAID', name: '已支付', sort: 1, enabled: true },
      { id: 22, group: 'payment_status', groupName: '支付状态', code: 'PAYING', name: '支付中', sort: 2, enabled: true },
      { id: 23, group: 'payment_status', groupName: '支付状态', code: 'SUCCESS', name: '成功', sort: 3, enabled: true },
      { id: 24, group: 'payment_status', groupName: '支付状态', code: 'PENDING', name: '处理中', sort: 4, enabled: true },

      { id: 25, group: 'execute_status', groupName: '分账执行状态', code: 'PENDING', name: '待执行', sort: 1, enabled: true },
      { id: 26, group: 'execute_status', groupName: '分账执行状态', code: 'RUNNING', name: '执行中', sort: 2, enabled: true },
      { id: 27, group: 'execute_status', groupName: '分账执行状态', code: 'SUCCESS', name: '成功', sort: 3, enabled: true },
      { id: 28, group: 'execute_status', groupName: '分账执行状态', code: 'FAILED', name: '失败', sort: 4, enabled: true },

      { id: 29, group: 'ledger_status', groupName: '资金台账状态', code: 'PENDING', name: '待结算', sort: 1, enabled: true },
      { id: 30, group: 'ledger_status', groupName: '资金台账状态', code: 'SETTLEABLE', name: '可结算', sort: 2, enabled: true },
      { id: 31, group: 'ledger_status', groupName: '资金台账状态', code: 'FROZEN', name: '冻结', sort: 3, enabled: true },
      { id: 32, group: 'ledger_status', groupName: '资金台账状态', code: 'SETTLED', name: '已结算', sort: 4, enabled: true },

      { id: 33, group: 'reconcile_status', groupName: '对账状态', code: 'open', name: '待处理', sort: 1, enabled: true },
      { id: 34, group: 'reconcile_status', groupName: '对账状态', code: 'resolved', name: '已处理', sort: 2, enabled: true },

      { id: 35, group: 'snapshot_status', groupName: '快照状态', code: 'valid', name: '有效', sort: 1, enabled: true },
      { id: 36, group: 'snapshot_status', groupName: '快照状态', code: 'invalid', name: '已作废', sort: 2, enabled: true },

      { id: 37, group: 'withdraw_status', groupName: '提现状态', code: 'pending', name: '待审核', sort: 1, enabled: true },
      { id: 38, group: 'withdraw_status', groupName: '提现状态', code: 'approved', name: '已通过', sort: 2, enabled: true },
      { id: 39, group: 'withdraw_status', groupName: '提现状态', code: 'rejected', name: '已驳回', sort: 3, enabled: true },

      { id: 40, group: 'verify_type', groupName: '核销类型', code: 'order', name: '订单', sort: 1, enabled: true },
      { id: 41, group: 'verify_type', groupName: '核销类型', code: 'exchange', name: '兑换', sort: 2, enabled: true },

      { id: 42, group: 'verify_result', groupName: '核销结果', code: 'success', name: '核销成功', sort: 1, enabled: true },
      { id: 43, group: 'verify_result', groupName: '核销结果', code: 'rejected', name: '重复拦截', sort: 2, enabled: true },

      { id: 44, group: 'coupon_type', groupName: '优惠券类型', code: 'voucher', name: '代金券', sort: 1, enabled: true },
      { id: 45, group: 'coupon_type', groupName: '优惠券类型', code: 'discount', name: '折扣券', sort: 2, enabled: true },

      { id: 46, group: 'enabled_status', groupName: '启用状态', code: 'true', name: '启用', sort: 1, enabled: true },
      { id: 47, group: 'enabled_status', groupName: '启用状态', code: 'false', name: '停用', sort: 2, enabled: true },

      { id: 48, group: 'gender', groupName: '性别', code: 'male', name: '男', sort: 1, enabled: true },
      { id: 49, group: 'gender', groupName: '性别', code: 'female', name: '女', sort: 2, enabled: true },
      { id: 50, group: 'gender', groupName: '性别', code: 'unknown', name: '未知', sort: 3, enabled: true },

      { id: 51, group: 'data_scope', groupName: '数据范围', code: 'platform', name: '平台级', sort: 1, enabled: true },
      { id: 52, group: 'data_scope', groupName: '数据范围', code: 'store', name: '门店级', sort: 2, enabled: true },

      { id: 53, group: 'exchange_status', groupName: '兑换记录状态', code: 'pending_payment', name: '待支付', sort: 1, enabled: true },
      { id: 54, group: 'exchange_status', groupName: '兑换记录状态', code: 'pending_delivery', name: '待发货', sort: 2, enabled: true },
      { id: 55, group: 'exchange_status', groupName: '兑换记录状态', code: 'pending_receipt', name: '待收货', sort: 3, enabled: true },
      { id: 56, group: 'exchange_status', groupName: '兑换记录状态', code: 'pending_verify', name: '待核销', sort: 4, enabled: true },
      { id: 57, group: 'exchange_status', groupName: '兑换记录状态', code: 'verified', name: '已核销', sort: 5, enabled: true },
      { id: 58, group: 'exchange_status', groupName: '兑换记录状态', code: 'completed', name: '已完成', sort: 6, enabled: true },

      { id: 59, group: 'split_ready', groupName: '分账完整度', code: 'ready', name: '完整', sort: 1, enabled: true },
      { id: 60, group: 'split_ready', groupName: '分账完整度', code: 'incomplete', name: '未完整', sort: 2, enabled: true },

      { id: 61, group: 'split_scope', groupName: '分账范围', code: 'global', name: '全局', sort: 1, enabled: true },
      { id: 62, group: 'split_scope', groupName: '分账范围', code: 'product', name: '商品', sort: 2, enabled: true },

      { id: 63, group: 'fund_flow_type', groupName: '资金流水类型', code: 'INCOME', name: '订单入账', sort: 1, enabled: true },
      { id: 64, group: 'fund_flow_type', groupName: '资金流水类型', code: 'WITHDRAW', name: '提现出款', sort: 2, enabled: true },
      { id: 65, group: 'fund_flow_type', groupName: '资金流水类型', code: 'REFUND', name: '退款', sort: 3, enabled: true },
      { id: 66, group: 'fund_flow_type', groupName: '资金流水类型', code: 'FREEZE', name: '冻结', sort: 4, enabled: true },
      { id: 67, group: 'fund_flow_type', groupName: '资金流水类型', code: 'UNFREEZE', name: '解冻', sort: 5, enabled: true },
      { id: 68, group: 'fund_flow_type', groupName: '资金流水类型', code: 'ADJUST', name: '调整', sort: 6, enabled: true },

      { id: 69, group: 'order_type', groupName: '订单类型', code: 'store', name: '门店订单', sort: 1, enabled: true },
      { id: 70, group: 'order_type', groupName: '订单类型', code: 'stored-value', name: '储值订单', sort: 2, enabled: true },
      { id: 71, group: 'order_type', groupName: '订单类型', code: 'gift-card', name: '礼品卡订单', sort: 3, enabled: true },

      { id: 72, group: 'member_level', groupName: '会员等级', code: 'Lv1', name: '时光卡', sort: 1, enabled: true },
      { id: 73, group: 'member_level', groupName: '会员等级', code: 'Lv2', name: '星享卡', sort: 2, enabled: true },
      { id: 74, group: 'member_level', groupName: '会员等级', code: 'Lv3', name: '挚友卡', sort: 3, enabled: true }
    ],
    productCategories: [
      { id: 1, code: 'milk-tea', name: '鲜奶茶', tag: '热销', sort: 1, enabled: true },
      { id: 2, code: 'fruit-tea', name: '水果茶', tag: '新品', sort: 2, enabled: true },
      { id: 3, code: 'leaf-tea', name: '原叶茶', tag: '', sort: 3, enabled: true },
      { id: 4, code: 'seasonal', name: '季节限定', tag: '限定', sort: 4, enabled: true }
    ],
    provinces: [
      { id: 1, code: 'hunan', name: '湖南省', sort: 1, enabled: true },
      { id: 2, code: 'guangdong', name: '广东省', sort: 2, enabled: true }
    ],
    cities: [
      { id: 1, provinceCode: 'hunan', code: 'changsha', name: '长沙市', latitude: 28.2282, longitude: 112.9388, sort: 1, enabled: true },
      { id: 2, provinceCode: 'guangdong', code: 'guangzhou', name: '广州市', latitude: 23.1291, longitude: 113.2644, sort: 2, enabled: true },
      { id: 3, provinceCode: 'guangdong', code: 'shenzhen', name: '深圳市', latitude: 22.5431, longitude: 114.0579, sort: 3, enabled: true }
    ],
    storeTypes: [
      { id: 1, code: 'convenience', name: '便利店', sort: 1, enabled: true },
      { id: 2, code: 'restaurant', name: '餐饮', sort: 2, enabled: true },
      { id: 3, code: 'gym', name: '健身房', sort: 3, enabled: true },
      { id: 4, code: 'milk-tea', name: '奶茶/饮品', sort: 4, enabled: true }
    ],
    pointsProducts: [
      { id: 1, name: '五零时光公益宠粮', category: 'pet', image: '/assets/images/3x/points-product-pet.jpg', points: 20, stock: 5879, badge: '限时抢兑', badgeInImage: false, limitText: '', description: '五零时光公益宠粮，每月善款用于购买大米捐赠并持续追踪后续。' },
      { id: 2, name: '五零时光公仔挂件', category: 'pet', image: '/assets/images/3x/points-product-pet.jpg', points: 500, stock: 100, badge: '', badgeInImage: false, limitText: '', description: '五零时光公仔挂件，限量兑换。' },
      { id: 3, name: '超浓抹茶系列买一送一券', category: 'coupon', image: '/assets/images/3x/points-product-matcha.jpg', points: 300, stock: 51, badge: '限时抢兑', badgeInImage: false, limitText: '*每人仅可兑换一次', description: '超浓抹茶系列买一送一券，兑换后7天内有效。' },
      { id: 4, name: '超浓抹茶系列单杯3元券', category: 'coupon', image: '/assets/images/3x/points-product-single.jpg', points: 300, stock: 51, badge: '限时抢兑', badgeInImage: true, limitText: '*每人仅可兑换一次', description: '超浓抹茶系列单杯3元优惠券。' },
      { id: 5, name: '超浓抹茶系列第2杯半价券', category: 'coupon', image: '/assets/images/3x/points-product-half.jpg', points: 300, stock: 51, badge: '限时抢兑', badgeInImage: true, limitText: '*每人仅可兑换一次', description: '超浓抹茶系列第2杯半价券。' }
    ],
    pointsEarningRules: [
      { id: 1, action: '每消费1元', reward: '+1币', note: '基础获取通道' },
      { id: 2, action: '每日签到', reward: '+1币', note: '连续签到7天额外+20币' },
      { id: 3, action: '邀请好友注册', reward: '+3币/人', note: '好友完成首单后到账' }
    ],
    signInDaily: 1 as any,
    signInRewards: [{ days: 7, amount: 20 }] as any,
    referralConfig: {
      id: 1,
      inviteCodePrefix: 'WLG',
      firstOrderPoints: 3,
      firstOrderCouponAmount: 3,
      socialStarThreshold: 5,
      socialStarProduct: '',
      recommenderThreshold: 10,
      recommenderRebateRate: 5
    } as any,
    exchangeRecords: [
      { id: 1, recordNo: 'EX202609180001', user: '微信用户1', product: '五零时光公仔挂件', points: 500, status: 'pending_verify', applyTime: now() },
      { id: 2, recordNo: 'EX202609180002', user: '微信用户2', product: '超浓抹茶系列买一送一券', points: 300, status: 'completed', applyTime: now() },
      { id: 3, recordNo: 'EX202609180003', user: '微信用户3', product: '超浓抹茶系列单杯3元券', points: 300, status: 'pending_payment', applyTime: now() }
    ],
    memberLevels: [
      {
        id: 1,
        level: 'Lv1',
        name: '时光卡',
        amountTarget: 0,
        condition: '注册即得',
        discount: '8折',
        benefits: ['基础折扣', '生日月双倍时光币', '专属会员价']
      },
      {
        id: 2,
        level: 'Lv2',
        name: '星享卡',
        amountTarget: 300,
        condition: '累计消费满300元',
        discount: '7折',
        benefits: ['基础折扣', '专属优惠券', '新品优先体验']
      },
      {
        id: 3,
        level: 'Lv3',
        name: '挚友卡',
        amountTarget: 2000,
        condition: '累计消费满2000元',
        discount: '6折',
        benefits: ['专属优惠券', '时光币1.5倍', '生日免费饮品']
      }
    ],
    comments: [
      {
        id: 1,
        userId: 'U1000',
        nickName: '微信用户1',
        orderNo: 'O202609180021',
        product: '抹茶芝士芭乐',
        rating: 5,
        content: '很好喝，抹茶味很浓',
        status: 'pending',
        time: now()
      },
      {
        id: 2,
        userId: 'U1001',
        nickName: '微信用户2',
        orderNo: 'O202609180019',
        product: '红苹果乌龙冰奶',
        rating: 4,
        content: '口感不错',
        status: 'pending',
        time: now()
      }
    ],
    auditLogs: []
  };
}

const auditFieldMeta: Record<string, string> = {
  id: 'ID',
  name: '名称',
  code: '编码',
  title: '标题',
  status: '状态',
  onSale: '上架状态',
  signStatus: '签约状态',
  businessRole: '业务角色',
  boundSubjectId: '绑定主体编号',
  boundSubjectName: '绑定主体',
  boundUserId: '绑定用户编号',
  boundUserName: '绑定用户',
  currentStatus: '当前状态',
  nickName: '昵称',
  userId: '用户编号',
  openId: 'OpenID',
  birthday: '生日',
  gender: '性别',
  storeName: '门店名称',
  roleType: '角色类型',
  balance: '余额',
  points: '积分',
  vipLevel: '会员等级',
  phone: '手机号',
  manager: '店长',
  address: '地址',
  city: '城市',
  productCount: '商品数量',
  investableStoreCount: '可投门店数量',
  relatedStore: '关联门店',
  dataScope: '数据范围',
  subject: '主体',
  role: '角色',
  daily: '每日签到',
  rewards: '签到奖励',
  days: '连续天数',
  amount: '金额',
  applyTime: '申请时间',
  reviewTime: '审核时间',
  reviewer: '审核人',
  subjectId: '主体编号',
  subjectName: '主体名称',
  orderNo: '订单号',
  verifyCode: '核销码',
  store: '门店',
  type: '类型',
  result: '结果',
  device: '设备',
  operator: '操作人',
  time: '时间',
  refundNo: '退款单号',
  product: '商品',
  content: '内容',
  quantity: '数量',
  price: '价格',
  originalPrice: '原价',
  costPrice: '成本价',
  platformCommission: '平台分佣',
  withdrawFreeAuditThreshold: '提现免审阈值',
  stock: '库存',
  description: '描述',
  createTime: '创建时间',
  deleted: '删除状态',
  deleteReason: '删除原因',
  appid: 'AppID',
  appSecret: 'AppSecret'
};

const auditValueMeta: Record<string, string> = {
  on: '上架',
  off: '下架',
  store: '门店',
  channel: '资源方',
  resource: '资源方',
  investor: '投资人',
  supplier: '供应商',
  platform: '平台',
  active: '启用',
  open: '营业',
  signed: '已签约',
  pending: '待审核',
  approved: '已通过',
  rejected: '已驳回',
  success: '成功',
  exchange: '兑换',
  order: '订单',
  PENDING: '待审核',
  APPROVED: '已通过',
  SUCCESS: '成功',
  REJECTED: '已驳回',
  COMPLETED: '已完成',
  REFUNDED: '已退款',
  PAID: '已支付',
  UNPAID: '未支付',
  CANCELLED: '已取消',
  enabled: '启用',
  disabled: '停用',
  male: '男',
  female: '女',
  unknown: '未知'
};

function formatAuditValue(v: any): string {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v !== 'object') return auditValueMeta[String(v)] ?? String(v);
  if (Array.isArray(v)) return v.length ? v.map(item => formatAuditValue(item)).join('、') : '—';
  const keys = Object.keys(v).filter(key => auditFieldMeta[key] != null && v[key] != null && v[key] !== '');
  if (!keys.length) return JSON.stringify(v);
  return keys.map(key => `${auditFieldMeta[key]}：${formatAuditValue(v[key])}`).join('；');
}

function formatLegacyAuditText(v: any): string {
  const text = typeof v === 'string' ? v.trim() : v == null ? '' : String(v);
  if (!text || text === '—') return '—';
  if (text.startsWith('{') || text.startsWith('[')) {
    try {
      return formatAuditValue(JSON.parse(text));
    } catch {
      return text;
    }
  }
  const matches = Array.from(text.matchAll(/([A-Za-z_][A-Za-z0-9_]*)=("[^"]*"|[^\s]+)/g));
  if (!matches.length) return text;
  const entries = matches.map(([, key, value]) => {
    const val = value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value;
    return [key, val === '—' ? null : val] as const;
  });
  const row = Object.fromEntries(entries);
  return formatAuditValue(row);
}

function migrate(data: AdminData): AdminData {
  const defaults = seed();
  const defaultProducts = defaults.products || [];
  (data.products || []).forEach((product: any) => {
    if (!product.image) {
      const seedProduct = defaultProducts.find((p: any) => p.productId === product.productId);
      product.image = seedProduct?.image || '/assets/images/3x/menu-product.jpg';
    }
    if (!Array.isArray(product.specGroups)) {
      const seedProduct = defaultProducts.find((p: any) => p.productId === product.productId);
      product.specGroups = seedProduct?.specGroups || [];
    }
    // 价格分转元（老数据 price > 100 视为分）
    if (typeof product.price === 'number' && product.price > 100) product.price = product.price / 100;
    if (typeof product.originalPrice === 'number' && product.originalPrice > 100) product.originalPrice = product.originalPrice / 100;
    if (product.costPrice == null) product.costPrice = Math.round((product.price || 0) * 0.5 * 10) / 10;
    if (!Array.isArray(product.tags)) product.tags = [];
    if (product.storedValuePrice == null) product.storedValuePrice = product.price;
    if (product.platformCommission == null) product.platformCommission = Math.round((product.costPrice || 0) * 0.1 * 10) / 10;
    if (!product.badgeIcon) product.badgeIcon = '';
    if (!product.ingredients) product.ingredients = '';
    if (!product.allergens) product.allergens = '';
    if (!product.cupCapacity) product.cupCapacity = '';
    if (!product.promotionText) product.promotionText = '';
    if (product.discountRate == null) product.discountRate = 1;
    if (!product.galleryImage) product.galleryImage = product.image || '';
    if (!product.imageDisclaimer) product.imageDisclaimer = '';
    if (!Array.isArray(product.tips)) product.tips = [];
    // 订单 split 快照补平台分佣/提成
    if (product.platformCommission == null) product.platformCommission = 0;
    // 规格 priceDelta 分转元
    (product.specGroups || []).forEach((g: any) => {
      (g.options || []).forEach((o: any) => {
        if (typeof o.priceDelta === 'number' && o.priceDelta > 100) o.priceDelta = o.priceDelta / 100;
      });
    });
  });
  (data.orders || []).forEach((order: any) => {
    if (order.split) {
      if (order.split.platformCommission == null) order.split.platformCommission = 0;
      if (order.split.platformBonus == null) order.split.platformBonus = order.split.platformShare ?? 0;
    }
  });
  (data.users || []).forEach((user: any) => {
    if (!user.avatar) user.avatar = '/assets/images/3x/profile-avatar.jpg';
    if (!user.birthday) user.birthday = '';
    if (!user.gender) user.gender = 'unknown';
    if (!user.vipLevel) user.vipLevel = '时光卡';
    if (user.points == null) user.points = 0;
    if (user.balance == null) user.balance = 0;
  });
  (data.coupons || []).forEach((coupon: any) => {
    if (!coupon.brand) coupon.brand = '五零时光';
    if (!coupon.scenes) coupon.scenes = '';
    if (!coupon.validityPeriod) coupon.validityPeriod = '';
    if (!coupon.usageTime) coupon.usageTime = '';
    if (!coupon.source) coupon.source = '';
    if (!coupon.image) coupon.image = '/assets/images/3x/menu-product.jpg';
    if (!coupon.description) coupon.description = '';
    if (!Array.isArray(coupon.applicableStoreIds)) coupon.applicableStoreIds = [];
    if (!Array.isArray(coupon.applicableProductIds)) coupon.applicableProductIds = [];
    if (coupon.quantity == null) coupon.quantity = 100;
    if (coupon.paymentRestriction == null) coupon.paymentRestriction = '';
  });
  (data.memberLevels || []).forEach((lv: any) => {
    if (!lv.condition) lv.condition = '';
  });
  (data.snapshots || []).forEach((snap: any) => {
    if (!snap.summary) snap.summary = '—';
    if (snap.itemCount == null) snap.itemCount = 1;
    if (snap.platformCommission == null) snap.platformCommission = 0;
    if (snap.platformBonus == null) snap.platformBonus = snap.platformAmount ?? 0;
  });
  (data.splitRules || []).forEach((rule: any) => {
    if (rule.storePerItem == null) rule.storePerItem = 2;
    if (rule.channelPerItem == null) rule.channelPerItem = 1;
    if (rule.investorPercent == null) rule.investorPercent = 10;
  });
  (data.subjects || []).forEach((s: any) => {
    if (s.boundUserId == null) s.boundUserId = null;
    if (s.boundUserName == null) s.boundUserName = null;
    if (s.type === 'platform' && s.appid == null) s.appid = '';
    if (s.type === 'platform' && s.appSecret == null) s.appSecret = '';
  });
  (data.storedValuePackages || []).forEach((pkg: any) => {
    if (!Array.isArray(pkg.usageParagraphs)) pkg.usageParagraphs = [];
  });
  if (!Array.isArray(data.storeTypes)) data.storeTypes = [];
  if (!Array.isArray(data.productCategories)) data.productCategories = [];
  if (!Array.isArray(data.dictEntries) || data.dictEntries.length === 0) data.dictEntries = defaults.dictEntries || [];
  if (!Array.isArray(data.fundPool) || data.fundPool.length === 0) data.fundPool = defaults.fundPool || [];
  if (!Array.isArray(data.subjectAccounts) || data.subjectAccounts.length === 0) data.subjectAccounts = defaults.subjectAccounts || [];
  if (!Array.isArray(data.fundFlows)) data.fundFlows = [];
  if (!Array.isArray(data.exchangeRecords)) data.exchangeRecords = [];
  if (!Array.isArray(data.giftCardOrders)) data.giftCardOrders = [];
  if (!Array.isArray(data.provinces)) data.provinces = [];
  if (!Array.isArray(data.cities)) data.cities = [];
  if (!(data as any).referralConfig) (data as any).referralConfig = { id: 1, inviteCodePrefix: 'WLG', firstOrderPoints: 3, firstOrderCouponAmount: 3, socialStarThreshold: 5, socialStarProduct: '', recommenderThreshold: 10, recommenderRebateRate: 5 };
  (data.pointsProducts || []).forEach((p: any) => {
    if (!p.image) p.image = '/assets/images/3x/points-product-pet.jpg';
    if (p.stock == null) p.stock = 0;
    if (!p.badge) p.badge = '';
    if (!p.limitText) p.limitText = '';
    if (!p.description) p.description = '';
  });
  if (data.signInDaily == null) (data as any).signInDaily = 1;
  if (!Array.isArray((data as any).signInRewards)) (data as any).signInRewards = [{ days: 7, amount: 20 }];
  // 营销相关功能开关兜底为开启，保证营销中心可访问
  (data.features || []).forEach((f: any) => {
    if (f.code === 'ENABLE_COUPON' || f.code === 'ENABLE_STORED_VALUE' || f.code === 'ENABLE_REVIEW') {
      f.currentStatus = '开启';
      f.defaultStatus = '开启';
    }
  });
  (data.auditLogs || []).forEach((log: any) => {
    log.beforeValue = formatLegacyAuditText(log.beforeValue);
    log.afterValue = formatLegacyAuditText(log.afterValue);
  });
  return data;
}

function loadInitial(): AdminData {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object' && Array.isArray(parsed.subjects)) {
        return migrate(parsed);
      }
    }
  } catch {}
  return seed();
}

export const useAdminStore = defineStore(SetupStoreId.Admin, () => {
  const data = ref<AdminData>(loadInitial());

  function persist() {
    localStorage.setItem(KEY, JSON.stringify(data.value));
  }

  function nextId(list: { id: number }[]) {
    return list.reduce((m, item) => Math.max(m, item.id || 0), 0) + 1;
  }

  function summarize(v: any) {
    return formatAuditValue(v);
  }

  function audit(module: string, action: string, target: string, before: any, after: any, reason = '') {
    const logs = data.value.auditLogs || [];
    let beforeSummary = summarize(before);
    let afterSummary = summarize(after);

    if (before && after && typeof before === 'object' && typeof after === 'object' && !Array.isArray(before) && !Array.isArray(after)) {
      const changedKeys = Object.keys(after).filter(key => JSON.stringify(before[key]) !== JSON.stringify(after[key]));
      const beforeDiff = Object.fromEntries(changedKeys.map(key => [key, before[key]]));
      const afterDiff = Object.fromEntries(changedKeys.map(key => [key, after[key]]));
      beforeSummary = summarize(beforeDiff);
      afterSummary = summarize(afterDiff);
    }

    logs.unshift({
      id: nextId(logs),
      time: now(),
      operator: 'admin',
      module,
      action,
      target,
      beforeValue: beforeSummary,
      afterValue: afterSummary,
      reason,
      ip: '127.0.0.1'
    });
    data.value.auditLogs = logs;
  }

  function ensure(key: string) {
    if (!Array.isArray(data.value[key])) data.value[key] = [];
    return data.value[key];
  }

  // generic CRUD
  function add(key: string, row: any, module: string, labelKey = 'name') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      // 远端模式：写库后回填本地镜像，保证列表即时可见
      crudCreate(remote, row)
        .then(created => {
          const list = ensure(key);
          list.unshift(created);
          persist();
        })
        .catch(error => {
          window.$message?.error(error?.message || '新增失败');
        });
      const optimistic = { id: Date.now(), createTime: now(), ...row };
      return optimistic;
    }
    const list = ensure(key);
    const item = { id: nextId(list), createTime: now(), ...row };
    list.unshift(item);
    audit(module, '新增', item[labelKey] ?? item.id, null, item);
    persist();
    return item;
  }
  function update(key: string, id: number, updates: any, module: string, labelKey = 'name') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      // 先乐观更新，远端失败则回滚，避免「界面已改、库里没改」
      const list = ensure(key);
      const idx = list.findIndex((x: any) => x.id === id);
      const snapshot = idx >= 0 ? { ...list[idx] } : null;
      if (idx >= 0) list[idx] = { ...list[idx], ...updates };
      persist();

      crudUpdate(remote, id, updates)
        .then(updated => {
          const cur = ensure(key);
          const i = cur.findIndex((x: any) => x.id === id);
          if (i >= 0) cur[i] = { ...cur[i], ...updated };
          persist();
        })
        .catch(error => {
          if (snapshot) {
            const cur = ensure(key);
            const i = cur.findIndex((x: any) => x.id === id);
            if (i >= 0) cur[i] = snapshot;
            persist();
          }
          window.$message?.error(error?.message || '更新失败');
        });
      return;
    }
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...updates };
    audit(module, '编辑', list[idx][labelKey] ?? id, before, list[idx]);
    persist();
  }
  function remove(key: string, id: number, module: string, labelKey = 'name', reason = '') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      crudDelete(remote, id)
        .then(() => {
          const list = ensure(key);
          const idx = list.findIndex((x: any) => x.id === id);
          if (idx >= 0) {
            list[idx] = { ...list[idx], deleted: true, deletedAt: now(), deleteReason: reason };
          }
          persist();
        })
        .catch(error => {
          window.$message?.error(error?.message || '删除失败');
        });
      return;
    }
    patch(
      key,
      id,
      {
        deleted: true,
        deletedAt: now(),
        deleteReason: reason
      },
      module,
      '删除',
      labelKey,
      reason
    );
  }
  function patch(
    key: string,
    id: number,
    updates: any,
    module: string,
    action: string,
    labelKey = 'name',
    reason = ''
  ) {
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...updates };
    audit(module, action, list[idx][labelKey] ?? id, before, list[idx], reason);
    persist();
    return list[idx];
  }

  function saveSignInRule(payload: { daily: number; rewards: Array<{ days: number; amount: number }> }) {
    const before = { daily: (data.value as any).signInDaily ?? 1, rewards: (data.value as any).signInRewards || [] };
    (data.value as any).signInDaily = payload.daily;
    (data.value as any).signInRewards = payload.rewards;
    audit('营销中心', '编辑签到规则', '签到规则', before, { daily: payload.daily, rewards: payload.rewards });
    persist();

    // 远端模式：同步到后端
    const first = payload.rewards && payload.rewards[0];
    saveSigninRuleApi({
      daily: payload.daily,
      streakDays: first?.days ?? 7,
      streakReward: first?.amount ?? 20,
      rewards: payload.rewards
    }).catch(error => {
      window.$message?.error(error?.message || '签到规则同步失败');
    });
  }
  function saveReferralConfig(payload: Record<string, any>) {
    const before = { ...(data.value as any).referralConfig };
    (data.value as any).referralConfig = { ...(data.value as any).referralConfig, ...payload };
    audit('营销中心', '编辑分享规则', '分享有礼', before, (data.value as any).referralConfig);
    persist();

    saveReferralConfigApi(payload).catch(error => {
      window.$message?.error(error?.message || '分享规则同步失败');
    });
  }

  /** 从后端读取邀请配置并写入本地镜像（只读加载，不触发审计、不回写后端）。 */
  async function loadReferralConfig() {
    try {
      const cfg = await fetchReferralConfig();
      if (cfg && typeof cfg === 'object') {
        (data.value as any).referralConfig = { ...(data.value as any).referralConfig, ...cfg };
        persist();
      }
    } catch (error: any) {
      window.$message?.error(error?.message || '邀请配置加载失败');
    }
  }

  /**
   * 从后端加载配置类资源到本地镜像（远端模式）。
   * 页面 onMounted 调用即可，未登记的资源会跳过。
   */
  async function loadRemote(key: string, params?: Record<string, any>) {
    const remote = REMOTE_RESOURCES[key];
    if (!remote) return null;
    try {
      const page = await crudPage(remote, { current: 1, size: 200, ...params });
      const list = ensure(key);
      list.splice(0, list.length, ...(page?.records || []));
      remoteLoaded.value[key] = true;
      persist();
      return page;
    } catch (error: any) {
      window.$message?.error(error?.message || `加载 ${key} 失败`);
      return null;
    }
  }

  /** 批量加载多个远端资源 */
  async function loadRemoteAll(keys: string[]) {
    await Promise.all(keys.map(key => loadRemote(key)));
  }

  /** 该 key 是否已启用远端模式 */
  function isRemote(key: string) {
    return Boolean(REMOTE_RESOURCES[key]);
  }
  function listFiltered<T extends Record<string, any>>(
    list: T[],
    search: Record<string, any>,
    page: number,
    pageSize: number
  ) {
    let rows = list.filter(row => !row.deleted);
    for (const key of Object.keys(search)) {
      const value = String(search[key] ?? '').trim();
      if (!value) continue;
      rows = rows.filter(row => {
        const target = row[key];
        if (Array.isArray(target)) return target.some(item => String(item).includes(value));
        return String(target ?? '').includes(value);
      });
    }
    const total = rows.length;
    const start = (page - 1) * pageSize;
    return { data: rows.slice(start, start + pageSize), total };
  }

  function bindUserRole(userId: number, roleType: RoleType, subjectId: string, subjectName: string) {
    const users = ensure('users');
    const subjects = ensure('subjects');
    const userIdx = users.findIndex((u: any) => u.id === userId);
    if (userIdx < 0) return;
    const user = users[userIdx];

    // 一个用户只能绑定一个主体
    if (user.boundSubjectId) {
      window.$message?.warning('该用户已绑定主体，请先解绑');
      return;
    }

    const subject = subjects.find((s: any) => s.code === subjectId);
    if (!subject) {
      window.$message?.warning('主体不存在');
      return;
    }

    // 主体未被其他用户绑定
    if (subject.boundUserId && subject.boundUserId !== user.userId) {
      window.$message?.warning('该经营者已绑定用户，请先解绑');
      return;
    }

    const beforeUser = { ...user };
    const beforeSubject = { ...subject };
    users[userIdx] = { ...user, businessRole: roleType, boundSubjectId: subjectId, boundSubjectName: subjectName };
    subject.boundUserId = user.userId;
    subject.boundUserName = user.nickName;
    audit('用户管理', '绑定角色', user.nickName, beforeUser, users[userIdx]);
    audit('主体管理', '绑定用户', subjectName, beforeSubject, subject);
    persist();
  }

  function unbindUserRole(userId: number, reason = '') {
    const users = ensure('users');
    const subjects = ensure('subjects');
    const userIdx = users.findIndex((u: any) => u.id === userId);
    if (userIdx < 0) return;
    const user = users[userIdx];
    const subject = subjects.find((s: any) => s.code === user.boundSubjectId);
    const beforeUser = { ...user };
    users[userIdx] = { ...user, businessRole: null, boundSubjectId: null, boundSubjectName: null };
    if (subject) {
      const beforeSubject = { ...subject };
      subject.boundUserId = null;
      subject.boundUserName = null;
      audit('主体管理', '解绑用户', subject.name, beforeSubject, subject, reason);
    }
    audit('用户管理', '解绑角色', user.nickName, beforeUser, users[userIdx], reason);
    persist();
  }

  /** 主体列表：绑定用户（任意活跃状态可绑定；已绑定需先解绑） */
  function bindSubjectUser(subjectId: number, userId: number) {
    const subjects = ensure('subjects');
    const subject = subjects.find((s: any) => s.id === subjectId);
    if (!subject) return;
    if (subject.boundUserId) {
      window.$message?.warning('该经营者已绑定用户，请先解绑');
      return;
    }
    const user = ensure('users').find((u: any) => u.id === userId);
    if (!user) return;
    if (user.boundSubjectId) {
      window.$message?.warning('该用户已绑定主体，请先解绑');
      return;
    }
    const roleType = (subject.type === 'resource' ? 'resource' : subject.type) as RoleType;
    bindUserRole(userId, roleType, subject.code, subject.name);
  }

  /** 主体列表：解绑用户（仅停用态可解绑） */
  function unbindSubjectUser(subjectId: number, reason = '') {
    const subjects = ensure('subjects');
    const subject = subjects.find((s: any) => s.id === subjectId);
    if (!subject) return;
    if (!subject.boundUserId) {
      window.$message?.warning('该经营者未绑定用户');
      return;
    }
    // 仅停用态可解绑
    const disabled = subject.status === 'closed' || subject.status === 'disabled';
    if (!disabled) {
      window.$message?.warning('仅停用的经营者才能解绑用户');
      return;
    }
    const user = ensure('users').find((u: any) => u.userId === subject.boundUserId);
    if (user) unbindUserRole(user.id, reason || `解绑用户：${subject.name}`);
  }

  function reviewApplication(
    id: number,
    approve: boolean,
    subjectId: string | null,
    subjectName: string | null,
    reason = ''
  ) {
    const list = ensure('roleApplications');
    const idx = list.findIndex((a: any) => a.id === id);
    if (idx < 0) return;
    const app = list[idx];
    if (app.status !== 'pending') {
      window.$message?.warning('该申请已审核，不能重复操作');
      return;
    }
    const before = { ...app };
    if (approve && !subjectId) {
      window.$message?.error('该申请未携带主体，无法通过');
      return;
    }
    list[idx] = {
      ...app,
      status: approve ? 'approved' : 'rejected',
      reviewTime: now(),
      reviewer: 'admin',
      subjectId,
      subjectName
    };
    if (approve && subjectId) {
      const user = ensure('users').find((u: any) => u.userId === before.userId);
      if (user) bindUserRole(user.id, before.roleType, subjectId, subjectName || '');
    }
    audit('申请审核', approve ? '通过' : '驳回', before.nickName, before, list[idx], reason);
    persist();
  }

  function toggleFeature(id: number, on: boolean, reason = '') {
    patch(
      'features',
      id,
      { currentStatus: on ? '开启' : '关闭' },
      '系统',
      on ? '开启开关' : '关闭开关',
      'name',
      reason
    );
  }

  function refundOrder(id: number, reason = '') {
    const list = ensure('orders');
    const idx = list.findIndex((o: any) => o.id === id);
    if (idx < 0) return;
    const order = list[idx];
    // 仅待支付(CREATED)/已支付(PAID)可取消；已核销(VERIFIED)/已完成(COMPLETED)/已退款(REFUNDED)不可取消
    if (order.status === 'VERIFIED' || order.status === 'COMPLETED') {
      window.$message?.error('已核销订单不可取消');
      return;
    }
    if (order.status === 'REFUNDED') {
      window.$message?.warning('该订单已退款，不能重复取消');
      return;
    }
    const before = { ...order };
    order.status = 'REFUNDED';
    order.payStatus = 'REFUNDED';
    // 取消后自动生成退款记录（无需审核，直接退款成功）
    const refunds = ensure('refunds');
    refunds.unshift({
      id: nextId(refunds),
      refundNo: `R${Date.now()}`,
      orderNo: order.orderNo,
      user: order.user || '—',
      title: order.summary || '—',
      product: order.summary || '—',
      amount: order.paidAmount,
      status: 'SUCCESS',
      applyTime: now(),
      refundType: 'paid'
    });
    audit('交易中心', '整单退款', order.orderNo, before, order, reason);
    persist();
    window.$message?.success('已取消，退款将原路退回');
  }

  function reviewWithdraw(id: number, approve: boolean, reason = '') {
    const list = ensure('withdrawals');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'pending') {
      window.$message?.warning('该提现申请已处理，不能重复审核');
      return;
    }
    if (approve) {
      const acc = ensure('subjectAccounts').find((a: any) => a.subjectId === item.subjectId);
      if (acc) {
        if (item.amount > acc.availableBalance) {
          window.$message?.error('可提现余额不足，无法通过');
          return;
        }
        debitAccount(acc, item.amount);
        const pool = ensure('fundPool')[0];
        pool.totalBalance = Math.round((pool.totalBalance - item.amount) * 100) / 100;
        pool.updateTime = now();
        addFlow({
          type: 'WITHDRAW', direction: 'out', amount: item.amount,
          subjectId: acc.subjectId, subjectName: acc.subjectName, roleType: acc.roleType,
          orderNo: '', poolBalanceAfter: pool.totalBalance, remark: reason || '提现出款'
        });
      }
    }
    patch(
      'withdrawals',
      id,
      { status: approve ? 'approved' : 'rejected', reviewTime: now(), reviewer: 'admin' },
      '财务中心',
      approve ? '提现通过' : '提现驳回',
      'nickName',
      reason
    );
  }

  function executeVerify(id: number, reason = '') {
    const pool = ensure('verifyPool');
    const idx = pool.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const [item] = pool.splice(idx, 1);
    item.status = 'verified';
    item.verifiedTime = now();

    // 点单奶茶核销：通过取餐码联动订单状态 PAID -> VERIFIED
    let orderNo = item.orderNo;
    if (item.type !== 'exchange' && item.pickupCode) {
      const orders = ensure('orders');
      const order = orders.find((o: any) => o.pickupCode === item.pickupCode && o.status === 'PAID');
      if (order) {
        order.status = 'VERIFIED';
        orderNo = order.orderNo;
      }
    }

    const list = ensure('verifies');
    list.unshift({
      id: nextId(list),
      verifyCode: item.code || item.pickupCode,
      orderNo,
      store: item.store || '五一广场店',
      operator: 'admin',
      device: '后台',
      type: item.type === 'exchange' ? '兑换' : '订单',
      result: 'success',
      time: now()
    });
    audit('交易中心', '执行核销', orderNo, item, item, reason);
    persist();
  }

  function reviewComment(id: number, approve: boolean, reason = '') {
    const list = ensure('comments');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'pending') {
      window.$message?.warning('该评论已审核，不能重复操作');
      return;
    }

    const apply = () =>
      patch(
        'comments',
        id,
        { status: approve ? 'approved' : 'rejected' },
        '营销中心',
        approve ? '评论通过' : '评论驳回',
        'content',
        reason
      );

    // 远端模式：审核落库；失败则不动本地状态
    reviewCommentApi(id, approve, reason)
      .then(apply)
      .catch(error => {
        window.$message?.error(error?.message || '评论审核失败');
      });
  }

  function refundAudit(id: number, approve: boolean, reason = '') {
    const list = ensure('refunds');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'PENDING') {
      window.$message?.warning('该退款单已处理，不能重复审核');
      return;
    }
    patch(
      'refunds',
      id,
      { status: approve ? 'SUCCESS' : 'REJECTED' },
      '交易中心',
      approve ? '退款审核通过' : '退款驳回',
      'refundNo',
      reason
    );
  }

  function enableSplitRule(id: number, reason = '') {
    // 远端模式：交由后端校验万分比合计与同范围唯一启用
    toggleSplitRule(id, true)
      .then(() => {
        const list = ensure('splitRules');
        list.forEach((r: any) => {
          if (r.scope === 'GLOBAL' && r.id !== id) r.status = 'disabled';
        });
        const idx = list.findIndex((r: any) => r.id === id);
        if (idx >= 0) list[idx].status = 'enabled';
        audit('商品中心', '启用规则', String(id), null, { status: 'enabled', reason });
        persist();
      })
      .catch(error => {
        window.$message?.error(error?.message || '启用失败（请检查分账比例合计是否为 100%）');
      });
  }

  /**
   * 分账计算：
   * 成本合计 = Σ(商品成本价×数量) —— 供应商分账
   * 门店 = 门店每件 × 件数
   * 资源方 = 有资源方 ? 资源方每件 × 件数 : 0
   * 基础 = 实付 - 成本 - 门店 - 资源方
   * 投资人 = max(0, 基础) × X%
   * 平台 = 实付 - 成本 - 门店 - 资源方 - 投资人（可能为负，平台承担）
   */
  function calcOrderSplit(paid: number, itemCount: number, costTotal: number, rule: any, hasChannel: boolean, platformCommission = 0) {
    const storeShare = (rule?.storePerItem ?? 0) * itemCount;
    const channelShare = hasChannel ? (rule?.channelPerItem ?? 0) * itemCount : 0;
    const base = paid - costTotal - storeShare - channelShare;
    const investorShare = base > 0 ? (base * (rule?.investorPercent ?? 0)) / 100 : 0;
    const platformShare = paid - costTotal - storeShare - channelShare - investorShare;
    const platformBonus = platformShare - platformCommission;
    return {
      costTotal,
      itemCount,
      storeShare,
      channelShare,
      investorShare,
      platformShare,
      platformCommission,
      platformBonus,
      base,
      ruleName: rule?.name || '默认分账'
    };
  }

  function getPlatformConfig() {
    const platform = ensure('subjects').find((subj: any) => subj.type === 'platform');
    return platform || { withdrawFreeAuditThreshold: 0 };
  }

  function ensureAccount(subjectId: number, subjectName: string, roleType: string) {
    const accounts = ensure('subjectAccounts');
    let acc = accounts.find((a: any) => a.subjectId === subjectId);
    if (!acc) {
      acc = { id: nextId(accounts), subjectId, subjectName, roleType, availableBalance: 0, frozenBalance: 0, totalIncome: 0, totalWithdrawn: 0, updateTime: now() };
      accounts.unshift(acc);
    }
    return acc;
  }

  function creditAccount(acc: any, amount: number) {
    acc.availableBalance = Math.round((acc.availableBalance + amount) * 100) / 100;
    acc.totalIncome = Math.round((acc.totalIncome + amount) * 100) / 100;
    acc.updateTime = now();
  }

  function debitAccount(acc: any, amount: number) {
    acc.availableBalance = Math.round((acc.availableBalance - amount) * 100) / 100;
    acc.totalWithdrawn = Math.round((acc.totalWithdrawn + amount) * 100) / 100;
    acc.updateTime = now();
  }

  function addFlow(flow: Record<string, any>) {
    const flows = ensure('fundFlows');
    const pool = ensure('fundPool')[0] || { totalBalance: 0 };
    flows.unshift({
      id: nextId(flows),
      flowNo: flow.flowNo || `FF${Date.now()}`,
      type: flow.type,
      direction: flow.direction,
      amount: flow.amount,
      subjectId: flow.subjectId ?? null,
      subjectName: flow.subjectName ?? '',
      roleType: flow.roleType ?? '',
      orderNo: flow.orderNo ?? '',
      poolBalanceAfter: flow.poolBalanceAfter ?? pool.totalBalance,
      remark: flow.remark ?? '',
      createTime: now()
    });
  }

  /**
   * 订单支付入账：统一进资金池，按分账规则记账到各方账户（不入真实账户）
   */
  function orderIncome(orderId: number) {
    const orders = ensure('orders');
    const order = orders.find((o: any) => o.id === orderId);
    if (!order) return;
    if (order.incomeRecorded) {
      window.$message?.warning('该订单已入账，不能重复入账');
      return;
    }
    const paid = order.paidAmount / 100; // 分转元
    const split = order.split || {};
    const pool = ensure('fundPool')[0];
    const subjects = ensure('subjects');

    // 平台账户（最高级，收益留在池子）
    const platform = subjects.find((subj: any) => subj.type === 'platform');
    const platformAcc = ensureAccount(platform?.id ?? 40, platform?.name ?? '平台', 'platform');
    creditAccount(platformAcc, split.platformShare ?? 0);

    // 门店
    const store = subjects.find((subj: any) => subj.name === order.store && subj.type === 'store');
    if (store) creditAccount(ensureAccount(store.id, store.name, 'store'), split.storeShare ?? 0);

    // 供应商（成本）
    const supplierAmount = split.costTotal ?? 0;
    if (supplierAmount > 0) {
      const supplier = subjects.find((subj: any) => subj.type === 'supplier');
      if (supplier) creditAccount(ensureAccount(supplier.id, supplier.name, 'supplier'), supplierAmount);
    }

    // 资源方
    if (split.channelShare > 0) {
      const resource = subjects.find((subj: any) => subj.type === 'resource');
      if (resource) creditAccount(ensureAccount(resource.id, resource.name, 'resource'), split.channelShare);
    }

    // 投资人
    if (split.investorShare > 0) {
      const investor = subjects.find((subj: any) => subj.type === 'investor');
      if (investor) creditAccount(ensureAccount(investor.id, investor.name, 'investor'), split.investorShare);
    }

    pool.totalBalance = Math.round((pool.totalBalance + paid) * 100) / 100;
    pool.updateTime = now();
    order.incomeRecorded = true;

    addFlow({
      type: 'INCOME', direction: 'in', amount: paid,
      subjectId: platform?.id ?? 40, subjectName: platform?.name ?? '平台', roleType: 'platform',
      orderNo: order.orderNo, poolBalanceAfter: pool.totalBalance, remark: '订单支付入账'
    });
    persist();
  }

  /**
   * 提现：金额 <= 免审阈值直接出款，否则待审核
   */
  function applyWithdraw(subjectId: number, amount: number) {
    const accounts = ensure('subjectAccounts');
    const acc = accounts.find((a: any) => a.subjectId === subjectId);
    if (!acc) {
      window.$message?.warning('该经营方无记账账户');
      return;
    }
    if (acc.roleType === 'platform') {
      window.$message?.warning('平台账户不支持提现');
      return;
    }
    if (amount <= 0) {
      window.$message?.warning('提现金额必须大于 0');
      return;
    }
    if (amount > acc.availableBalance) {
      window.$message?.error('可提现余额不足');
      return;
    }
    const platform = getPlatformConfig();
    const threshold = Number(platform.withdrawFreeAuditThreshold || 0);
    const withdrawals = ensure('withdrawals');
    const free = amount <= threshold;
    const status = free ? 'approved' : 'pending';

    withdrawals.unshift({
      id: nextId(withdrawals),
      subjectId,
      nickName: acc.subjectName,
      roleType: acc.roleType,
      amount,
      status,
      applyTime: now(),
      reviewTime: free ? now() : null,
      reviewer: free ? 'system' : null
    });

    if (free) {
      debitAccount(acc, amount);
      const pool = ensure('fundPool')[0];
      pool.totalBalance = Math.round((pool.totalBalance - amount) * 100) / 100;
      pool.updateTime = now();
      addFlow({
        type: 'WITHDRAW', direction: 'out', amount,
        subjectId, subjectName: acc.subjectName, roleType: acc.roleType,
        orderNo: '', poolBalanceAfter: pool.totalBalance, remark: '提现出款（免审）'
      });
    }
    persist();
  }

  function freezeAccount(subjectId: number, reason = '') {
    const acc = ensure('subjectAccounts').find((a: any) => a.subjectId === subjectId);
    if (!acc) return;
    if (acc.availableBalance <= 0) {
      window.$message?.warning('无可冻结余额');
      return;
    }
    const amount = acc.availableBalance;
    acc.availableBalance = 0;
    acc.frozenBalance = Math.round((acc.frozenBalance + amount) * 100) / 100;
    acc.updateTime = now();
    addFlow({ type: 'FREEZE', direction: 'out', amount, subjectId, subjectName: acc.subjectName, roleType: acc.roleType, remark: reason || '冻结余额' });
    persist();
  }

  function unfreezeAccount(subjectId: number, reason = '') {
    const acc = ensure('subjectAccounts').find((a: any) => a.subjectId === subjectId);
    if (!acc) return;
    if (acc.frozenBalance <= 0) {
      window.$message?.warning('无可解冻余额');
      return;
    }
    const amount = acc.frozenBalance;
    acc.frozenBalance = 0;
    acc.availableBalance = Math.round((acc.availableBalance + amount) * 100) / 100;
    acc.updateTime = now();
    addFlow({ type: 'UNFREEZE', direction: 'in', amount, subjectId, subjectName: acc.subjectName, roleType: acc.roleType, remark: reason || '解冻余额' });
    persist();
  }

  function bindInvestorToStore(storeId: number, investorCode: string, reason = '') {
    const subjects = ensure('subjects');
    const store = subjects.find((s: any) => s.id === storeId && s.type === 'store');
    if (!store) return;
    if (store.investorId) {
      window.$message?.warning('该门店已绑定投资人，请先解绑');
      return;
    }
    const investor = subjects.find((s: any) => s.code === investorCode && s.type === 'investor');
    if (!investor) return;
    // 门店一对一
    patch('subjects', storeId, { investorId: investorCode, investorName: investor.name }, '主体管理', '绑定投资人', 'name', reason);
    // 投资人一对多：追加门店 code
    const ids = Array.isArray(investor.relatedStoreIds) ? investor.relatedStoreIds : [];
    if (!ids.includes(store.code)) {
      patch('subjects', investor.id, { relatedStoreIds: [...ids, store.code], relatedStore: store.name }, '主体管理', '绑定门店', 'name', reason);
    }
  }

  function unbindInvestorFromStore(storeId: number, reason = '') {
    const subjects = ensure('subjects');
    const store = subjects.find((s: any) => s.id === storeId && s.type === 'store');
    if (!store) return;
    if (!store.investorId) {
      window.$message?.warning('该门店未绑定投资人');
      return;
    }
    const investorCode = store.investorId;
    patch('subjects', storeId, { investorId: null, investorName: '未绑定' }, '主体管理', '解绑投资人', 'name', reason);
    const investor = subjects.find((s: any) => s.code === investorCode && s.type === 'investor');
    if (investor) {
      const ids = (Array.isArray(investor.relatedStoreIds) ? investor.relatedStoreIds : []).filter((c: string) => c !== store.code);
      const remainNames = subjects.filter((s: any) => s.type === 'store' && ids.includes(s.code)).map((s: any) => s.name);
      patch('subjects', investor.id, { relatedStoreIds: ids, relatedStore: remainNames.join('、') || '未绑定' }, '主体管理', '解绑门店', 'name', reason);
    }
  }



  function bindResourceToStore(resourceId: number, storeCode: string, reason = '') {
    const subjects = ensure('subjects');
    const resource = subjects.find((s: any) => s.id === resourceId && s.type === 'resource');
    if (!resource) return;
    const store = subjects.find((s: any) => s.code === storeCode && s.type === 'store');
    if (!store) return;
    const ids = Array.isArray(resource.boundStoreIds) ? resource.boundStoreIds : [];
    if (!ids.includes(store.code)) {
      patch('subjects', resourceId, { boundStoreIds: [...ids, store.code], boundStoreCount: ids.length + 1 }, '主体管理', '绑定门店', 'name', reason);
    }
  }

  function unbindResourceFromStore(resourceId: number, storeCode: string, reason = '') {
    const subjects = ensure('subjects');
    const resource = subjects.find((s: any) => s.id === resourceId && s.type === 'resource');
    if (!resource) return;
    const ids = (Array.isArray(resource.boundStoreIds) ? resource.boundStoreIds : []).filter((c: string) => c !== storeCode);
    patch('subjects', resourceId, { boundStoreIds: ids, boundStoreCount: ids.length }, '主体管理', '解绑门店', 'name', reason);
  }  return {
    data,
    subjects: computed(() => ensure('subjects')),
    users: computed(() => ensure('users')),
    roleApplications: computed(() => ensure('roleApplications')),
    roles: computed(() => ensure('roles')),
    grants: computed(() => ensure('grants')),
    products: computed(() => ensure('products').filter((item: any) => !item.deleted)),
    specs: computed(() => ensure('specs')),
    splitRules: computed(() => ensure('splitRules')),
    orders: computed(() => ensure('orders')),
    payments: computed(() => ensure('payments')),
    refunds: computed(() => ensure('refunds')),
    verifies: computed(() => ensure('verifies')),
    snapshots: computed(() => ensure('snapshots')),
    reconciles: computed(() => ensure('reconciles')),
    features: computed(() => ensure('features')),
    auditLogs: computed(() => ensure('auditLogs')),
    withdrawals: computed(() => ensure('withdrawals')),
    verifyPool: computed(() => ensure('verifyPool')),
    coupons: computed(() => ensure('coupons')),
    storedValuePackages: computed(() => ensure('storedValuePackages')),
    giftCards: computed(() => ensure('giftCards')),
    giftCardDenominations: computed(() => ensure('giftCardDenominations')),
    storeTypes: computed(() => ensure('storeTypes')),
    productCategories: computed(() => ensure('productCategories')),
    dictEntries: computed(() => ensure('dictEntries')),
    fundPool: computed(() => ensure('fundPool')),
    subjectAccounts: computed(() => ensure('subjectAccounts')),
    fundFlows: computed(() => ensure('fundFlows')),
    exchangeRecords: computed(() => ensure('exchangeRecords')),
    giftCardOrders: computed(() => ensure('giftCardOrders')),
    provinces: computed(() => ensure('provinces')),
    cities: computed(() => ensure('cities')),
    referralConfig: computed(() => (data.value as any).referralConfig || { id: 1 }),
    pointsProducts: computed(() => ensure('pointsProducts')),
    pointsEarningRules: computed(() => ensure('pointsEarningRules')),
    signInDaily: computed(() => (data.value as any).signInDaily ?? 1),
    signInRewards: computed(() => (data.value as any).signInRewards || [{ days: 7, amount: 20 }]),
    memberLevels: computed(() => ensure('memberLevels')),
    comments: computed(() => ensure('comments')),
    add,
    update,
    remove,
    patch,
    listFiltered,
    loadRemote,
    loadRemoteAll,
    isRemote,
    remoteLoaded,
    bindUserRole,
    unbindUserRole,
    bindSubjectUser,
    unbindSubjectUser,
    reviewApplication,
    toggleFeature,
    refundOrder,
    enableSplitRule,
    calcOrderSplit,
    bindInvestorToStore,
    unbindInvestorFromStore,
    bindResourceToStore,
    unbindResourceFromStore,
    saveSignInRule,
    saveReferralConfig,
    loadReferralConfig,
    refundAudit,
    reviewWithdraw,
    orderIncome,
    applyWithdraw,
    freezeAccount,
    unfreezeAccount,
    getPlatformConfig,
    executeVerify,
    reviewComment
  };
});












