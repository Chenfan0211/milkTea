const productImage = '/assets/images/3x/menu-product.jpg'
const badgeMemberIcon = '/assets/icons/lucide/member.svg'

function createSpecGroups() {
  return [
    {
      id: 'size',
      label: '份量',
      options: [
        { id: 'medium', label: '中杯', selected: true, priceDelta: 0 }
      ]
    },
    {
      id: 'sweetness',
      label: '甜度',
      options: [
        { id: 'seven', label: '7分甜', selected: true, priceDelta: 0, icon: 'star' },
        { id: 'no-sugar', label: '不额外加糖', selected: false, priceDelta: 0 }
      ]
    },
    {
      id: 'temperature',
      label: '温度',
      options: [
        { id: 'less-ice', label: '少冰', selected: false, priceDelta: 0 },
        { id: 'no-ice', label: '去冰（微凉）', selected: false, priceDelta: 0 }
      ]
    },
    {
      id: 'topping',
      label: '加料（必选1份）',
      options: [
        { id: 'none', label: '不加马蹄粉圆', selected: true, priceDelta: 0 },
        { id: 'horseshoe', label: '加马蹄粉圆', selected: false, priceDelta: 2 }
      ]
    }
  ]
}

function createProduct(id, name, price = 13.9, originalPrice = 16, badgeIcon = '') {
  const tags = ['年度热销', '五窨茉莉花茶']
  const description = '草本清香与醇厚茶韵交融，入口清甜顺滑，回甘自然。'
  return {
    id,
    name,
    tags,
    description,
    price,
    originalPrice,
    badgeIcon,
    image: productImage,
    specDetail: {
      galleryImage: productImage,
      imageDisclaimer: '图片与杯型仅供参考，具体请以实物为准',
      promotionText: '周三会员日招牌饮品85折',
      priceLabel: '小程序价',
      startPrice: price,
      originalPrice,
      discountRate: 0.85,
      tag: tags[1],
      description,
      ingredients: '一级千目抹茶+芒果鲜果+牛乳芝士+HPP冷冻芒果汁',
      allergens: '饮品内含有乳制品、芒果、无花果碎，过敏者请谨慎选择',
      cupCapacity: '杯型容量中杯500ml，标注容量及图片仅供参考，饮品量请以实际出品为准',
      tips: [
        '抹茶含有天然咖啡因，若您对咖啡因敏感，处于孕期、哺乳期或晚间饮用，建议您酌情选择。',
        '本产品含有无花果碎，少数人群可能对无花果或相关果类过敏。',
        '时令芒果品种会更换使用，请以门店实际出品为准。',
        '避免阳光直射、高温或急冻。',
        '建议2小时内饮用，开盖直饮风味更佳。'
      ],
      specGroups: createSpecGroups()
    }
  }
}

const classicGroups = [
  {
    id: 'recommend',
    label: '店长推荐',
    categories: [
      {
        id: 'herbal',
        label: '草本养生茶',
        products: [
          createProduct('classic-001', '五窨茉莉抹茶', 13.9, 16, badgeMemberIcon),
          createProduct('classic-002', '金桂轻乳茶'),
          createProduct('classic-003', '青提茉莉冰茶', 15.9, 18),
          createProduct('classic-004', '陈皮普洱轻乳茶', 14.9, 17),
          createProduct('herbal-001', '桂香暖润茶', 12.9, 15),
          createProduct('herbal-002', '陈皮山楂茶', 13.9, 16),
          createProduct('herbal-003', '黑枸杞玫瑰茶', 15.9, 18, badgeMemberIcon)
        ]
      }
    ]
  },
  {
    id: 'leaf',
    label: '原叶臻选',
    categories: [
      {
        id: 'traditional',
        label: '传统原叶茶',
        products: [
          createProduct('leaf-001', '茉莉银毫', 14.9, 17),
          createProduct('leaf-002', '高山绿茶', 12.9, 15),
          createProduct('leaf-003', '鸭屎香单丛', 16.9, 19),
          createProduct('traditional-001', '冷萃龙井', 15.9, 18),
          createProduct('traditional-002', '白桃乌龙', 15.9, 18)
        ]
      }
    ]
  }
]

