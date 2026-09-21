const roleDefinitions = [
  {
    id: 'store',
    label: '门店',
    description: '核销订单、查看每日收益与提现',
    icon: '/assets/icons/lucide/store.svg',
    brandIcon: '/assets/icons/lucide/store-brand.svg',
    whiteIcon: '/assets/icons/lucide/store-white.svg',
    badgeText: '经营角色'
  },
  {
    id: 'investor',
    label: '投资人',
    description: '申请点位投资、查看每月分佣与提现',
    icon: '/assets/icons/lucide/trending-up.svg',
    brandIcon: '/assets/icons/lucide/trending-up-brand.svg',
    whiteIcon: '/assets/icons/lucide/trending-up-white.svg',
    badgeText: '经营角色'
  },
  {
    id: 'resource',
    label: '资源方',
    description: '绑定门店、按门店订单提成、查看门店订单',
    icon: '/assets/icons/lucide/store.svg',
    brandIcon: '/assets/icons/lucide/store-brand.svg',
    whiteIcon: '/assets/icons/lucide/store-white.svg',
    badgeText: '经营角色'
  }
];

const settlementNotes = [
  '仅按订单实付金额分账，时光币兑换与抵扣部分不参与分账',
  '收益入账后先为待结算，T+1 转为可结算，退款订单同步冲正',
  '分账比例以万分比维护，金额统一精确到分，尾差归平台'
];

const withdrawRule = {
  instantLimit: '¥100.00',
  instantNote: '小额即时到账，无需人工审核',
  auditNote: '超过即时额度需后台审核，审核通过后出款',
  feeNote: '提现手续费与单笔上限由后台配置',
  failureNote: '失败或驳回将自动解冻对应金额',
  items: [
    {
      id: 'instant',
      icon: '/assets/icons/lucide/banknote.svg',
      title: '即时到账',
      description: '单笔不超过即时额度时，系统自动出款，无需人工审核。',
      extra: '当前即时额度：单笔 ≤ ¥100.00'
    },
    {
      id: 'audit',
      icon: '/assets/icons/lucide/clock-muted.svg',
      title: '大额审核',
      description: '超过即时额度需提交后台审核，审核通过后统一出款。',
      extra: '审核时效：1 个工作日'
    },
    {
      id: 'fee',
      icon: '/assets/icons/lucide/badge-percent.svg',
      title: '费用与上限',
      description: '提现手续费与单笔提现上限由平台后台配置，提交前会在页面展示本次到账金额。',
      extra: ''
    },
    {
      id: 'failure',
      icon: '/assets/icons/lucide/shield-check-muted.svg',
      title: '失败解冻',
      description: '提现失败或被驳回时，对应金额将自动解冻回到可提现余额，可重新发起。',
      extra: ''
    }
  ]
};

