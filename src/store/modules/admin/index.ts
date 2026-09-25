import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { SetupStoreId } from '@/enum';
import {
  crudCreate,
  crudDelete,
  crudUpdate,
  crudPage,
  // 业务动作接口（审核 / 核销 / 退款 / 资金）
  executeVerifyApi,
  freezeAccount as freezeAccountApi,
  refundOrderApi,
  reviewComment as reviewCommentApi,
  adminApplyWithdraw as adminApplyWithdrawApi,
  bindSubjectUser as bindSubjectUserApi,
  bindChannelStore as bindChannelStoreApi,
  unbindChannelStore as unbindChannelStoreApi,
  bindUserRole as bindUserRoleApi,
  reviewRoleApplication as reviewApplicationApi,
  unbindSubjectUser as unbindSubjectUserApi,
  unbindUserRole as unbindUserRoleApi,
  reviewWithdraw as reviewWithdrawApi,
  saveReferralConfigApi,
  fetchReferralConfig,
  saveSigninRule as saveSigninRuleApi,
  unfreezeAccount as unfreezeAccountApi
} from '@/service/api/crud';
import {
  bindStoreInvestor as bindStoreInvestorApi,
  createSubjectStore,
  fetchSubjectStores,
  unbindStoreInvestor as unbindStoreInvestorApi,
  updateSubjectStore
} from '@/service/api/subject';
import {
  createProduct,
  deleteProduct,
  fetchProductsPage,
  fetchSpecGroups,
  saveProductStores,
  saveSpecGroups,
  updateProduct,
  updateProductOnSale
} from '@/service/api/admin-product';
import { fetchAdminOrders, fetchAdminOrderDetail } from '@/service/api/trade';
import { fetchAdminRoleGrants } from '@/service/api/auth_admin';
import { fetchAdminSnapshots } from '@/service/api/finance';

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
  comments: 'comments',
  // 小程序运营配置（app_config）：首页入口 / 活动文案 / 客服信息与常见问题 /
  // 提现与结算说明 / 城市列表等，由后台维护、小程序只读。
  appConfig: 'appConfig'
};

/** 是否使用远端接口读取配置类数据（由页面通过 loadRemote 触发） */
const remoteLoaded = ref<Record<string, boolean>>({});

/**
 * 远端资源的总条数（分页用）。
 *
 * 为什么需要它：loadRemote 只把「第 1 页（size=200）」灌进本地镜像，
 * 若丢掉接口返回的 total，页面就只能拿镜像长度当总数 —— 数据超过 200 条时，
 * 分页器会永远认为自己只有 200 条，从而翻不到后面的页。
 */
const remoteTotals = ref<Record<string, number>>({});

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
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * 本地种子数据（已停用）。
 *
 * <p><b>为什么返回空结构而不是假数据</b>：
 * 运营后台所有列表/详情页均已接入后端接口（loadRemote / queryRemote / 专用 API）。
 * 保留 seed 假数据会造成两类问题：
 * <ol>
 *   <li>页面忘记加载接口时，界面会安静地展示假数据，问题难以察觉；</li>
 *   <li>详情页刷新/直接打开 URL 时读到的是 localStorage 缓存的假数据，与数据库不符。</li>
 * </ol>
 * 因此这里统一返回空数组：任一页面若漏接接口，会直接显示「暂无数据」而暴露问题。
 *
 * <p>历史实现（含各资源的演示数据）已由 git 保存，如需临时对照可查看
 * 本文件在移除前的版本。
 *
 * <p>仅保留少量「非列表型」默认值（签到/邀请/推荐配置），
 * 它们会在页面挂载时被对应接口覆盖，作为接口不可用时的兜底展示结构。
 */
