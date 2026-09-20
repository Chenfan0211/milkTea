import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { SetupStoreId } from '@/enum';

export type RoleType = 'store' | 'investor' | 'channel';

export interface UserRow {
  id: number;
  userId: string;
  nickName: string;
  openId: string;
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
  type: 'store' | 'channel' | 'investor' | 'supplier' | 'platform';
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

function seed(): AdminData {
  const storeNames = ['五一广场店', '岳麓店', '平和堂店', '梅溪湖店'];
  const channelNames = ['渠道方 A', '渠道方 B', '渠道方 C'];
  const investorNames = ['投资人甲', '投资人乙', '投资人丙'];
  const supplierNames = ['供应商一', '供应商二', '供应商三'];

  const subjects: any[] = [
    { id: 40, code: 'PT-1000', name: '五零时光运营平台', type: 'platform', status: 'active', createTime: now() },
    ...storeNames.map((name, i) => ({
      id: i + 1,
      code: `ST-${1000 + i}`,
      name,
      type: 'store',
      status: 'open',
      createTime: now(),
      city: '长沙',
      manager: `店长${i + 1}`,
      location: '长沙市',
      investorName: i % 2 === 0 ? `投资人${i + 1}` : '未绑定'
    })),
    ...channelNames.map((name, i) => ({
      id: 10 + i,
      code: `CH-${1000 + i}`,
      name,
      type: 'channel',
      status: 'active',
      createTime: now(),
      channelCode: `QJ${10000 + i}`,
      boundUserCount: 20 + i * 3,
      bindStatus: 'bound'
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
      signStatus: 'signed'
    })),
    ...supplierNames.map((name, i) => ({
      id: 30 + i,
      code: `SU-${1000 + i}`,
      name,
      type: 'supplier',
      status: 'active',
      createTime: now(),
      productCount: 5 + i * 2
    }))
  ];

  const users: any[] = Array.from({ length: 8 }, (_, i) => ({
    id: i + 1,
    userId: `U${1000 + i}`,
    nickName: `微信用户${i + 1}`,
    openId: `openid_${1000 + i}`,
    businessRole: null,
    boundSubjectId: null,
    boundSubjectName: null
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
      roleType: 'channel',
      status: 'pending',
      applyTime: now(),
      reviewTime: null,
      reviewer: null,
      subjectId: 'CH-1001',
      subjectName: '渠道方 B',
      promoteChannel: '小红书',
      expectFans: '5000'
    }
  ];

  const orders: any[] = Array.from({ length: 15 }, (_, i) => ({
    id: i + 1,
    orderNo: `O${202609180000 + i}`,
    store: '五一广场店',
    user: `U${1000 + i}`,
    summary: '抹茶芝士芭乐 x1',
    paidAmount: 1890 + i * 100,
    status: ['CREATED', 'PAID', 'VERIFIED', 'COMPLETED', 'REFUNDED'][i % 5],
    payStatus: i % 5 >= 1 ? 'PAID' : 'UNPAID',
    pickupCode: String(400 + i).padStart(4, '0'),
    createTime: now()
  }));

  const refunds: any[] = Array.from({ length: 6 }, (_, i) => ({
    id: i + 1,
    refundNo: `R${100000 + i}`,
    orderNo: `O${202609180000 + i}`,
    amount: 1890 + i * 100,
    status: ['PENDING', 'APPROVED', 'SUCCESS', 'REJECTED'][i % 4],
    applyTime: now()
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
    products: Array.from({ length: 6 }, (_, i) => ({
      id: i + 1,
      code: `P-${1000 + i}`,
      name: ['抹茶芝士芭乐', '红苹果乌龙冰奶', '五窨茉莉抹茶', '芝士奶盖'][i % 4],
      category: '鲜奶茶',
      specCount: 3 + i,
      price: 1890 + i * 100,
      store: '五一广场店',
      onSale: i % 3 === 0 ? 'off' : 'on',
      splitReady: i % 4 === 0 ? 'incomplete' : 'ready'
    })),
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
      type: '订单',
      result: i % 4 === 0 ? 'rejected' : 'success',
      time: now()
    })),
    snapshots: Array.from({ length: 6 }, (_, i) => ({
      id: i + 1,
      snapshotNo: `SN${100000 + i}`,
      orderNo: `O${202609180000 + i}`,
      platformAmount: 189,
      storeAmount: 945,
      channelAmount: 284,
      investorAmount: 284,
      supplierAmount: 188,
      totalCheck: '一致',
      status: i % 3 === 0 ? 'invalid' : 'valid',
      createTime: now()
    })),
    executions: Array.from({ length: 5 }, (_, i) => ({
      id: i + 1,
      executeNo: `EX${100000 + i}`,
      snapshotNo: `SN${100000 + i}`,
      thirdRequestNo: `REQ${100000 + i}`,
      status: ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED'][i % 5],
      executeTime: now()
    })),
    ledgers: Array.from({ length: 8 }, (_, i) => ({
      id: i + 1,
      subject: ['五一广场店', '渠道方', '投资人', '供应商'][i % 4],
      role: ['门店', '渠道', '投资人', '供应商'][i % 4],
      orderNo: `O${202609180000 + i}`,
      amount: 1890 + i * 100,
      status: ['PENDING', 'SETTLEABLE', 'FROZEN', 'SETTLED'][i % 4],
      carryTime: now(),
      flowNo: `FL${100000 + i}`
    })),
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
        defaultStatus: '关闭',
        currentStatus: '关闭',
        openCondition: '优惠券活动、锁券、核销和优惠承担逻辑完成'
      },
      {
        id: 2,
        code: 'ENABLE_STORED_VALUE',
        name: '储值充值',
        defaultStatus: '关闭',
        currentStatus: '关闭',
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
        defaultStatus: '关闭',
        currentStatus: '关闭',
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
        roleType: 'channel',
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
        pickupCode: 'A026',
        orderNo: 'D00235803499139801088',
        product: '抹茶芝士芭乐（首创）',
        spec: '中杯,少冰,加马蹄粉圆',
        amount: 1890
      },
      {
        id: 2,
        pickupCode: 'B012',
        orderNo: 'D00235803499139801089',
        product: '红苹果乌龙冰奶',
        spec: '中杯,标准冰',
        amount: 1490
      },
      {
        id: 3,
        pickupCode: 'A015',
        orderNo: 'D00235803499139801090',
        product: '五窨茉莉抹茶',
        spec: '中杯,标准冰',
        amount: 1390
      }
    ],
    coupons: [
      {
        id: 1,
        title: '【VIP1】五零时光3元代金券（满20）',
        type: 'voucher',
        amount: 300,
        condition: '满20可用',
        expiryText: '2026-09-29 23:59 到期',
        channel: '不限制',
        status: 'enabled'
      },
      {
        id: 2,
        title: '新客立减5元',
        type: 'voucher',
        amount: 500,
        condition: '满30可用',
        expiryText: '2026-09-30 23:59 到期',
        channel: '小程序',
        status: 'enabled'
      }
    ],
    storedValuePackages: [
      {
        id: 1,
        amount: 100,
        coupons: [
          { amount: 2, quantity: 2, description: '储值赠送-2元代金券' },
          { amount: 5, quantity: 2, description: '储值赠送-5元代金券' }
        ]
      }
    ],
    giftCards: [
      { id: 1, name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg' },
      { id: 2, name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg' }
    ],
    giftCardDenominations: [
      { id: 1, faceValue: 100, salePrice: 100 },
      { id: 2, faceValue: 200, salePrice: 200 },
      { id: 3, faceValue: 500, salePrice: 500 }
    ],
    pointsProducts: [
      { id: 1, name: '宠物粮', category: '实物', points: 20 },
      { id: 2, name: '抹茶买一赠一券', category: '优惠券', points: 300 },
      { id: 3, name: '单品券', category: '优惠券', points: 300 }
    ],
    pointsEarningRules: [
      { id: 1, action: '每消费1元', reward: '+1币', note: '基础获取通道' },
      { id: 2, action: '每日签到', reward: '+1币', note: '连续签到7天额外+20币' },
      { id: 3, action: '邀请好友注册', reward: '+3币/人', note: '好友完成首单后到账' }
    ],
    memberLevels: [
      {
        id: 1,
        level: 'Lv1',
        name: '时光卡',
        amountTarget: 0,
        discount: '8折',
        benefits: ['基础折扣', '生日月双倍时光币', '专属会员价']
      },
      {
        id: 2,
        level: 'Lv2',
        name: '星享卡',
        amountTarget: 300,
        discount: '7折',
        benefits: ['基础折扣', '专属优惠券', '新品优先体验']
      },
      {
        id: 3,
        level: 'Lv3',
        name: '挚友卡',
        amountTarget: 2000,
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

function loadInitial(): AdminData {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object' && Array.isArray(parsed.subjects)) return parsed;
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
    if (v == null) return '—';
    if (typeof v !== 'object') return String(v);
    const keys = [
      'name',
      'code',
      'status',
      'onSale',
      'signStatus',
      'businessRole',
      'boundSubjectName',
      'currentStatus'
    ];
    const parts = keys.filter(k => v[k] != null).map(k => `${k}=${v[k]}`);
    return parts.length ? parts.join(' ') : JSON.stringify(v);
  }

  function audit(module: string, action: string, target: string, before: any, after: any) {
    const logs = data.value.auditLogs || [];
    logs.unshift({
      id: nextId(logs),
      time: now(),
      operator: 'admin',
      module,
      action,
      target,
      beforeValue: summarize(before),
      afterValue: summarize(after),
      reason: '',
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
    const list = ensure(key);
    const item = { id: nextId(list), createTime: now(), ...row };
    list.unshift(item);
    audit(module, '新增', item[labelKey] ?? item.id, null, item);
    persist();
    return item;
  }
  function update(key: string, id: number, patch: any, module: string, labelKey = 'name') {
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...patch };
    audit(module, '编辑', list[idx][labelKey] ?? id, before, list[idx]);
    persist();
  }
  function remove(key: string, id: number, module: string, labelKey = 'name') {
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const [removed] = list.splice(idx, 1);
    audit(module, '删除', removed[labelKey] ?? id, removed, null);
    persist();
  }
  function patch(key: string, id: number, patch: any, module: string, action: string, labelKey = 'name') {
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...patch };
    audit(module, action, list[idx][labelKey] ?? id, before, list[idx]);
    persist();
    return list[idx];
  }

  function listFiltered<T extends Record<string, any>>(
    list: T[],
    search: Record<string, any>,
    page: number,
    pageSize: number
  ) {
    let rows = [...list];
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
    const list = ensure('users');
    const idx = list.findIndex((u: any) => u.id === userId);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], businessRole: roleType, boundSubjectId: subjectId, boundSubjectName: subjectName };
    audit('用户管理', '绑定角色', list[idx].nickName, before, list[idx]);
    persist();
  }
  function unbindUserRole(userId: number) {
    const list = ensure('users');
    const idx = list.findIndex((u: any) => u.id === userId);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], businessRole: null, boundSubjectId: null, boundSubjectName: null };
    audit('用户管理', '解绑角色', list[idx].nickName, before, list[idx]);
    persist();
  }