const roleDashboardData = {
  store: {
    title: '门店工作台',
    metrics: [
      { id: 'today-income', label: '今日收益', value: '¥328.60', icon: '/assets/icons/lucide/banknote.svg' },
      { id: 'today-orders', label: '今日订单', value: '42', icon: '/assets/icons/lucide/receipt.svg' },
      { id: 'pending-settlement', label: '待结算', value: '¥1,206.00', icon: '/assets/icons/lucide/badge-percent.svg' }
    ],
    actions: [
      {
        id: 'verify',
        label: '核销订单',
        icon: '/assets/icons/lucide/scan-line.svg',
        description: '扫码 / 输码 / 订单号核销'
      },
      { id: 'products', label: '选品管理', icon: '/assets/icons/lucide/shopping-bag.svg', description: '从运营后台商品中选择门店在售商品' },
      { id: 'income', label: '每日收益', icon: '/assets/icons/lucide/banknote.svg', description: '含退款冲正后净额' },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ],
    // 门店角色绑定的经营门店，选品上下架按该门店隔离。
    boundStoreId: 'store-001',
    boundStoreName: '星沙乐运魔方店',
    withdrawable: '¥1,206.00',
    withdrawRule,
    settlementNotes,
    recordsTitle: '今日核销记录',
    records: [
      { id: 'v-1', title: '抹茶芝士芭乐（首创）', meta: '取餐号 0402', amount: '¥18.90', time: '20:31' },
      { id: 'v-2', title: '红苹果乌龙冰奶', meta: '取餐号 0403', amount: '¥14.90', time: '19:16' },
      { id: 'v-3', title: '五窨茉莉抹茶', meta: '取餐号 0404', amount: '¥13.90', time: '18:02' }
    ]
  },
  investor: {
    title: '投资人工作台',
    metrics: [
      { id: 'month-commission', label: '本月分佣', value: '¥2,418.00', icon: '/assets/icons/lucide/trending-up.svg' },
      { id: 'invested-store', label: '投资点位', value: '1 家', icon: '/assets/icons/lucide/store.svg' },
      { id: 'settled', label: '已结算', value: '¥1,806.00', icon: '/assets/icons/lucide/badge-percent.svg' }
    ],
    actions: [
      {
        id: 'invest',
        label: '点位投资申请',
        icon: '/assets/icons/lucide/map-pinned.svg',
        description: '选择点位 → 审核 → 签约'
      },
      {
        id: 'commission',
        label: '月度分佣',
        icon: '/assets/icons/lucide/trending-up.svg',
        description: '查看每月分佣与明细'
      },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ],
    withdrawable: '¥1,806.00',
    withdrawRule,
    settlementNotes,
    recordsTitle: '近月分佣明细',
    records: [
      { id: 'c-1', title: '长沙五一广场平和堂店', meta: '2026-08 结算', amount: '¥1,806.00', time: '08-31' },
      { id: 'c-2', title: '长沙五一广场平和堂店', meta: '2026-08 待结算', amount: '¥612.00', time: '08-31' },
      { id: 'c-3', title: '点位申请·岳麓区', meta: '审核通过', amount: '—', time: '07-12' }
    ]
  },
  resource: {
    title: '资源方工作台',
    metrics: [
      { id: 'stores', label: '绑定门店', value: '2 家', icon: '/assets/icons/lucide/store.svg' },
      { id: 'commission', label: '累计提成', value: '¥3,420.50', icon: '/assets/icons/lucide/badge-percent.svg' },
      { id: 'today-commission', label: '今日提成', value: '¥86.40', icon: '/assets/icons/lucide/trending-up.svg' }
    ],
    actions: [
      {
        id: 'orders',
        label: '门店订单',
        icon: '/assets/icons/lucide/receipt.svg',
        description: '查看各绑定门店订单记录'
      },
      { id: 'income', label: '每日提成', icon: '/assets/icons/lucide/banknote.svg', description: '按门店订单实付金额提成' },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ],
    withdrawable: '¥3,420.50',
    withdrawRule,
    settlementNotes,
    boundStores: [
      { id: 'store-001', name: '星沙乐运魔方店', storeType: '奶茶/饮品' },
      { id: 'store-002', name: '松雅湖吾悦广场店', storeType: '奶茶/饮品' }
    ],
    recordsTitle: '门店订单提成',
    records: [
      { id: 'f-1', title: '五一广场店', meta: '抹茶芝士芭乐 x1', amount: '+¥4.20', time: '20:11' },
      { id: 'f-2', title: '岳麓店', meta: '红苹果乌龙冰奶 x1', amount: '+¥3.60', time: '17:40' },
      { id: 'f-3', title: '平和堂店', meta: '五窨茉莉抹茶 x2', amount: '+¥6.30', time: '15:08' }
    ]
  }
};