const featuredGroups = [
  {
    id: 'featured-signature',
    label: '招牌热销',
    categories: [
      {
        id: 'featured-season',
        label: '季节限定',
        products: [
          createProduct('featured-001', '五窨茉莉抹茶', 13.9, 16, badgeMemberIcon),
          createProduct('featured-002', '抹茶轻乳茶', 15.9, 18),
          createProduct('featured-003', '金秋桂花茶', 14.9, 17),
          createProduct('season-001', '青提抹茶冰萃', 16.9, 19),
          createProduct('season-002', '草莓抹茶奶绿', 17.9, 20)
        ]
      }
    ]
  }
]

const menuTabs = [
  { id: 'classic', label: '经典菜单', groups: classicGroups },
  { id: 'featured', label: '招牌主打', groups: featuredGroups }
]

const stores = [
  {
    id: 'store-001',
    name: '星沙乐运魔方店',
    distanceText: '距您2.3km',
    distanceKm: '2.30km',
    address: '湖南省长沙市长沙县星沙街道开元东路288号乐运魔方1层L108号铺（靠近中庭）',
    modes: ['pickup', 'dinein']
  },
  {
    id: 'store-002',
    name: '松雅湖吾悦广场店',
    distanceText: '距您4.8km',
    distanceKm: '4.80km',
    address: '湖南省长沙市长沙县东四路与滨湖东路交汇处吾悦广场1层B区B108号铺（靠近1号门）',
    modes: ['pickup', 'dinein']
  },
  {
    id: 'store-003',
    name: '长沙高铁南站店',
    distanceText: '距您6.1km',
    distanceKm: '6.10km',
    address: '湖南省长沙市雨花区花侯路长沙南站西广场1层S102号铺（地铁2号线出口旁）',
    modes: ['pickup']
  }
]