  function reviewApplication(id: number, approve: boolean, subjectId: string | null, subjectName: string | null) {
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
    audit('申请审核', approve ? '通过' : '驳回', before.nickName, before, list[idx]);
    persist();
  }

  function toggleFeature(id: number, on: boolean) {
    patch('features', id, { currentStatus: on ? '开启' : '关闭' }, '系统', on ? '开启开关' : '关闭开关', 'name');
  }

  function refundOrder(id: number) {
    const list = ensure('orders');
    const idx = list.findIndex((o: any) => o.id === id);
    if (idx < 0) return;
    const order = list[idx];
    if (order.status === 'VERIFIED' || order.status === 'COMPLETED') {
      window.$message?.error('已核销/已结算订单不可退款');
      return;
    }
    const before = { ...order };
    order.status = 'REFUNDED';
    audit('交易中心', '整单退款', order.orderNo, before, order);
    persist();
  }

  function freezeLedger(id: number) {
    const list = ensure('ledgers');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status === 'FROZEN' || item.status === 'SETTLED') {
      window.$message?.warning('该笔资金当前状态不可冻结');
      return;
    }
    patch('ledgers', id, { status: 'FROZEN' }, '财务中心', '冻结', 'flowNo');
  }

  function unfreezeLedger(id: number) {
    const list = ensure('ledgers');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'FROZEN') {
      window.$message?.warning('仅冻结状态可解冻');
      return;
    }
    patch('ledgers', id, { status: 'SETTLEABLE' }, '财务中心', '解冻', 'flowNo');
  }

  function reviewWithdraw(id: number, approve: boolean) {
    const list = ensure('withdrawals');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'pending') {
      window.$message?.warning('该提现申请已处理，不能重复审核');
      return;
    }
    patch(
      'withdrawals',
      id,
      { status: approve ? 'approved' : 'rejected', reviewTime: now(), reviewer: 'admin' },
      '财务中心',
      approve ? '提现通过' : '提现驳回',
      'nickName'
    );
  }

  function executeVerify(id: number) {
    const pool = ensure('verifyPool');
    const idx = pool.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const [item] = pool.splice(idx, 1);
    const list = ensure('verifies');
    list.unshift({
      id: nextId(list),
      verifyCode: item.pickupCode,
      orderNo: item.orderNo,
      store: '五一广场店',
      operator: 'admin',
      device: '后台',
      type: '订单',
      result: 'success',
      time: now()
    });
    audit('交易中心', '执行核销', item.orderNo, item, item);
    persist();
  }

  function reviewComment(id: number, approve: boolean) {
    const list = ensure('comments');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const item = list[idx];
    if (item.status !== 'pending') {
      window.$message?.warning('该评论已审核，不能重复操作');
      return;
    }
    patch(
      'comments',
      id,
      { status: approve ? 'approved' : 'rejected' },
      '营销中心',
      approve ? '评论通过' : '评论驳回',
      'content'
    );
  }

  function refundAudit(id: number, approve: boolean) {
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
      'refundNo'
    );
  }

  function enableSplitRule(id: number) {
    const list = ensure('splitRules');
    const idx = list.findIndex((r: any) => r.id === id);
    if (idx < 0) return;
    const rule = list[idx];
    const sum = rule.platformRatio + rule.storeRatio + rule.channelRatio + rule.investorRatio + rule.supplierRatio;
    if (sum !== 10000) {
      window.$message?.error('五方比例合计必须等于 100%，无法启用');
      return;
    }
    patch('splitRules', id, { status: 'enabled' }, '商品中心', '启用规则', 'name');
  }

  return {
    data,
    subjects: computed(() => ensure('subjects')),
    users: computed(() => ensure('users')),
    roleApplications: computed(() => ensure('roleApplications')),
    roles: computed(() => ensure('roles')),
    grants: computed(() => ensure('grants')),
    products: computed(() => ensure('products')),
    specs: computed(() => ensure('specs')),
    splitRules: computed(() => ensure('splitRules')),
    orders: computed(() => ensure('orders')),
    payments: computed(() => ensure('payments')),
    refunds: computed(() => ensure('refunds')),
    verifies: computed(() => ensure('verifies')),
    snapshots: computed(() => ensure('snapshots')),
    executions: computed(() => ensure('executions')),
    ledgers: computed(() => ensure('ledgers')),
    reconciles: computed(() => ensure('reconciles')),
    features: computed(() => ensure('features')),
    auditLogs: computed(() => ensure('auditLogs')),
    withdrawals: computed(() => ensure('withdrawals')),
    verifyPool: computed(() => ensure('verifyPool')),
    coupons: computed(() => ensure('coupons')),
    storedValuePackages: computed(() => ensure('storedValuePackages')),
    giftCards: computed(() => ensure('giftCards')),
    giftCardDenominations: computed(() => ensure('giftCardDenominations')),
    pointsProducts: computed(() => ensure('pointsProducts')),
    pointsEarningRules: computed(() => ensure('pointsEarningRules')),
    memberLevels: computed(() => ensure('memberLevels')),
    comments: computed(() => ensure('comments')),
    add,
    update,
    remove,
    patch,
    listFiltered,
    bindUserRole,
    unbindUserRole,
    reviewApplication,
    toggleFeature,
    refundOrder,
    enableSplitRule,
    freezeLedger,
    unfreezeLedger,
    refundAudit,
    reviewWithdraw,
    executeVerify,
    reviewComment
  };
});