const verifyPool = {
  store: [
    {
      id: 'vp-1',
      pickupCode: 'A026',
      orderNo: 'D00235803499139801085',
      product: '抹茶芝士芭乐（首创）',
      spec: '中杯,少冰,加马蹄粉圆',
      amount: '¥18.90'
    },
    {
      id: 'vp-2',
      pickupCode: 'B012',
      orderNo: 'D00235803499139801087',
      product: '红苹果乌龙冰奶',
      spec: '中杯,标准冰',
      amount: '¥14.90'
    },
    {
      id: 'vp-3',
      pickupCode: 'A015',
      orderNo: 'D00235803499139801090',
      product: '五窨茉莉抹茶',
      spec: '中杯,标准冰',
      amount: '¥13.90'
    }
  ]
};

const verifiedRecords = {
  store: [
    {
      id: 'vr-1',
      title: '抹茶芝士芭乐（首创）',
      meta: '取餐号 0424',
      orderNo: 'D00235803499139801088',
      time: '2026-09-20 20:31:08',
      image: '/assets/images/3x/menu-product.jpg'
    },
    {
      id: 'vr-2',
      title: '红苹果乌龙冰奶',
      meta: '取餐号 0403',
      orderNo: 'D00235803499139801087',
      time: '2026-09-20 19:16:42',
      image: '/assets/images/3x/menu-product.jpg'
    }
  ]
};

// 收益结算流转链路：pending 先为待结算，T+1 转可结算，退款同步冲正。
const INCOME_FLOW = {
  pending: [
    { id: 'created', title: '收益入账', description: '订单完成，收益已计入待结算', offset: 0 },
    { id: 'settling', title: '待结算', description: 'T+1 转为可结算，期间可被退款冲正', offset: 1800 },
    { id: 'settled', title: '可结算', description: '结算完成后可提现或继续累计' },
    { id: 'withdrawn', title: '已提现', description: '发起提现后从可结算余额扣减' }
  ],
  settled: [
    { id: 'created', title: '收益入账', description: '订单完成，收益已计入待结算', offset: 0 },
    { id: 'settling', title: '待结算', description: 'T+1 转为可结算', offset: 1800 },
    { id: 'settled', title: '可结算', description: '结算完成，已转入可结算余额', offset: 86400 },
    { id: 'withdrawn', title: '已提现', description: '发起提现后从可结算余额扣减' }
  ],
  reversed: [
    { id: 'created', title: '收益入账', description: '订单完成，收益已计入待结算', offset: 0 },
    { id: 'settling', title: '待结算', description: 'T+1 转为可结算', offset: 1800 },
    { id: 'reversed', title: '退款冲正', description: '订单发生退款，对应收益同步冲正', offset: 5400 }
  ]
};

const INCOME_ACTIVE_STEP = {
  pending: 'settling',
  settled: 'settled',
  reversed: 'reversed'
};