function seed(): AdminData {
  return {
    subjects: [],
    users: [],
    roleApplications: [],
    roles: [],
    grants: [],
    products: [],
    specs: [],
    splitRules: [],
    orders: [],
    payments: [],
    refunds: [],
    verifies: [],
    snapshots: [],
    fundPool: [],
    subjectAccounts: [],
    fundFlows: [],
    reconciles: [],
    features: [],
    withdrawals: [],
    verifyPool: [],
    coupons: [],
    storedValuePackages: [],
    giftCards: [],
    giftCardDenominations: [],
    giftCardOrders: [],
    dictEntries: [],
    productCategories: [],
    provinces: [],
    cities: [],
    storeTypes: [],
    pointsProducts: [],
    pointsEarningRules: [],
    signInDaily: 1 as any,
    signInRewards: [{ days: 7, amount: 20 }] as any,
    referralConfig: {} as any,
    exchangeRecords: [],
    memberLevels: [],
    comments: [],
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
    if (typeof product.originalPrice === 'number' && product.originalPrice > 100)
      product.originalPrice = product.originalPrice / 100;
    if (product.costPrice == null) product.costPrice = Math.round((product.price || 0) * 0.5 * 10) / 10;
    if (!Array.isArray(product.tags)) product.tags = [];
    if (product.storedValuePrice == null) product.storedValuePrice = product.price;
    if (product.platformCommission == null)
      product.platformCommission = Math.round((product.costPrice || 0) * 0.1 * 10) / 10;
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
  // 注意：以下三张表**不做** defaults 回填。
  // 早期实现会在「接口返回空数组」时塞回前端假数据，导致页面显示与数据库不一致却难以察觉
  // （症状：库里明明是空，页面却有条目）。现统一遵循「接口返回什么就是什么」。
  if (!Array.isArray(data.dictEntries)) data.dictEntries = [];
  if (!Array.isArray(data.fundPool)) data.fundPool = [];
  if (!Array.isArray(data.subjectAccounts)) data.subjectAccounts = [];
  if (!Array.isArray(data.fundFlows)) data.fundFlows = [];
  if (!Array.isArray(data.exchangeRecords)) data.exchangeRecords = [];
  if (!Array.isArray(data.giftCardOrders)) data.giftCardOrders = [];
  if (!Array.isArray(data.provinces)) data.provinces = [];
  if (!Array.isArray(data.cities)) data.cities = [];
  // 分享规则同理：不再塞硬编码默认值，缺省即为空对象（由页面自行处理）
  if (!(data as any).referralConfig) (data as any).referralConfig = {};
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

    if (
      before &&
      after &&
      typeof before === 'object' &&
      typeof after === 'object' &&
      !Array.isArray(before) &&
      !Array.isArray(after)
    ) {
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

  /**
   * 写操作前的字段归一化（按资源类型）。
   *
   * <p>subjects：前端历史上用 `type`（小写，如 store/channel），
   * 而数据库列为 `subject_type`，值为大写（STORE/CHANNEL...）。
   * 若不转换，写库会因字段名不匹配被 CrudRegistry 白名单丢弃，
   * 或因值大小写不符导致数据不一致。
   */
  /** 后端大写角色枚举 -> 前端小写 RoleType（资源方 = 渠道 CHANNEL）。 */
  /** 审计日志模块码 -> 中文（与页面 moduleLabels 保持一致，集中在此便于复用） */
  const AUDIT_MODULE_LABELS: Record<string, string> = {
    AUTH: '鉴权',
    PAY: '支付',
    WITHDRAW: '提现',
    SUBJECTS: '主体管理',
    USERS: '用户管理',
    USER: '用户管理',
    PRODUCT: '商品中心',
    PRODUCTS: '商品中心',
    TRADE: '交易中心',
    ORDER: '订单管理',
    MARKETING: '营销中心',
    FINANCE: '财务中心',
    SYSTEM: '系统配置',
    AUDIT: '审计日志'
  };

  /** 审计日志动作码 -> 中文 */
  const AUDIT_ACTION_LABELS: Record<string, string> = {
    LOGIN: '登录',
    LOGIN_FAIL: '登录失败',
    LOGIN_FAIL_LOCKED: '登录失败并锁定',
    LOGIN_BLOCKED: '登录拦截',
    CALLBACK: '支付回调',
    CALLBACK_REJECT: '回调拒绝',
    ADMIN_APPLY: '后台代发起提现',
    APPROVE: '审核通过',
    REJECT: '审核驳回',
    MARK_FAILED: '标记出款失败',
    ADD: '新增',
    CREATE: '新增',
    EDIT: '编辑',
    UPDATE: '编辑',
    DELETE: '删除',
    REMOVE: '删除',
    BIND: '绑定',
    UNBIND: '解绑',
    ENABLE: '启用',
    DISABLE: '停用',
    FREEZE: '冻结',
    UNFREEZE: '解冻',
    VERIFY: '核销',
    REFUND: '退款'
  };

  const ROLE_CODE_TO_TYPE: Record<string, string> = {
    STORE: 'store',
    INVESTOR: 'investor',
    CHANNEL: 'resource',
    RESOURCE: 'resource'
  };

  /**
   * 读操作后的字段归一化（按资源类型）。
   *
   * <p>subjects：后端返回 `subjectType`（大写值，如 STORE），
   * 而页面历史代码按 `type`（小写）过滤与展示。若不归一化，
   * 列表与内嵌下拉都会恒为空。这里做双写，保持对既有页面的兼容。
   *
   * <p>users：`app_user` 只存 `bound_subject_id`（数字）与 `business_role`（大写），
   * 而页面按 `boundSubjectName` / 小写 `businessRole` 展示。后端不会返回 boundSubjectName，
   * 若不在此补齐，会同时出现两个现象：
   * 「绑定主体」列恒为 —（数据已落库，只是没解析成主体名）；
   * 「经营角色」列原样显示英文 STORE（大写不命中页面小写映射）。
   *
   * <p><b>调用前提</b>：`subjects` 镜像需已加载（boundSubjectId -> 主体名依赖它），
   * 故用户列表页须先 `loadRemote('subjects')` 再查询 users。
   */
  function normalizeRemoteRow(key: string, rows: any[]) {
    if (!Array.isArray(rows)) return rows;

    /** 按主体 id 取名称（依赖 subjects 镜像；未加载时返回 null） */
    const subjectNameById = (id: any) => {
      if (id == null) return null;
      const hit = ensure('subjects').find((s: any) => Number(s.id) === Number(id));
      return hit ? hit.name : null;
    };
    /** 角色码统一转小写，命中页面中文映射（STORE -> store -> 门店） */
    const lowerRole = (v: any) => (v ? String(v).toLowerCase() : null);

    if (key === 'subjects') {
      return rows.map(r => ({
        ...r,
        subjectType: r.subjectType,
        type: String(r.subjectType || '').toLowerCase(),
        // boundUserId 存数字用户 id（app_user 主键即 id），统一为 number 便于严格比较
        boundUserId: r.boundUserId == null ? null : Number(r.boundUserId)
      }));
    }

    if (key === 'users') {
      return rows.map(r => {
        const boundId = r.boundSubjectId == null ? null : Number(r.boundSubjectId);
        const rawRole = r.businessRole == null ? '' : String(r.businessRole);
        return {
          ...r,
          boundSubjectId: boundId,
          // 主体名由前端按 id 解析（app_user 无 bound_subject_name 列）
          boundSubjectName: subjectNameById(boundId),
          // 大写 STORE -> 小写 store，命中页面 roleOptions 后显示「门店」
          businessRole: rawRole ? (ROLE_CODE_TO_TYPE[rawRole.toUpperCase()] ?? rawRole.toLowerCase()) : null
        };
      });
    }

    // ---------- 财务 / 交易：补齐 subjectName、roleType 小写 ----------

    if (key === 'fundFlows') {
      return rows.map(r => ({
        ...r,
        // 表只存 subject_id，页面「经营方」列需要名称
        subjectName: r.subjectName ?? subjectNameById(r.subjectId) ?? '—',
        roleType: lowerRole(r.roleType),
        // 页面读 poolBalanceAfter，表列名为 balance_after
        poolBalanceAfter: r.poolBalanceAfter ?? r.balanceAfter
      }));
    }

    if (key === 'subjectAccounts' || key === 'fundPool') {
      return rows.map(r => ({
        ...r,
        subjectName: r.subjectName ?? subjectNameById(r.subjectId) ?? '—',
        roleType: lowerRole(r.roleType)
      }));
    }

    if (key === 'withdrawals') {
      return rows.map(r => ({
        ...r,
        // 页面「申请人」列读 nickName，表里只有 user_id / subject_id
        nickName: r.nickName ?? subjectNameById(r.subjectId) ?? '—',
        roleType: lowerRole(r.roleType)
      }));
    }

    // ---------- 核销：字段名对齐 + 结果码统一 + 门店名 ----------

    if (key === 'verifies' || key === 'verifyRecords' || key === 'verifyPool') {
      return rows.map(r => {
        const raw = r.result == null ? '' : String(r.result).toUpperCase();
        return {
          ...r,
          // 页面读 store / time，接口给的是 storeSubjectId / createTime
          store: r.store ?? r.storeName ?? subjectNameById(r.storeSubjectId) ?? '—',
          time: r.time ?? r.createTime,
          // 后端 SUCCESS/FAIL -> 页面映射用的 success/failed；rejected 保留（重复拦截）
          result: raw === 'SUCCESS' ? 'success' : raw === 'FAIL' ? 'failed' : r.result
        };
      });
    }

    // ---------- 角色申请：申请人字段对齐 + extra_form 平铺 ----------

    if (key === 'roleApplications') {
      return rows.map(r => {
        let extra: Record<string, any> = {};
        try {
          extra = r.extraForm ? (typeof r.extraForm === 'string' ? JSON.parse(r.extraForm) : r.extraForm) : {};
        } catch {
          extra = {};
        }
        return {
          ...r,
          // 页面读 nickName / name / phone，接口给的是 applicantName / applicantPhone
          nickName: r.nickName ?? r.applicantName ?? null,
          name: r.name ?? r.applicantName ?? null,
          phone: r.phone ?? r.applicantPhone ?? null,
          roleType: lowerRole(r.roleType),
          ...extra
        };
      });
    }

    // ---------- 角色授权记录（后台账号 ↔ 角色）----------

    if (key === 'grants') {
      // 口径已变更（2026-09-25）：/admin/auth/grants 现在返回 sys_user_role
      // （后台账号 ↔ 后台角色），不再是 user_role_grant（小程序用户的经营角色）。
      // 因此这里不再补 userName/subjectName 之类的展示名 —— 后端已直接返回
      // username / nickName / roleName / roleCode，原样展示即可。
      return rows;
    }

    // ---------- 审计日志：模块/动作中文化 ----------

    if (key === 'auditLogs') {
      return rows.map(r => ({
        ...r,
        module: AUDIT_MODULE_LABELS[String(r.module || '').toUpperCase()] ?? r.module,
        action: AUDIT_ACTION_LABELS[String(r.action || '').toUpperCase()] ?? r.action
      }));
    }

    return rows;
  }

  function normalizeWritePayload(key: string, payload: Record<string, any>) {
    if (!payload || typeof payload !== 'object') return payload;
    if (key !== 'subjects') return payload;

    const raw = payload.subjectType ?? payload.type;
    if (!raw) return payload;

    const { type: _ignoredType, subjectType: _ignoredSubjectType, ...rest } = payload;
    return { ...rest, subjectType: String(raw).toUpperCase() };
  }

  /** 资源 key -> 中文名，仅用于告警文案；未登记时回退为 key 本身 */
  const RESOURCE_LABELS: Record<string, string> = {
    productCategories: '分类管理',
    splitRules: '分账规则',
    features: '功能开关',
    subjects: '主体管理',
    users: '用户管理',
    roles: '角色管理',
    grants: '授权管理',
    dictEntries: '数据字典',
    cities: '城市管理',
    coupons: '优惠券',
    memberLevels: '会员等级',
    pointsProducts: '积分商品',
    pointsEarningRules: '积分规则',
    storedValuePackages: '储值套餐',
    giftCards: '礼品卡',
    appConfig: '运营配置'
  };

  function labelOf(key: string, fallback: string) {
    return RESOURCE_LABELS[key] ?? fallback ?? key;
  }

  /**
   * 写库后比对「实际落库结果」，发现被后端丢弃的字段时显式告警。
   *
   * <p><b>为什么需要它</b>：后端 CrudService 只会写入 CrudRegistry 白名单内的列，
   * 白名单外字段会被静默跳过，接口仍返回 200。
   * 历史上因此出现过「界面提示保存成功、库里其实没变」的假成功，
   * 且前端毫无感知（如分类的 tag/enabled、分账规则的 storePerItem）。
   *
   * <p>策略：把提交值与后端回读值逐一比对，仅对**明确规定过但未落库**的字段告警；
   * 不阻断流程（写入本身已成功，只是部分字段未生效），但让问题可见。
   *
   * @param submitted 前端提交的字段（驼峰）
   * @param persisted 后端返回的记录（驼峰）
   * @param label     用于提示的资源中文名
   */
  function warnDroppedFields(submitted: Record<string, any>, persisted: any, label: string) {
    if (!submitted || !persisted || typeof persisted !== 'object') return;
    const dropped: string[] = [];
    for (const [key, value] of Object.entries(submitted)) {
      // 只校验有明确提交值的字段；空值/未填不参与比对
      if (value === undefined || value === null || value === '') continue;
      if (!(key in persisted)) {
        // 字段在返回结果里完全不存在 -> 极可能是列名不匹配或未进白名单
        dropped.push(key);
        continue;
      }
      const persistedValue = persisted[key];
      // 数字/字符串宽松比对（后端 tinyint 返回数字、前端 select 传字符串）
      if (String(persistedValue) !== String(value)) {
        dropped.push(key);
      }
    }
    if (dropped.length) {
      window.$message?.warning(`${label}：字段 ${dropped.join('、')} 未写入数据库，请联系开发检查后端白名单配置`);
    }
  }

  async function fetchAdminStores(params: { current: number; size: number; search?: string; status?: string }) {
    const page = await fetchSubjectStores(params);
    return { data: page?.records || [], total: page?.total || 0 };
  }

  async function addAdminStore(payload: Record<string, any>) {
    await createSubjectStore(payload);
    await loadRemote('subjects');
  }

  async function updateAdminStore(id: number, payload: Record<string, any>) {
    await updateSubjectStore(id, payload);
    await loadRemote('subjects');
  }

  async function bindStoreInvestor(storeSubjectId: number, investorSubjectId: number) {
    await bindStoreInvestorApi(storeSubjectId, investorSubjectId);
    await loadRemote('subjects');
  }

  async function unbindStoreInvestor(storeSubjectId: number) {
    await unbindStoreInvestorApi(storeSubjectId);
    await loadRemote('subjects');
  }

  async function bindChannelStore(channelSubjectId: number, storeSubjectId: number) {
    await bindChannelStoreApi(channelSubjectId, storeSubjectId);
    await loadRemote('subjects');
  }

  async function unbindChannelStore(channelSubjectId: number, storeSubjectId: number) {
    await unbindChannelStoreApi(channelSubjectId, storeSubjectId);
    await loadRemote('subjects');
  }

  function ensure(key: string) {
    if (!Array.isArray(data.value[key])) data.value[key] = [];
    return data.value[key];
  }

  // generic CRUD
  /**
   * 新增。远端模式下**先写库、成功后才更新本地镜像**，失败抛出异常由调用方处理。
   *
   * 为什么不再"乐观 UI"：原实现在请求返回前就插入 id=Date.now() 的假数据并立即 return，
   * 接口失败时仅弹一个 toast，但页面已经渲染了这条不存在的数据，
   * 用户会误以为新增成功（与列表查询"失败不回退"的策略也不一致）。
   */
  async function add(key: string, row: any, module: string, labelKey = 'name') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      const payload = normalizeWritePayload(key, row);
      const created: any = await crudCreate(remote, payload);
      warnDroppedFields(payload, created, labelOf(key, module));
      const list = ensure(key);
      list.unshift(created);
      persist();
      return created;
    }
    const list = ensure(key);
    const item = { id: nextId(list), createTime: now(), ...row };
    list.unshift(item);
    audit(module, '新增', item[labelKey] ?? item.id, null, item);
    persist();
    return item;
  }
  async function update(key: string, id: number, updates: any, module: string, labelKey = 'name') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      // 先写库，成功后才更新本地镜像；失败抛出，由页面提示，不产生"假成功"
      const payload = normalizeWritePayload(key, updates);
      const updated: any = await crudUpdate(remote, id, payload);
      warnDroppedFields(payload, updated, labelOf(key, module));
      const cur = ensure(key);
      const ci = cur.findIndex((x: any) => x.id === id);
      if (ci >= 0) cur[ci] = { ...cur[ci], ...updated };
      persist();
      return updated;
    }
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...updates };
    audit(module, '编辑', list[idx][labelKey] ?? id, before, list[idx]);
    persist();
  }
  async function remove(key: string, id: number, module: string, labelKey = 'name', reason = '') {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      // 先删库，成功后才标记本地；失败抛出，页面不会显示"已删除"
      await crudDelete(remote, id);
      const list = ensure(key);
      const idx = list.findIndex((x: any) => x.id === id);
      if (idx >= 0) {
        list[idx] = { ...list[idx], deleted: true, deletedAt: now(), deleteReason: reason };
      }
      persist();
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
  /**
   * 局部更新。远端模式下先写库、成功后才改本地镜像，失败抛出。
   *
   * 与 add/update/remove 保持一致的「接口优先」策略，
   * 避免出现「界面已改、库里没改」却无感知的情况。
   */
  async function patch(
    key: string,
    id: number,
    updates: any,
    module: string,
    action: string,
    labelKey = 'name',
    reason = ''
  ) {
    const remote = REMOTE_RESOURCES[key];
    if (remote) {
      const payload = normalizeWritePayload(key, updates);
      const updated: any = await crudUpdate(remote, id, payload);
      warnDroppedFields(payload, updated, labelOf(key, module));
      const cur = ensure(key);
      const ci = cur.findIndex((x: any) => x.id === id);
      // 以「后端返回值」为准回填，避免本地自造字段与库中不一致
      if (ci >= 0) cur[ci] = { ...cur[ci], ...updated };
      persist();
      return cur[ci];
    }
    const list = ensure(key);
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx < 0) return;
    const before = { ...list[idx] };
    list[idx] = { ...list[idx], ...updates };
    audit(module, action, list[idx][labelKey] ?? id, before, list[idx], reason);
    persist();
    return list[idx];
  }

  /**
   * 保存签到规则（先写库、成功后再改本地；失败抛出）。
   *
   * 原实现先改本地 + 失败仅弹 toast，与 add/update 的「乐观 UI」是同一病症：
   * 界面显示已保存，实际库中未变。
   */
  async function saveSignInRule(payload: { daily: number; rewards: Array<{ days: number; amount: number }> }) {
    const first = payload.rewards && payload.rewards[0];
    // 先落库：失败直接抛出，不更新本地，避免「界面已改、库里没改」
    await saveSigninRuleApi({
      daily: payload.daily,
      streakDays: first?.days ?? 7,
      streakReward: first?.amount ?? 20,
      rewards: payload.rewards
    });
    const before = { daily: (data.value as any).signInDaily ?? 1, rewards: (data.value as any).signInRewards || [] };
    (data.value as any).signInDaily = payload.daily;
    (data.value as any).signInRewards = payload.rewards;
    audit('营销中心', '编辑签到规则', '签到规则', before, { daily: payload.daily, rewards: payload.rewards });
    persist();
  }
  /** 保存分享规则（先写库、成功后再改本地；失败抛出） */
  async function saveReferralConfig(payload: Record<string, any>) {
    await saveReferralConfigApi(payload);
    const before = { ...(data.value as any).referralConfig };
    (data.value as any).referralConfig = { ...(data.value as any).referralConfig, ...payload };
    audit('营销中心', '编辑分享规则', '分享有礼', before, (data.value as any).referralConfig);
    persist();
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
   * 归一化时需要 subjects 镜像做 id -> 名称解析的资源。
   *
   * <p>若不自动预加载，用户直接进入这些列表页（而非先经过主体管理）时，
   * boundSubjectName / subjectName / store 等字段会全部为空 —— 表现为
   * 「绑定主体」「经营方」「门店」列显示 —，而数据其实已在库中。
   */
  const NEEDS_SUBJECTS = new Set([
    'users',
    'fundFlows',
    'subjectAccounts',
    'fundPool',
    'withdrawals',
    'verifies',
    'verifyRecords',
    'verifyPool'
  ]);

  /** 确保 subjects 镜像已加载（仅首次真正请求，之后走内存） */
  async function ensureSubjectsLoaded() {
    if (remoteLoaded.value.subjects) return;
    const remote = REMOTE_RESOURCES.subjects;
    if (!remote) return;
    try {
      const page = await crudPage(remote, { current: 1, size: 200 });
      const list = ensure('subjects');
      list.splice(0, list.length, ...normalizeRemoteRow('subjects', page?.records || []));
      remoteLoaded.value.subjects = true;
      persist();
    } catch {
      // 静默失败：主体镜像仅为展示增强，不应阻断主列表加载
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
      if (NEEDS_SUBJECTS.has(key)) await ensureSubjectsLoaded();
      const page = await crudPage(remote, { current: 1, size: 200, ...params });
      const list = ensure(key);
      // 同样应用字段归一化：页面内嵌下拉（如 subjects 按 type 过滤）依赖它
      list.splice(0, list.length, ...normalizeRemoteRow(key, page?.records || []));
      // 保留后端真实总数，供分页使用（镜像只装了第 1 页）
      remoteTotals.value[key] = Number(page?.total ?? (page?.records || []).length);
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

  /**
   * 统一远端分页查询（列表页专用）。
   *
   * <p>返回结构与 {@link listFiltered} 保持一致，便于页面无痛替换：
   * {@code { data, total }}。
   *
   * <p><b>与 listFiltered 的区别</b>：这是真正的服务端分页查询，
   * 数据直接来自数据库，不再依赖 localStorage 中的本地镜像。
   *
   * <p><b>失败策略（重要）</b>：接口失败时抛出异常，<b>不回退到本地假数据</b>。
   * 目的是避免「界面显示的数据其实是旧假数据」这种误导性状态；
   * 页面应捕获并展示错误，而不是静默使用本地数据。
   */
  async function queryRemote(
    key: string,
    search: Record<string, any>,
    page: number,
    pageSize: number
  ): Promise<{ data: any[]; total: number }> {
    // 商品走 product-service 专用接口，见 loadProducts 的说明
    if (key === 'products') {
      const keyword = String((search && (search.name || search.code)) || '').trim();
      const res = await fetchProductsPage({ current: page, size: pageSize, search: keyword || undefined });
      return { data: (res as any)?.records || [], total: (res as any)?.total || 0 };
    }

    // 分账快照走 /admin/finance/snapshots：该接口额外聚合 order_item 得到
    // summary（商品信息）。若走通用 CRUD 单表查询，列表「商品信息」列恒为空。
    if (key === 'snapshots') {
      const res: any = await fetchAdminSnapshots({ current: page, size: pageSize });
      const records = res?.records ?? res?.data?.records ?? [];
      const total = res?.total ?? res?.data?.total ?? 0;
      return { data: normalizeRemoteRow('snapshots', records), total };
    }

    // 角色授权记录走 /admin/auth/grants：该接口 join 了 sys_user / sys_role，
    // 直接给出 username / nickName / roleName / roleCode，前端无需二次解析。
    // （原实现读的是 user_role_grant，2026-09-25 已切换为后台账号口径。）
    if (key === 'grants') {
      const res: any = await fetchAdminRoleGrants({ current: page, size: pageSize });
      const records = res?.records ?? res?.data?.records ?? [];
      const total = res?.total ?? res?.data?.total ?? 0;
      return { data: records, total };
    }

    const normalize = (rows: any[]) => normalizeRemoteRow(key, rows);

    const remote = REMOTE_RESOURCES[key];
    if (!remote) {
      throw new Error(`资源未接入后端：${key}`);
    }
    // 归一化需要 subjects 做 id -> 名称解析时，先确保镜像已加载
    if (NEEDS_SUBJECTS.has(key)) await ensureSubjectsLoaded();
    // 去除空搜索项，避免后端拼接无意义的 like 条件。
    //
    // 等值过滤约定：搜索项 key 以 eq_ 开头的，后端会按「等值过滤」处理
    // （CrudController 把 eq_ 前缀参数交给 filters，其余按 LIKE 模糊搜索），
    // 且受 CrudRegistry 的 filterable 白名单约束。
    // 页面写法：searchFields 的 key 直接写 eq_enabled，由此原样透传即可。
    // 之所以需要它：状态/枚举列用 LIKE 会误匹配（如 1 命中 10、11）且无法命中索引。
    const params: Record<string, any> = { current: page, size: pageSize };
    for (const [k, v] of Object.entries(search || {})) {
      const value = String(v ?? '').trim();
      if (value) params[k] = value;
    }
    const res = await crudPage(remote, params);
    return { data: normalize((res as any)?.records || []), total: (res as any)?.total || 0 };
  }

  /**
   * 加载商品列表（走 product-service 专用接口，非通用 CRUD）。
   *
   * 说明：商品接口路径为 /api/v1/admin/product/list，与通用 CRUD 的
   * /api/v1/admin/crud/{resource} 不同，因此单独适配。
   * 返回字段由后端组装（category 分类名、stores 门店数组、specCount、
   * costPrice / platformCommission，金额单位为「分」）。
   */
  async function loadProducts(params?: Record<string, any>) {
    try {
      const page = await fetchProductsPage({ current: 1, size: 200, ...params });
      const list = ensure('products');
      list.splice(0, list.length, ...((page?.records || []) as any[]));
      remoteLoaded.value.products = true;
      persist();
      return page;
    } catch (error: any) {
      window.$message?.error(error?.message || '加载商品失败');
      return null;
    }
  }

  /** 新增商品（写库后回填本地镜像） */
  /**
   * 加载订单列表（走 trade-service 专用接口 /api/v1/admin/trade/orders）。
   *
   * 与通用 CRUD 的区别：该接口返回完整 OrderDTO（含 items 商品明细、
   * summary、mealType、payStatus、payTime/verifyTime/completeTime 等），
   * 而通用 CRUD 只返回 orders 表裸列，缺商品明细与时间详情。
   * 同时把完整列表写入 orders 镜像，供详情页 store.orders.find 使用。
   */
  async function loadAdminOrders(params?: Record<string, any>) {
    const page = await fetchAdminOrders({ current: 1, size: 200, ...params });
    const list = ensure('orders');
    list.splice(0, list.length, ...((page?.records || []) as any[]));
    remoteLoaded.value.orders = true;
    persist();
    return { data: (page as any)?.records || [], total: (page as any)?.total || 0 };
  }

  /** 订单详情（含商品明细），走后端专用接口 */
  async function loadAdminOrderDetail(orderNo: string) {
    return fetchAdminOrderDetail(orderNo);
  }
  async function addProduct(payload: Record<string, any>) {
    const created: any = await createProduct(payload);
    const list = ensure('products');
    list.unshift(created);
    persist();
    return created;
  }

  /** 编辑商品 */
  async function editProduct(id: number, payload: Record<string, any>) {
    const updated: any = await updateProduct(id, payload);
    const list = ensure('products');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx >= 0) list[idx] = { ...list[idx], ...updated };
    persist();
    return updated;
  }

  /** 商品上下架：onSale = on / off */
  async function setProductOnSale(id: number, onSale: string) {
    const updated: any = await updateProductOnSale(id, onSale);
    const list = ensure('products');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx >= 0) list[idx] = { ...list[idx], ...updated };
    persist();
    return updated;
  }

  /** 读取商品规格组（product-service 专用接口） */
  async function loadSpecGroups(productId: number) {
    const res: any = await fetchSpecGroups(productId);
    return (res?.groups || []) as any[];
  }

  /** 保存商品规格组（落库 product_spec） */
  async function saveProductSpecGroups(productId: number, groups: any[]) {
    const res: any = await saveSpecGroups(productId, groups);
    await loadProducts();
    return (res?.groups || []) as any[];
  }

  /** 保存商品门店关联（落库 product_store） */
  async function saveProductStoreIds(productId: number, storeSubjectIds: number[]) {
    const ids = await saveProductStores(productId, storeSubjectIds);
    await loadProducts();
    return ids;
  }

  /** 删除商品（后端逻辑删除） */
  async function removeProduct(id: number) {
    await deleteProduct(id);
    const list = ensure('products');
    const idx = list.findIndex((x: any) => x.id === id);
    if (idx >= 0) list.splice(idx, 1);
    persist();
  }

  /** 该 key 是否已启用远端模式 */
  function isRemote(key: string) {
    return Boolean(REMOTE_RESOURCES[key]);
  }
  /**
   * 本地镜像分页（按 search 过滤后切片）。
   *
   * totalKey 用于取后端真实总数：镜像最多只装 200 条，若无脑用 rows.length，
   * 数据量超过 200 条时会被截断，分页器永远翻不到后面的页。
   * 未登记 totalKey（纯本地数据）时才退回用过滤后的行数。
   */
  function listFiltered<T extends Record<string, any>>(
    list: T[],
    search: Record<string, any>,
    page: number,
    pageSize: number,
    totalKey?: string
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
    // 有后端 total 时优先用它：镜像最多只装 200 条，直接取 rows.length 会在
    // 数据量超过 200 条时把总数截断，导致分页器翻不到后面的页。
    // 仅在「无过滤条件」时采用后端 total —— 一旦本地做了 search 过滤，
    // 后端 total 对应的是未过滤全集，继续沿用会与实际行数不符。
    const hasSearch = Object.keys(search || {}).some(key => String((search as any)[key] ?? '').trim());
    const remoteTotal = totalKey && !hasSearch ? remoteTotals.value[totalKey] : undefined;
    const total = remoteTotal != null && remoteTotal > rows.length ? remoteTotal : rows.length;
    const start = (page - 1) * pageSize;
    return { data: rows.slice(start, start + pageSize), total };
  }

  /**
   * 绑定用户与主体（走后端接口）。
   *
   * 原实现只改本地数组，刷新即丢；现调用
   * POST /api/v1/admin/subject/binding/user/{userId}/role/{roleCode}/subject/{subjectId}。
   * 注意后端 subjectId 需要**数字主体 id**，而本方法的 subjectId 参数是主体 code，
   * 故此处先按 code 查出实体再取其 id。
   */
  async function bindUserRole(userId: number, roleType: RoleType, subjectCode: string, subjectName: string) {
    const users = ensure('users');
    const subjects = ensure('subjects');
    const user = users.find((u: any) => Number(u.id) === Number(userId));
    if (!user) return;

    // 一个用户只能绑定一个主体（boundSubjectId 为数字 id，判空即可）
    if (user.boundSubjectId != null && user.boundSubjectId !== '') {
      window.$message?.warning('该用户已绑定主体，请先解绑');
      return;
    }

    const subject = subjects.find((s: any) => s.code === subjectCode);
    if (!subject) {
      window.$message?.warning('主体不存在');
      return;
    }

    // 主体未被其他用户绑定
    // app_user 主键即 id（接口无 userId 字段）；原写法 user.userId 恒为 undefined，
    // 会导致判断恒真、把「未绑定」误判为「已绑定」，绑定功能不可用
    if (subject.boundUserId && Number(subject.boundUserId) !== Number(user.id)) {
      window.$message?.warning('该经营者已绑定用户，请先解绑');
      return;
    }

    const roleCode = String(roleType || '').toUpperCase();
    await bindUserRoleApi(userId, roleCode, Number(subject.id));
    await loadRemote('users');
    await loadRemote('subjects');
    audit('用户管理', '绑定', user.nickName || String(userId), null, {
      businessRole: roleCode,
      boundSubjectId: subject.id,
      boundSubjectName: subject.name
    });
  }

  /**
   * 解绑用户与主体（走后端接口）。
   *
   * <p>后端按 (userId, roleCode, subjectId) 定位绑定记录，三者都需提供。
   *
   * <p><b>历史 Bug（本方法修复）</b>：原写法用
   * `subjects.find(s => s.code === user.boundSubjectId)` 定位主体，
   * 但 `boundSubjectId` 是后端 `app_user.bound_subject_id` 的**数字 id**，
   * 而 `code` 是形如 `ST-1001` 的**字符串编码**，两者恒不相等 ——
   * subject 恒为 undefined，方法在警告后直接 return，
   * **DELETE 请求从未发出，数据库绑定关系丝毫未动**。
   * 表现为「点了解绑、页面提示未绑定，刷新后数据仍在」。
   * <p>现改为：优先按数字 id 匹配，并兼容早期可能存入 code 的脏数据。
   */
  async function unbindUserRole(userId: number, reason = '') {
    const users = ensure('users');
    const user = users.find((u: any) => u.id === userId);
    if (!user) return;

    const subject = resolveUserSubject(user);
    if (!subject) {
      window.$message?.warning('该用户未绑定主体');
      return;
    }
    const roleCode = String(user.businessRole || subject.type || '').toUpperCase();
    await unbindUserRoleApi(userId, roleCode, Number(subject.id));
    await loadRemote('users');
    await loadRemote('subjects');
    audit(
      '用户管理',
      '解绑',
      user.nickName || String(userId),
      {
        businessRole: roleCode,
        boundSubjectId: subject.id,
        boundSubjectName: subject.name
      },
      null,
      reason
    );
  }

  /**
   * 由用户记录反查其绑定主体。
   *
   * <p>`boundSubjectId` 是数字 id，但历史脏数据里可能存过主体 code，
   * 故先按 id 匹配、再按 code 兜底，避免任一种形态导致「查不到主体」
   * 而让解绑/校验逻辑静默失效。
   */
  function resolveUserSubject(user: any) {
    const subjects = ensure('subjects');
    const raw = user?.boundSubjectId;
    if (raw == null || raw === '') return null;
    const byId = subjects.find((s: any) => Number(s.id) === Number(raw));
    if (byId) return byId;
    return subjects.find((s: any) => s.code === raw) ?? null;
  }
  /** 主体列表：绑定用户（任意活跃状态可绑定；已绑定需先解绑） */
  /** 主体列表：绑定用户（任意活跃状态可绑定；已绑定需先解绑） */
  async function bindSubjectUser(subjectId: number, userId: number) {
    const subjects = ensure('subjects');
    const subject = subjects.find((s: any) => Number(s.id) === Number(subjectId));
    if (!subject) return;
    if (subject.boundUserId) {
      window.$message?.warning('该经营者已绑定用户，请先解绑');
      return;
    }
    const user = ensure('users').find((u: any) => Number(u.id) === Number(userId));
    if (!user) return;
    if (user.boundSubjectId != null && user.boundSubjectId !== '') {
      window.$message?.warning('该用户已绑定主体，请先解绑');
      return;
    }
    const roleType = (subject.type === 'resource' ? 'resource' : subject.type) as RoleType;
    await bindUserRole(userId, roleType, subject.code, subject.name);
  }

  /** 主体列表：解绑用户（仅停用态可解绑） */
  /** 主体列表：解绑用户（仅停用态可解绑） */
  async function unbindSubjectUser(subjectId: number, reason = '') {
    const subjects = ensure('subjects');
    const subject = subjects.find((s: any) => Number(s.id) === Number(subjectId));
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
    const user = ensure('users').find((u: any) => Number(u.id) === Number(subject.boundUserId));
    if (user) await unbindUserRole(user.id, reason || `解绑用户：${subject.name}`);
  }

  /**
   * 审核角色开通申请（走后端接口）。
   *
   * 原实现仅改本地数组，刷新即丢；现改为调用
   * POST /api/v1/admin/subject/binding/application/{id}/review，
   * 成功后再刷新本地申请列表，保证界面与库一致。
   */
  async function reviewApplication(
    id: number,
    approve: boolean,
    subjectId: string | null,
    subjectName: string | null,
    reason = ''
  ) {
    if (approve && !subjectId) {
      window.$message?.error('该申请未携带主体，无法通过');
      return;
    }
    const list = ensure('roleApplications');
    const idx = list.findIndex((a: any) => a.id === id);
    if (idx >= 0 && list[idx].status !== 'PENDING' && list[idx].status !== 'pending') {
      window.$message?.warning('该申请已审核，不能重复操作');
      return;
    }
    await reviewApplicationApi(id, approve, reason, subjectId ? Number(subjectId) : undefined);
    // 审核成功后重新拉取，避免本地与库不一致
    await loadRemote('roleApplications');
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

  /**
   * 整单退款（走后端接口）。
   *
   * 原实现直接改本地订单状态并本地拼一条退款记录，刷新即丢；
   * 现调用 POST /api/v1/admin/trade/refund，由后端完成
   * 「订单状态流转 + 退款记录 + 资金冲正」。
   */
  async function refundOrder(id: number, reason = '') {
    const list = ensure('orders');
    const order = list.find((o: any) => o.id === id);
    if (!order) return;
    const st = String(order.status || '').toUpperCase();
    if (st === 'VERIFIED' || st === 'COMPLETED') {
      window.$message?.error('已核销订单不可取消');
      return;
    }
    if (st === 'REFUNDED') {
      window.$message?.warning('该订单已退款，不能重复取消');
      return;
    }
    await refundOrderApi(order.orderNo, reason);
    await loadRemote('orders');
    await loadRemote('refunds');
  }

  /**
   * 提现审核（走后端接口）。
   *
   * 原实现自行扣减本地账户与资金池，属「界面记账」，刷新即丢；
   * 现改为调用 POST /api/v1/admin/finance/withdrawals/{id}/review，
   * 由后端在事务内完成「余额扣减 + 资金池 + 流水」，前端只负责刷新镜像。
   */
  async function reviewWithdraw(id: number, approve: boolean, reason = '') {
    const list = ensure('withdrawals');
    const item = list.find((x: any) => x.id === id);
    const st = String(item?.status || '').toUpperCase();
    if (st && st !== 'APPLIED' && st !== 'PENDING') {
      window.$message?.warning('该提现申请已处理，不能重复审核');
      return;
    }
    await reviewWithdrawApi(id, approve, reason);
    await loadRemote('withdrawals');
    await loadRemote('subjectAccounts');
    await loadRemote('fundFlows');
  }

  /**
   * 执行核销（走后端接口）。
   *
   * 后端负责订单状态流转（PAID -> VERIFIED）与核销记录落库，
   * 前端不再本地拼装核销记录，避免「界面有记录、库里没有」。
   */
  async function executeVerify(id: number, reason = '') {
    const pool = ensure('verifyPool');
    const item = pool.find((x: any) => x.id === id);
    if (!item) return;
    await executeVerifyApi({
      type: item.type === 'exchange' ? 'EXCHANGE' : 'ORDER',
      orderNo: item.orderNo,
      verifyCode: item.code || item.pickupCode,
      reason
    });
    await loadRemote('verifyPool');
    await loadRemote('verifies');
    await loadRemote('orders');
  }

  /** 评论审核（走后端接口，成功后再改本地镜像；失败抛出） */
  async function reviewComment(id: number, approve: boolean, reason = '') {
    const list = ensure('comments');
    const item = list.find((x: any) => x.id === id);
    const st = String(item?.status || '').toUpperCase();
    if (st && st !== 'PENDING') {
      window.$message?.warning('该评论已审核，不能重复操作');
      return;
    }
    await reviewCommentApi(id, approve, reason);
    await loadRemote('comments');
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

  /**
   * 【已移除】原 enableSplitRule。
   *
   * 原因：该函数在本地镜像上做「同范围唯一启用」的切换，与后端规则不一致，
   * 且页面已统一改用 store.patch('splitRules', ...) 真写库，留着容易被误用。
   * 分账规则的启用/停用一律走 store.patch('splitRules')。
   */

  /**
   * 分账计算：
   * 成本合计 = Σ(商品成本价×数量) —— 供应商分账
   * 门店 = 门店每件 × 件数
   * 资源方 = 有资源方 ? 资源方每件 × 件数 : 0
   * 基础 = 实付 - 成本 - 门店 - 资源方
   * 投资人 = max(0, 基础) × X%
   * 平台 = 实付 - 成本 - 门店 - 资源方 - 投资人（可能为负，平台承担）
   */
  function calcOrderSplit(
    paid: number,
    itemCount: number,
    costTotal: number,
    rule: any,
    hasChannel: boolean,
    platformCommission = 0
  ) {
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
      acc = {
        id: nextId(accounts),
        subjectId,
        subjectName,
        roleType,
        availableBalance: 0,
        frozenBalance: 0,
        totalIncome: 0,
        totalWithdrawn: 0,
        updateTime: now()
      };
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
  /**
   * 【已停用】手动入账。
   *
   * 后端已有完整自动分账链路：
   * trade 发布 OrderVerifiedEvent -> OrderVerifiedSplitConsumer
   * -> LedgerService.executeSplit，即「核销后自动入账」。
   *
   * 保留前端手动入账会造成**同一订单被重复入账**（资损风险），
   * 故本方法不再执行任何记账，仅提示用户入账由核销自动触发。
   * 若后续确有「自动分账失败后人工补录」需求，应另行设计幂等的补录接口。
   */
  function orderIncome(orderId: number) {
    window.$message?.info('订单入账由「核销」自动触发，无需手动操作');
  }

  /**
   * 提现：金额 <= 免审阈值直接出款，否则待审核
   */
  /**
   * 后台代经营方发起提现（走后端接口）。
   *
   * 原实现是纯本地记账（本地扣余额、本地写提现记录），刷新即丢，
   * 且金额单位是「元」，与后端不一致。
   * 现调用 POST /api/v1/admin/finance/withdrawals/apply，由后端在事务内完成
   * 「余额原子冻结 + 小额即时到账/大额进入审核 + 写资金流水」。
   *
   * @param subjectId 主体 id
   * @param amount    提现金额，单位：**元**（内部换算为分后传后端）
   */
  async function applyWithdraw(subjectId: number, amount: number) {
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
    if (!(amount > 0)) {
      window.$message?.warning('提现金额必须大于 0');
      return;
    }
    const amountFen = Math.round(amount * 100);
    if (amountFen > acc.availableBalance) {
      window.$message?.error('可提现余额不足');
      return;
    }
    // 后端 apply 需要「发起人 app_user.id」用于记录申请人。
    // 说明：subject_account 表本身**不含**用户字段（已核对表结构），
    // 绑定关系存放在 biz_subject.bound_user_id，故从主体查取。
    // 原实现用 `?? 1` 兜底 —— 会把提现错误记到 1 号用户名下，构成数据污染，现改为强校验。
    const subject = ensure('subjects').find((s: any) => s.id === subjectId);
    const ownerUserId = Number(subject?.boundUserId ?? 0);
    if (!ownerUserId) {
      window.$message?.error('该主体未绑定用户，无法代发起提现；请先在主体管理中绑定用户');
      return;
    }
    // 校验该用户确实存在且未删除，避免绑定了失效用户
    // app_user 主键即 id（接口无 userId 字段），主体上的 bound_user_id 存的就是该 id
    const owner = ensure('users').find((u: any) => u.id === ownerUserId);
    if (!owner || owner.deleted) {
      window.$message?.error('该主体绑定的用户不存在或已删除，无法代发起提现');
      return;
    }
    const userId = Number(owner.id);
    const roleType = String(acc.roleType || '').toUpperCase();
    await adminApplyWithdrawApi(userId, subjectId, roleType, amountFen);
    await loadRemote('withdrawals');
    await loadRemote('subjectAccounts');
    await loadRemote('fundFlows');
  }

  /** 冻结账户余额（走后端接口；成功后再刷新本地账户镜像） */
  async function freezeAccount(subjectId: number, reason = '') {
    const acc = ensure('subjectAccounts').find((a: any) => a.subjectId === subjectId);
    if (!acc) {
      window.$message?.warning('未找到该主体账户');
      return;
    }
    if (acc.availableBalance <= 0) {
      window.$message?.warning('无可冻结余额');
      return;
    }
    // 后端按「全部可用余额」冻结，与界面语义一致
    await freezeAccountApi(subjectId, acc.availableBalance);
    await loadRemote('subjectAccounts');
    await loadRemote('fundFlows');
  }

  /** 解冻账户余额（走后端接口；成功后再刷新本地账户镜像） */
  async function unfreezeAccount(subjectId: number, reason = '') {
    const acc = ensure('subjectAccounts').find((a: any) => a.subjectId === subjectId);
    if (!acc) {
      window.$message?.warning('未找到该主体账户');
      return;
    }
    if (acc.frozenBalance <= 0) {
      window.$message?.warning('无可解冻余额');
      return;
    }
    await unfreezeAccountApi(subjectId, acc.frozenBalance);
    await loadRemote('subjectAccounts');
    await loadRemote('fundFlows');
  }

  return {
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
    queryRemote,
    loadProducts,
    loadAdminOrders,
    loadAdminOrderDetail,
    addProduct,
    editProduct,
    setProductOnSale,
    removeProduct,
    loadSpecGroups,
    saveProductSpecGroups,
    saveProductStoreIds,
    isRemote,
    remoteTotals,
    remoteLoaded,
    bindUserRole,
    unbindUserRole,
    bindSubjectUser,
    fetchAdminStores,
    addAdminStore,
    updateAdminStore,
    bindStoreInvestor,
    unbindStoreInvestor,
    bindChannelStore,
    unbindChannelStore,
    unbindSubjectUser,
    reviewApplication,
    toggleFeature,
    refundOrder,
    calcOrderSplit,
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