const userProfile = {
  nickname: '微信用户',
  avatar: '/assets/images/3x/profile-avatar.jpg',
  vipLevel: 'VIP1',
  nextLevel: 'VIP2',
  progressCurrent: 10,
  progressTarget: 45,
  couponCount: 2,
  balance: 0,
  points: 0,
  giftCards: [
    { id: 'gift-001', name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg' },
    { id: 'gift-002', name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg' }
  ]
}

const giftCardGroups = [
  {
    id: 'popular',
    title: '人气礼品卡',
    cards: [
      { id: 'gift-001', name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg' },
      { id: 'gift-002', name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg' }
    ]
  },
  {
    id: 'limited',
    title: '限定心意卡',
    cards: [
      { id: 'gift-003', name: '限定心意', image: '/assets/images/3x/gift-card-limited.jpg' }
    ]
  }
]

const giftCardDenominations = [
  { id: 'gift-value-100', faceValue: 100, salePrice: 100 },
  { id: 'gift-value-200', faceValue: 200, salePrice: 200 },
  { id: 'gift-value-500', faceValue: 500, salePrice: 500 }
]

const pointsCategories = [
  { id: 'all', label: '全部' },
  { id: 'pet', label: '宠物公益专区' },
  { id: 'coupon', label: '优惠券区' }
]

const pointsProducts = [
  {
    id: 'points-pet-food',
    name: '五零时光公益宠粮',
    category: 'pet',
    image: '/assets/images/3x/points-product-pet.jpg',
    points: 20,
    stock: 5879,
    badge: '限时抢兑',
    badgeInImage: false,
    limitText: '',
    description: '【五零时光公益宠粮】五零时光小程序积分商城正式上线宠物公益专区，每月善款用于购买大米捐赠并持续追踪后续。\n1. 第九期捐助周期：2026年9月1日—2026年9月30日\n2. 预计宠粮送达救助地时间：2026年10月底前\n3. 公示说明：按整月结算，次月10日前公示当期捐助情况\n本期捐助类型为大米，因救助基地狗狗数量较多，具体送达情况以公示结果为准。'
  },
  {
    id: 'points-matcha-buy-one',
    name: '超浓抹茶系列买一送一券',
    category: 'coupon',
    image: '/assets/images/3x/points-product-matcha.jpg',
    points: 300,
    stock: 51,
    badge: '限时抢兑',
    badgeInImage: false,
    limitText: '*每人仅可兑换一次',
    description: '一、券基本信息\n商品类型：饮品优惠券。\n兑换方式：通过五零时光官方渠道使用积分兑换。\n券面查看：成功兑换后，可在“五零时光点单小程序”-【我的】-【优惠券】中查看详情及使用规则。\n二、有效期\n兑换成功当日起计算，7个自然日内有效（含兑换当天），逾期未使用自动失效。\n三、适用商品\n仅限兑换时指定的五零时光饮品，不包括周边商品、固定套餐、额外加购小料和包装袋。'
  },
  {
    id: 'points-single-cup',
    name: '超浓抹茶系列单杯3元券',
    category: 'coupon',
    image: '/assets/images/3x/points-product-single.jpg',
    points: 300,
    stock: 51,
    badge: '限时抢兑',
    badgeInImage: true,
    limitText: '*每人仅可兑换一次',
    description: '超浓抹茶系列单杯3元优惠券，兑换后可在指定饮品结算时抵扣，具体适用范围和有效期以券面说明为准。'
  },
  {
    id: 'points-second-cup-half',
    name: '超浓抹茶系列第2杯半价券',
    category: 'coupon',
    image: '/assets/images/3x/points-product-half.jpg',
    points: 300,
    stock: 51,
    badge: '限时抢兑',
    badgeInImage: true,
    limitText: '*每人仅可兑换一次',
    description: '超浓抹茶系列第2杯半价券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。'
  }
]

const signInRewards = [
  { id: 'signin-7', days: 7, title: '5积分', description: '连续签到7天', type: 'points', amount: 5 },
  { id: 'signin-15', days: 15, title: '10积分', description: '连续签到15天', type: 'points', amount: 10 },
  { id: 'signin-30', days: 30, title: '全品类买一送一券', description: '连续签到30天', type: 'coupon', amount: 0 }
]

const signInRules = [
  '每日签到可领取1积分；',
  '连续签到7天额外获得5积分，共12积分；',
  '连续签到15天额外获得10积分，共30积分；',
  '连续签到30天额外获得全品类买一送一券，共累计45积分。'
]

const pointsSignIn = {
  year: 2026,
  month: 9,
  today: '2026-09-17',
  todayLabel: '9.17',
  weekDates: [
    { key: '2026-09-16', label: '9.16' },
    { key: '2026-09-17', label: '9.17' },
    { key: '2026-09-18', label: '9.18' },
    { key: '2026-09-19', label: '9.19' },
    { key: '2026-09-20', label: '9.20' },
    { key: '2026-09-21', label: '9.21' }
  ]
}

const pointsRecords = []

const exchangeRecordCategories = [
  { id: 'all', label: '全部' },
  { id: 'pending_payment', label: '待支付' },
  { id: 'pending_delivery', label: '待发货' },
  { id: 'pending_receipt', label: '待收货' },
  { id: 'completed', label: '已完成' }
]

const exchangeRecords = []

const homeShortcuts = [
  { id: 'coupon', label: '会员领券', icon: '/assets/icons/lucide/ticket-percent.svg' },
  { id: 'stored-value', label: '储值有礼', icon: '/assets/icons/lucide/gift.svg' },
  { id: 'points-mall', label: '积分商城', icon: '/assets/icons/lucide/badge-japanese-yen.svg' },
  { id: 'service', label: '客服入口', icon: '/assets/icons/lucide/headset.svg' }
]

const profileFunctions = [
  { id: 'share', label: '分享有礼', icon: '/assets/icons/lucide/share-2.svg' },
  { id: 'coupon-wallet', label: '我的券包', icon: '/assets/icons/lucide/ticket.svg' },
  { id: 'points', label: '积分兑换', icon: '/assets/icons/lucide/badge-japanese-yen.svg' },
  { id: 'benefits', label: '会员权益', icon: '/assets/icons/lucide/member.svg' },
  { id: 'address', label: '收货地址', icon: '/assets/icons/lucide/map-pinned.svg' },
  { id: 'service', label: '客服中心', icon: '/assets/icons/lucide/headset.svg' },
  { id: 'activity', label: '活动报名', icon: '/assets/icons/lucide/calendar-check.svg' },
  { id: 'cooperation', label: '加盟合作', icon: '/assets/icons/lucide/handshake.svg' }
]

const orderCategories = [
  { id: 'all', label: '全部订单' },
  { id: 'store', label: '门店订单' },
  { id: 'purchase', label: '买单订单' },
  { id: 'stored-value', label: '储值订单' },
  { id: 'coupon-group', label: '拼券订单' }
]

const coupons = [
  {
    id: 'coupon-001',
    quantity: 2,
    type: 'voucher',
    amount: 3,
    condition: '满20可用',
    title: '【VIP1】五零时光3元代金券（满20）',
    expiryText: '2026-09-29 23:59 到期',
    brand: '五零时光',
    couponNo: '1306715891380928513',
    applicableStoreIds: stores.map(store => store.id),
    applicableStores: '查看门店',
    applicableProducts: '查看适用商品',
    channel: '不限制',
    scenes: '买单、堂食(门店就餐)、堂食(打包外带)',
    validityPeriod: '2026-09-15 00:00:00~2026-09-29 23:59:59',
    usageTime: '00:00:00~23:59:59',
    paymentRestriction: '',
    description: '五零时光送您一张任意饮品3元券（满20可用，仅饮品可用，不包括任何周边商品、固定套餐、额外加购的小料、包装袋等），可在五零时光全门店使用，获得券当日起生效，有效期15天（同一类型券每天仅限使用一张）。',
    source: '开卡权益',
    expanded: false
  }
]

const orders = [
  {
    id: 'order-001',
    category: 'store',
    type: '堂食',
    storeName: '星沙乐运魔方店',
    storePhone: '0731-88880001',
    status: '已完成',
    orderStatus: 'completed',
    statusTitle: '已完成',
    statusNote: '感谢您的光临，期待再次为您服务',
    pickupCode: 'A026',
    completedTime: '2026-09-16 13:28',
    amount: 21,
    originalAmount: 27.8,
    discountAmount: 6.8,
    couponName: '五零时光3元代金券（满20）',
    couponAmount: -3,
    count: 2,
    items: [
      { id: 'classic-002', name: '金桂轻乳茶', spec: '中杯,5分甜,热', image: productImage, unitPrice: 13.9, originalPrice: 16, quantity: 1 },
      { id: 'classic-001', name: '五窨茉莉抹茶', spec: '中杯,7分甜,冰沙', image: productImage, unitPrice: 13.9, originalPrice: 16, quantity: 1, badgeIcon: badgeMemberIcon }
    ],
    mealInfo: [
      { label: '用餐方式', value: '堂食' },
      { label: '就餐时间', value: '2026-09-16 13:02' }
    ],
    orderInfo: {
      orderNo: 'WX202609160001',
      createdAt: '2026-09-16 13:02',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-002',
    category: 'store',
    type: '打包',
    storeName: '松雅湖吾悦广场店',
    storePhone: '0731-88880002',
    status: '已完成',
    orderStatus: 'completed',
    statusTitle: '已完成',
    statusNote: '感谢您的光临，期待再次为您服务',
    pickupCode: 'B012',
    completedTime: '2026-09-15 18:47',
    amount: 9.9,
    originalAmount: 16,
    discountAmount: 6.1,
    couponName: '五零时光6.1元优惠',
    couponAmount: -6.1,
    count: 1,
    items: [
      { id: 'classic-004', name: '陈皮普洱轻乳茶', spec: '大杯,3分甜,少冰', image: productImage, unitPrice: 9.9, originalPrice: 16, quantity: 1 }
    ],
    mealInfo: [
      { label: '用餐方式', value: '打包自取' },
      { label: '就餐时间', value: '2026-09-15 18:22' }
    ],
    orderInfo: {
      orderNo: 'WX202609150012',
      createdAt: '2026-09-15 18:22',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-003',
    category: 'store',
    type: '打包',
    storeName: '星沙乐运魔方店',
    storePhone: '0731-88880001',
    status: '已完成',
    orderStatus: 'completed',
    statusTitle: '已完成',
    statusNote: '感谢您的光临，期待再次为您服务',
    pickupCode: 'A015',
    completedTime: '2026-09-15 12:06',
    amount: 49.5,
    originalAmount: 58.6,
    discountAmount: 9.1,
    couponName: '五零时光9.1元优惠',
    couponAmount: -9.1,
    count: 4,
    items: [
      { id: 'classic-001', name: '五窨茉莉抹茶', spec: '中杯,7分甜,冰沙', image: productImage, unitPrice: 13.9, originalPrice: 16, quantity: 1, badgeIcon: badgeMemberIcon },
      { id: 'classic-002', name: '金桂轻乳茶', spec: '中杯,5分甜,热', image: productImage, unitPrice: 13.9, originalPrice: 16, quantity: 1 },
      { id: 'classic-003', name: '青提茉莉冰茶', spec: '中杯,7分甜,少冰', image: productImage, unitPrice: 15.9, originalPrice: 18, quantity: 1 },
      { id: 'classic-004', name: '陈皮普洱轻乳茶', spec: '大杯,3分甜,少冰', image: productImage, unitPrice: 14.9, originalPrice: 17, quantity: 1 }
    ],
    mealInfo: [
      { label: '用餐方式', value: '打包自取' },
      { label: '就餐时间', value: '2026-09-15 11:40' }
    ],
    orderInfo: {
      orderNo: 'WX202609150008',
      createdAt: '2026-09-15 11:40',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-004',
    category: 'purchase',
    type: '堂食',
    storeName: '长沙高铁南站店',
    storePhone: '0731-88880003',
    status: '已完成',
    orderStatus: 'completed',
    statusTitle: '已完成',
    statusNote: '感谢您的光临，期待再次为您服务',
    pickupCode: 'C008',
    completedTime: '2026-09-14 19:15',
    amount: 18,
    originalAmount: 20,
    discountAmount: 2,
    couponName: '五零时光2元优惠',
    couponAmount: -2,
    count: 1,
    items: [
      { id: 'traditional-002', name: '白桃乌龙', spec: '大杯,5分甜,去冰', image: productImage, unitPrice: 18, originalPrice: 20, quantity: 1 }
    ],
    mealInfo: [
      { label: '用餐方式', value: '堂食' },
      { label: '就餐时间', value: '2026-09-14 19:02' }
    ],
    orderInfo: {
      orderNo: 'WX202609140021',
      createdAt: '2026-09-14 19:02',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-005',
    category: 'stored-value',
    type: '堂食',
    storeName: '星沙乐运魔方店',
    storePhone: '0731-88880001',
    status: '已完成',
    orderStatus: 'completed',
    statusTitle: '已完成',
    statusNote: '储值成功，赠送金额已到账',
    pickupCode: 'A009',
    completedTime: '2026-09-13 15:30',
    amount: 100,
    originalAmount: 100,
    discountAmount: 0,
    couponName: '',
    couponAmount: 0,
    count: 1,
    items: [
      { id: 'stored-value-100', name: '五零时光储值卡', spec: '充值100元赠10元', image: productImage, unitPrice: 100, originalPrice: 100, quantity: 1 }
    ],
    mealInfo: [
      { label: '用餐方式', value: '堂食' },
      { label: '就餐时间', value: '2026-09-13 15:12' }
    ],
    orderInfo: {
      orderNo: 'WX202609130005',
      createdAt: '2026-09-13 15:12',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-006',
    category: 'coupon-group',
    type: '打包',
    storeName: '松雅湖吾悦广场店',
    storePhone: '0731-88880002',
    status: '已取消',
    orderStatus: 'canceled',
    statusTitle: '已取消',
    statusNote: '订单已取消，如有疑问请联系门店',
    pickupCode: '',
    amount: 14.9,
    originalAmount: 28.8,
    discountAmount: 13.9,
    couponName: '五零时光13.9元优惠',
    couponAmount: -13.9,
    count: 2,
    items: [
      { id: 'classic-003', name: '青提茉莉冰茶', spec: '中杯,7分甜,少冰', image: productImage, unitPrice: 15.9, originalPrice: 18, quantity: 1 },
      { id: 'herbal-001', name: '桂香暖润茶', spec: '中杯,5分甜,热', image: productImage, unitPrice: 12.9, originalPrice: 15, quantity: 1 }
    ],
    mealInfo: [
      { label: '用餐方式', value: '打包自取' },
      { label: '就餐时间', value: '2026-09-12 10:05' }
    ],
    orderInfo: {
      orderNo: 'WX202609120003',
      createdAt: '2026-09-12 10:05',
      payMethod: '微信支付'
    }
  }
  ,
  {
    id: 'order-007',
    category: 'store',
    type: '自取',
    storeName: '武汉武胜凯德店',
    storePhone: '027-88880004',
    status: '请取餐',
    orderStatus: 'paid_pickup',
    amount: 16.9,
    originalAmount: 23,
    discountAmount: 6.1,
    count: 1,
    pickupCode: '0424',
    statusTitle: '请取餐',
    statusNote: '本店为自取餐厅，请您留意叫号屏',
    progressSteps: [
      { id: 'created', label: '已下单', icon: 'receipt', done: true },
      { id: 'making', label: '制作中', icon: 'chef-hat', done: true },
      { id: 'pickup', label: '请取餐', icon: 'circle-check-big', active: true }
    ],
    couponBanner: {
      title: '入群领30元券包',
      actionText: '点击领取 >',
      coupons: [
        { id: 'coupon-1', value: '¥2', note: '无门槛', tag: '代金券x1' },
        { id: 'coupon-2', value: '¥4', note: '抹茶芝士芭乐', tag: '代金券x1' },
        { id: 'coupon-3', value: '¥5', note: '满25可用', tag: '代金券x1' },
        { id: 'coupon-4', value: '8.8折', note: '单杯可用', tag: '优惠券x1' },
        { id: 'coupon-5', value: '7折', note: '第二杯可用', tag: '优惠券x1' }
      ]
    },
    rewards: [
      { id: 'health', label: '健康值', value: '+16.9', icon: 'gem' },
      { id: 'points', label: '积分', value: '+16.9', icon: 'star' }
    ],
    rewardNote: '订单完成后3小时内发放',
    items: [
      {
        id: 'manta-001',
        name: '抹茶芝士芭乐（首创）',
        spec: '[中杯,5分甜,少冰],加马蹄粉圆',
        image: productImage,
        unitPrice: 20.9,
        originalPrice: 23,
        quantity: 1
      }
    ],
    couponName: '真茶屋抹茶芝士红心芭乐4元券【社群】',
    couponAmount: -4,
    mealInfo: [
      { label: '用餐方式', value: '堂食' },
      { label: '就餐时间', value: '立即取餐' }
    ],
    orderInfo: {
      orderNo: 'D00235803499139801088',
      createdAt: '2026-09-16 20:31',
      payMethod: '微信支付'
    }
  },
  {
    id: 'order-008',
    category: 'store',
    type: '堂食',
    storeName: '长沙五一广场平和堂店',
    storePhone: '0731-88880005',
    status: '已完成',
    orderStatus: 'completed',
    amount: 18.9,
    originalAmount: 21,
    discountAmount: 2.1,
    count: 1,
    pickupCode: '0402',
    statusTitle: '已完成',
    statusNote: '感谢您的光临，期待再次为您服务',
    completedTime: '2026-09-16 16:50',
    items: [
      {
        id: 'manta-002',
        name: '抹茶芝士芭乐（首创）',
        spec: '[中杯,5分甜,少冰],不加马蹄粉圆',
        image: productImage,
        unitPrice: 18.9,
        originalPrice: 21,
        quantity: 1
      }
    ],
    couponName: '真茶屋抹茶芝士红心芭乐2.1元优惠',
    couponAmount: -2.1,
    mealInfo: [
      { label: '用餐方式', value: '堂食' },
      { label: '就餐时间', value: '立即取餐' }
    ],
    orderInfo: {
      orderNo: 'D00235803499139801089',
      createdAt: '2026-09-16 16:42',
      payMethod: '微信支付'
    }
  }
]

const initialCartItems = [
  {
    id: 'cart-001',
    name: '红苹果乌龙冰奶',
    spec: '中杯,5分甜,标准冰，此为正常现象 搅匀即可',
    price: 14.9,
    originalPrice: 16,
    quantity: 1,
    selected: true,
    image: productImage,
    isNew: true
  }
]

function formatOrderAmount(amount) {
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(1)
}

module.exports = {
  formatOrderAmount,
  homeShortcuts,
  initialCartItems,
  menuTabs,
  coupons,
  giftCardDenominations,
  giftCardGroups,
  pointsCategories,
  pointsProducts,
  pointsRecords,
  pointsSignIn,
  signInRewards,
  signInRules,
  exchangeRecordCategories,
  exchangeRecords,
  orderCategories,
  orders,
  profileFunctions,
  stores,
  userProfile
}