function buildIncomeTimeline(record) {
  const flow = INCOME_FLOW[record.status] || INCOME_FLOW.pending;
  const activeStep = INCOME_ACTIVE_STEP[record.status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = new Date(String(record.time).replace(/-/g, '/')).getTime();
  // 「已提现」是结算完成后的后续动作，始终视为未开始的最后一步。
  const followUpStepId = 'withdrawn';
  const timeline = flow.map(step => {
    let state = 'todo';
    if (step.id === activeStep) {
      state = 'active';
    } else if (step.id !== followUpStepId && step.offset <= activeOffset) {
      state = 'done';
    }
    const offset = typeof step.offset === 'number' ? step.offset : 0;
    return {
      id: step.id,
      title: step.title,
      description: step.description,
      state,
      time: state === 'todo' ? '' : formatWithdrawStamp(base + offset * 1000)
    };
  });
  return Object.assign({}, record, { timeline });
}

const incomeRecordSeeds = {
  store: [
    {
      id: 'i-s-1',
      orderNo: 'IC202609180004',
      title: '抹茶芝士芭乐 x1',
      source: '订单核销 · 五一广场店',
      amount: '+¥18.90',
      status: 'pending',
      time: '2026-09-18 20:31:02',
      note: '订单已完成，收益待结算'
    },
    {
      id: 'i-s-2',
      orderNo: 'IC202609180003',
      title: '红苹果乌龙冰奶 x1',
      source: '订单核销 · 五一广场店',
      amount: '+¥14.90',
      status: 'pending',
      time: '2026-09-18 19:16:44',
      note: '订单已完成，收益待结算'
    },
    {
      id: 'i-s-3',
      orderNo: 'IC202609170002',
      title: '五窨茉莉抹茶 x2',
      source: '订单核销 · 五一广场店',
      amount: '+¥27.80',
      status: 'settled',
      time: '2026-09-17 15:08:20',
      note: '已结算，转入可结算余额'
    },
    {
      id: 'i-s-4',
      orderNo: 'IC202609160001',
      title: '抹茶芝士芭乐 x1',
      source: '订单核销 · 五一广场店',
      amount: '-¥18.90',
      status: 'reversed',
      time: '2026-09-16 11:42:36',
      note: '订单退款，收益同步冲正',
      failReason: '订单已全额退款，对应分账收益同步冲正'
    }
  ],
  investor: [
    {
      id: 'i-i-1',
      orderNo: 'IC202608310002',
      title: '长沙五一广场平和堂店',
      source: '2026-08 月度分佣',
      amount: '¥1,806.00',
      status: 'settled',
      time: '2026-08-31 23:59:59',
      note: '月度分佣已结算'
    },
    {
      id: 'i-i-2',
      orderNo: 'IC202608310001',
      title: '长沙五一广场平和堂店',
      source: '2026-08 月度分佣（待结算部分）',
      amount: '¥612.00',
      status: 'pending',
      time: '2026-08-31 23:59:58',
      note: '待结算，T+1 转为可结算'
    },
    {
      id: 'i-i-3',
      orderNo: 'IC202607310001',
      title: '长沙五一广场平和堂店',
      source: '2026-07 月度分佣',
      amount: '¥1,740.00',
      status: 'settled',
      time: '2026-07-31 23:59:59',
      note: '月度分佣已结算'
    },
    {
      id: 'i-i-4',
      orderNo: 'IC202607150003',
      title: '长沙五一广场平和堂店',
      source: '2026-07 订单退款',
      amount: '-¥86.00',
      status: 'reversed',
      time: '2026-07-15 16:20:11',
      note: '订单退款，分佣同步冲正',
      failReason: '该笔订单发生退款，对应分佣已冲正'
    }
  ],
  resource: [
    {
      id: 'i-r-1',
      orderNo: 'IC202609210002',
      title: '五一广场店',
      source: '门店订单提成',
      amount: '+¥4.20',
      status: 'pending',
      time: '2026-09-21 08:19:30',
      note: '订单已完成，提成待结算'
    },
    {
      id: 'i-r-2',
      orderNo: 'IC202609210001',
      title: '岳麓店',
      source: '门店订单提成',
      amount: '+¥3.60',
      status: 'pending',
      time: '2026-09-21 07:40:12',
      note: '订单已完成，提成待结算'
    },
    {
      id: 'i-r-3',
      orderNo: 'IC202609200004',
      title: '五一广场店',
      source: '门店订单提成',
      amount: '+¥6.30',
      status: 'settled',
      time: '2026-09-20 15:08:44',
      note: '已结算，转入可结算余额'
    },
    {
      id: 'i-r-4',
      orderNo: 'IC202609190001',
      title: '岳麓店',
      source: '门店订单退款',
      amount: '-¥3.60',
      status: 'reversed',
      time: '2026-09-19 13:26:05',
      note: '订单退款，提成同步冲正',
      failReason: '该笔订单发生退款，对应提成已冲正'
    }
  ]
};

const incomeRecords = {
  store: incomeRecordSeeds.store.map(item => buildIncomeTimeline(item)),
  investor: incomeRecordSeeds.investor.map(item => buildIncomeTimeline(item)),
  resource: incomeRecordSeeds.resource.map(item => buildIncomeTimeline(item))
};

// 资源方提成订单：按绑定门店归属，结算状态与收益模块保持同一套语义。
const resourceOrderSeeds = [
  {
    id: 'ro-1',
    orderNo: 'DO20260921008812',
    storeId: 'store-001',
    storeName: '星沙乐运魔方店',
    title: '抹茶芝士芭乐',
    spec: '中杯,少冰,加马蹄粉圆',
    quantity: 1,
    orderAmount: '¥18.90',
    amount: '+¥4.20',
    status: 'pending',
    time: '2026-09-21 20:11:36',
    note: '订单已完成，提成待结算'
  },
  {
    id: 'ro-2',
    orderNo: 'DO20260921008765',
    storeId: 'store-001',
    storeName: '星沙乐运魔方店',
    title: '五窨茉莉抹茶',
    spec: '大杯,标准冰',
    quantity: 2,
    orderAmount: '¥33.80',
    amount: '+¥6.30',
    status: 'pending',
    time: '2026-09-21 15:08:20',
    note: '订单已完成，提成待结算'
  },
  {
    id: 'ro-3',
    orderNo: 'DO20260920007641',
    storeId: 'store-001',
    storeName: '星沙乐运魔方店',
    title: '陈皮普洱轻乳茶',
    spec: '中杯,热饮',
    quantity: 1,
    orderAmount: '¥14.90',
    amount: '+¥3.40',
    status: 'settled',
    time: '2026-09-20 19:42:05',
    note: '已结算，转入可结算余额'
  },
  {
    id: 'ro-4',
    orderNo: 'DO20260920006890',
    storeId: 'store-001',
    storeName: '星沙乐运魔方店',
    title: '红苹果乌龙冰奶',
    spec: '中杯,标准冰',
    quantity: 1,
    orderAmount: '¥14.90',
    amount: '-¥3.60',
    status: 'reversed',
    time: '2026-09-20 14:26:11',
    note: '订单退款，提成同步冲正',
    failReason: '该笔订单发生退款，对应提成已冲正'
  },
  {
    id: 'ro-5',
    orderNo: 'DO20260919005523',
    storeId: 'store-002',
    storeName: '松雅湖吾悦广场店',
    title: '红苹果乌龙冰奶',
    spec: '中杯,少冰',
    quantity: 1,
    orderAmount: '¥14.90',
    amount: '+¥3.60',
    status: 'pending',
    time: '2026-09-21 17:40:52',
    note: '订单已完成，提成待结算'
  },
  {
    id: 'ro-6',
    orderNo: 'DO20260919004788',
    storeId: 'store-002',
    storeName: '松雅湖吾悦广场店',
    title: '青提茉莉冰茶',
    spec: '大杯,标准冰',
    quantity: 2,
    orderAmount: '¥37.80',
    amount: '+¥7.10',
    status: 'settled',
    time: '2026-09-19 13:05:44',
    note: '已结算，转入可结算余额'
  }
];

function buildResourceOrderTimeline(record) {
  return buildIncomeTimeline(record).timeline;
}

const resourceOrders = resourceOrderSeeds.map(item =>
  Object.assign({}, item, { timeline: buildResourceOrderTimeline(item) })
);

const incomeData = {
  store: {
    title: '门店收益',
    today: '¥328.60',
    month: '¥8,412.00',
    total: '¥86,420.00',
    pending: '¥328.60',
    settled: '¥8,083.40',
    metricLabel: '今日收益',
    trend: [
      { id: 't1', label: '09-18', value: '¥328.60' },
      { id: 't2', label: '09-17', value: '¥402.10' },
      { id: 't3', label: '09-16', value: '¥286.40' },
      { id: 't4', label: '09-15', value: '¥451.00' }
    ],
    records: incomeRecords.store
  },
  investor: {
    title: '投资分佣',
    month: '¥2,418.00',
    total: '¥18,206.00',
    pending: '¥612.00',
    settled: '¥1,806.00',
    metricLabel: '本月分佣',
    trend: [
      { id: 't1', label: '08月', value: '¥1,806.00' },
      { id: 't2', label: '07月', value: '¥1,740.00' }
    ],
    records: incomeRecords.investor
  },
  resource: {
    title: '资源方提成',
    today: '¥86.40',
    total: '¥3,420.50',
    pending: '¥86.40',
    settled: '¥3,334.10',
    metricLabel: '今日提成',
    trend: [
      { id: 't1', label: '今天', value: '¥86.40' },
      { id: 't2', label: '昨天', value: '¥92.10' }
    ],
    records: incomeRecords.resource
  }
};

// 提现流转链路：按 status 推导节点状态，已完成节点带时间，未开始节点为 todo。
const WITHDRAW_FLOW = {
  pending: [
    { id: 'submitted', title: '已提交', description: '提现申请已提交，等待系统受理', offset: 0 },
    { id: 'auditing', title: '审核中', description: '后台审核中，请耐心等待', offset: 420 },
    { id: 'approved', title: '审核通过', description: '审核通过后进入出款流程' },
    { id: 'arrived', title: '已到账', description: '款项将打入指定收款账户' }
  ],
  processing: [
    { id: 'submitted', title: '已提交', description: '提现申请已提交，等待系统受理', offset: 0 },
    { id: 'approved', title: '审核通过', description: '审核通过，进入出款流程', offset: 300 },
    { id: 'paying', title: '出款中', description: '小额即时出款，预计 2 小时内到账', offset: 840 },
    { id: 'arrived', title: '已到账', description: '款项将打入指定收款账户' }
  ],
  success: [
    { id: 'submitted', title: '已提交', description: '提现申请已提交，等待系统受理', offset: 0 },
    { id: 'approved', title: '审核通过', description: '审核通过，进入出款流程', offset: 660 },
    { id: 'paying', title: '出款中', description: '系统已发起出款', offset: 1500 },
    { id: 'arrived', title: '已到账', description: '款项已成功打入指定收款账户', offset: 2280 }
  ],
  failed: [
    { id: 'submitted', title: '已提交', description: '提现申请已提交，等待系统受理', offset: 0 },
    { id: 'auditing', title: '审核中', description: '后台审核中', offset: 380 },
    { id: 'rejected', title: '已驳回', description: '审核未通过，金额已解冻', offset: 1560 }
  ]
};

const WITHDRAW_ACTIVE_STEP = {
  pending: 'auditing',
  processing: 'paying',
  success: 'arrived',
  failed: 'rejected'
};

function buildWithdrawTimeline(record) {
  const flow = WITHDRAW_FLOW[record.status] || WITHDRAW_FLOW.processing;
  const activeStep = WITHDRAW_ACTIVE_STEP[record.status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = new Date(String(record.time).replace(/-/g, '/')).getTime();
  const isTerminal = record.status === 'success' || record.status === 'failed';
  const timeline = flow.map(step => {
    let state = 'todo';
    if (isTerminal) {
      state = 'done';
    } else if (step.id === activeStep) {
      state = 'active';
    } else if (step.offset < activeOffset) {
      state = 'done';
    }
    return {
      id: step.id,
      title: step.title,
      description: step.description,
      state,
      time: state === 'todo' ? '' : formatWithdrawStamp(base + step.offset * 1000)
    };
  });
  return Object.assign({}, record, { timeline });
}

function formatWithdrawStamp(timestamp) {
  const date = new Date(timestamp);
  const pad = value => String(value).padStart(2, '0');
  return (
    date.getFullYear() +
    '-' +
    pad(date.getMonth() + 1) +
    '-' +
    pad(date.getDate()) +
    ' ' +
    pad(date.getHours()) +
    ':' +
    pad(date.getMinutes()) +
    ':' +
    pad(date.getSeconds())
  );
}

const withdrawRecordSeeds = {
  store: [
    {
      id: 'w-s-1',
      orderNo: 'WD202609200003',
      amount: '¥500.00',
      feeText: '¥0.00',
      arrivalText: '¥500.00',
      channel: '微信零钱',
      status: 'pending',
      time: '2026-09-20 15:42:08',
      note: '已提交，等待后台审核'
    },
    {
      id: 'w-s-2',
      orderNo: 'WD202609190002',
      amount: '¥88.00',
      feeText: '¥0.00',
      arrivalText: '¥88.00',
      channel: '微信零钱',
      status: 'processing',
      time: '2026-09-19 11:08:36',
      note: '小额即时出款，预计 2 小时内到账'
    },
    {
      id: 'w-s-3',
      orderNo: 'WD202609120001',
      amount: '¥1,200.00',
      feeText: '¥6.00',
      arrivalText: '¥1,194.00',
      channel: '微信零钱',
      status: 'success',
      time: '2026-09-12 09:24:15',
      note: '审核通过，已到账微信零钱'
    },
    {
      id: 'w-s-4',
      orderNo: 'WD202609050001',
      amount: '¥2,000.00',
      feeText: '¥0.00',
      arrivalText: '¥0.00',
      channel: '银行卡（尾号 8821）',
      status: 'failed',
      time: '2026-09-05 20:16:47',
      note: '收款账户信息有误，金额已解冻',
      failReason: '收款账户信息有误，请核对后重新提交'
    }
  ],
  investor: [
    {
      id: 'w-i-1',
      orderNo: 'WD202609210001',
      amount: '¥612.00',
      feeText: '¥0.00',
      arrivalText: '¥612.00',
      channel: '微信零钱',
      status: 'pending',
      time: '2026-09-21 10:05:22',
      note: '已提交，等待后台审核'
    },
    {
      id: 'w-i-2',
      orderNo: 'WD202609150002',
      amount: '¥1,806.00',
      feeText: '¥9.03',
      arrivalText: '¥1,796.97',
      channel: '银行卡（尾号 6210）',
      status: 'success',
      time: '2026-09-15 14:32:51',
      note: '审核通过，已到账银行卡'
    },
    {
      id: 'w-i-3',
      orderNo: 'WD202609080001',
      amount: '¥96.00',
      feeText: '¥0.00',
      arrivalText: '¥96.00',
      channel: '微信零钱',
      status: 'processing',
      time: '2026-09-08 19:47:03',
      note: '小额即时出款，预计 2 小时内到账'
    }
  ],
  resource: [
    {
      id: 'w-r-1',
      orderNo: 'WD202609210002',
      amount: '¥86.40',
      feeText: '¥0.00',
      arrivalText: '¥86.40',
      channel: '微信零钱',
      status: 'processing',
      time: '2026-09-21 08:19:30',
      note: '小额即时出款，预计 2 小时内到账'
    },
    {
      id: 'w-r-2',
      orderNo: 'WD202609140001',
      amount: '¥3,400.00',
      feeText: '¥17.00',
      arrivalText: '¥3,383.00',
      channel: '微信零钱',
      status: 'success',
      time: '2026-09-14 16:58:12',
      note: '审核通过，已到账微信零钱'
    }
  ]
};

const withdrawRecords = {
  store: withdrawRecordSeeds.store.map(item => buildWithdrawTimeline(item)),
  investor: withdrawRecordSeeds.investor.map(item => buildWithdrawTimeline(item)),
  resource: withdrawRecordSeeds.resource.map(item => buildWithdrawTimeline(item))
};

const withdrawData = {
  store: { balance: '¥1,206.00', pending: '¥328.60', deposit: '¥5,000.00', records: withdrawRecords.store },
  investor: { balance: '¥1,806.00', pending: '¥612.00', deposit: '¥20,000.00', records: withdrawRecords.investor },
  resource: { balance: '¥3,420.50', pending: '¥86.40', deposit: '¥0.00', records: withdrawRecords.resource }
};

module.exports = {
  roleDefinitions,
  roleDashboardData,
  verifyPool,
  verifiedRecords,
  incomeData,
  resourceOrders,
  withdrawRule,
  withdrawData
};
