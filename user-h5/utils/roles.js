const api = require('./api');

/**
 * 经营角色（门店 / 投资人 / 资源方）前端支撑层。
 *
 * 数据来源（假数据清理后）：
 * - 角色与绑定主体：GET /api/v1/app/roles/mine
 * - 工作台概览 / 资金流水 / 结算台账：/api/v1/app/workbench/subject/{id}/**
 * - 门店订单、渠道绑定门店与订单、投资人门店：/api/v1/app/workbench/**
 * - 核销记录与待核销池：/api/v1/app/workbench/store/{id}/verify-*
 * - 提现记录与规则：/api/v1/app/withdrawals、/api/v1/app/withdrawals/rule
 * - 收益结算说明 / 提现规则文案：app_config（settlement_notes / withdraw_rule）
 *
 * 本文件只保留「后端不返回的展示层内容」：角色图标文案、状态中文映射、
 * 流转链路节点定义。业务数据一律来自接口，不再内置本地假数据回退。
 */

const ROLE_STORAGE_KEY = 'milkTea:business-role';
const ROLE_STATUS_NONE = 'none';
const ROLE_STATUS_PENDING = 'pending';
const ROLE_STATUS_ACTIVE = 'active';

const VALID_STATUSES = [ROLE_STATUS_PENDING, ROLE_STATUS_ACTIVE];

/** 经营角色的展示元信息（图标 / 文案），非业务数据。 */
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

/** 工作台标题与功能入口（纯 UI，路由跳转固定）。 */
const ROLE_DASHBOARD_META = {
  store: {
    title: '门店工作台',
    recordsTitle: '今日核销记录',
    actions: [
      { id: 'verify', label: '核销订单', icon: '/assets/icons/lucide/scan-line.svg', description: '扫码 / 输码 / 订单号核销' },
      { id: 'products', label: '选品管理', icon: '/assets/icons/lucide/shopping-bag.svg', description: '从运营后台商品中选择门店在售商品' },
      { id: 'income', label: '每日收益', icon: '/assets/icons/lucide/banknote.svg', description: '含退款冲正后净额' },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ]
  },
  investor: {
    title: '投资人工作台',
    recordsTitle: '近月分佣明细',
    actions: [
      { id: 'invest', label: '点位投资申请', icon: '/assets/icons/lucide/map-pinned.svg', description: '选择点位 → 审核 → 签约' },
      { id: 'commission', label: '月度分佣', icon: '/assets/icons/lucide/trending-up.svg', description: '查看每月分佣与明细' },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ]
  },
  resource: {
    title: '资源方工作台',
    recordsTitle: '门店订单提成',
    actions: [
      { id: 'orders', label: '门店订单', icon: '/assets/icons/lucide/receipt.svg', description: '查看各绑定门店订单记录' },
      { id: 'income', label: '每日提成', icon: '/assets/icons/lucide/banknote.svg', description: '按门店订单实付金额提成' },
      { id: 'withdraw', label: '提现', icon: '/assets/icons/lucide/wallet.svg', description: '小额即时，大额后台审核' }
    ]
  }
};

function getRoleDefinitions() {
  return roleDefinitions.map(item => Object.assign({}, item));
}

function getRoleMeta(roleId) {
  return roleDefinitions.find(item => item.id === roleId) || null;
}

/** 前端角色 id -> 后端提现接口要求的 roleType 枚举。 */
const ROLE_TYPE_ENUM = { store: 'STORE', investor: 'INVESTOR', resource: 'CHANNEL' };

/** 前端角色 id -> 后端 roleType 枚举；未知返回 null。 */
function roleTypeOf(roleId) {
  return ROLE_TYPE_ENUM[roleId] || null;
}

function normalizeRoleEntry(entry) {
  const roleId = entry && entry.roleId;
  if (!getRoleMeta(roleId)) return null;
  const status = VALID_STATUSES.indexOf(entry.status) >= 0 ? entry.status : ROLE_STATUS_PENDING;
  return { roleId, status };
}

function normalizeState(value) {
  const source = value && typeof value === 'object' ? value : {};
  const roles = Array.isArray(source.roles) ? source.roles.map(normalizeRoleEntry).filter(Boolean) : [];
  const currentRoleId = getRoleMeta(source.currentRoleId) ? source.currentRoleId : '';
  return { roles, currentRoleId };
}

function readStorage() {
  try {
    const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(ROLE_STORAGE_KEY) : '';
    return normalizeState(value);
  } catch (error) {
    return { roles: [], currentRoleId: '' };
  }
}

function writeStorage(state) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(ROLE_STORAGE_KEY, {
        roles: state.roles.map(item => Object.assign({}, item)),
        currentRoleId: state.currentRoleId || ''
      });
    }
  } catch (error) {
    // 存储不可用时保持内存结果可用。
  }
}

function deriveStatus(state) {
  if (state.roles.some(item => item.status === ROLE_STATUS_PENDING)) return ROLE_STATUS_PENDING;
  if (state.roles.some(item => item.status === ROLE_STATUS_ACTIVE)) return ROLE_STATUS_ACTIVE;
  return ROLE_STATUS_NONE;
}

// 兼容旧调用：返回 roles / currentRoleId，并派生 status / roleId 供旧页面读取。
function getRoleState() {
  const state = readStorage();
  const currentRoleId = state.roles.some(
    item => item.roleId === state.currentRoleId && item.status === ROLE_STATUS_ACTIVE
  )
    ? state.currentRoleId
    : '';
  return {
    roles: state.roles.map(item => Object.assign({}, item)),
    currentRoleId,
    roleId: currentRoleId,
    status: deriveStatus(state)
  };
}

function getRoleStatus() {
  return getRoleState().status;
}

function isRoleActive() {
  return getRoleState().status === ROLE_STATUS_ACTIVE;
}

function hasRole(roleId) {
  return readStorage().roles.some(item => item.roleId === roleId);
}

function getActiveRoles() {
  return readStorage()
    .roles.filter(item => item.status === ROLE_STATUS_ACTIVE)
    .map(item => getRoleMeta(item.roleId))
    .filter(Boolean);
}

function getPendingRoles() {
  return readStorage()
    .roles.filter(item => item.status === ROLE_STATUS_PENDING)
    .map(item => getRoleMeta(item.roleId))
    .filter(Boolean);
}

// 从后端同步角色到本地存储（角色以后端 user_role_grant / app_user 为准）。
//
// 设计：只「补写」后端已确认的角色，不清空本地记录 —— 因为本地还承担
// 「申请中 pending」等后端尚未落库的中间态。接口失败时静默返回 null，
// 页面继续用本地缓存，不会白屏。
function syncRolesFromRemote() {
  return api
    .fetchMyRoles()
    .then((data) => {
      if (!data || !Array.isArray(data.roles)) return null;
      const local = readStorage();
      const merged = data.roles
        .filter(item => item && getRoleMeta(item.roleId))
        .map(item => ({
          roleId: item.roleId,
          status: item.status === ROLE_STATUS_PENDING ? ROLE_STATUS_PENDING : ROLE_STATUS_ACTIVE
        }));
      // 保留本地已有但后端未返回的角色（如待审核申请）
      local.roles.forEach(item => {
        if (!merged.some(entry => entry.roleId === item.roleId)) merged.push(item);
      });
      // 记录 subjectId 与主体名称，供工作台等按主体查询接口使用
      const subjectMap = {};
      const subjectNameMap = {};
      data.roles.forEach(item => {
        if (!item || !item.roleId) return;
        if (item.subjectId != null) subjectMap[item.roleId] = item.subjectId;
        if (item.subjectName) subjectNameMap[item.roleId] = item.subjectName;
      });
      const currentRoleId = local.currentRoleId && merged.some(
        item => item.roleId === local.currentRoleId && item.status === ROLE_STATUS_ACTIVE
      )
        ? local.currentRoleId
        : '';
      writeStorage({ roles: merged, currentRoleId });
      writeSubjectMap(subjectMap);
      writeSubjectNameMap(subjectNameMap);
      return data;
    })
    .catch(() => null);
}

const SUBJECT_MAP_KEY = 'milkTea:business-role-subject';
const SUBJECT_NAME_MAP_KEY = 'milkTea:business-role-subject-name';

function writeSubjectMap(map) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(SUBJECT_MAP_KEY, map || {});
    }
  } catch (error) {
    // 存储不可用时忽略，不影响页面渲染
  }
}

function readSubjectMap() {
  try {
    const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(SUBJECT_MAP_KEY) : '';
    return value && typeof value === 'object' ? value : {};
  } catch (error) {
    return {};
  }
}

/** 主体名称缓存：店铺/投资人/资源方名称来自后端 /roles/mine，避免再写死店名。 */
function writeSubjectNameMap(map) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(SUBJECT_NAME_MAP_KEY, map || {});
    }
  } catch (error) {
    // 存储不可用时忽略
  }
}

function readSubjectNameMap() {
  try {
    const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(SUBJECT_NAME_MAP_KEY) : '';
    return value && typeof value === 'object' ? value : {};
  } catch (error) {
    return {};
  }
}

/** 当前角色的经营主体 ID（工作台等按主体查询接口使用）；未知返回 null。 */
function getCurrentSubjectId() {
  const role = getCurrentBusinessRole();
  if (!role) return null;
  const map = readSubjectMap();
  return map[role.id] == null ? null : map[role.id];
}

/** 当前角色的经营主体名称；未知返回 ''。 */
function getCurrentSubjectName() {
  const role = getCurrentBusinessRole();
  if (!role) return '';
  return readSubjectNameMap()[role.id] || '';
}

/** 金额「分 -> 元」文本。 */
function fenToYuanText(fen) {
  const value = (Number(fen) || 0) / 100;
  return '¥' + value.toFixed(2);
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

function formatStamp(timestamp) {
  const date = new Date(timestamp);
  return (
    date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) +
    ' ' + pad2(date.getHours()) + ':' + pad2(date.getMinutes()) + ':' + pad2(date.getSeconds())
  );
}

function formatDayKey(date) {
  return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate());
}

// ============================================================
// 远端数据缓存（内存）
//
// 统一按 subjectId 隔离；接口失败时保持缓存不变，页面保留上次数据不白屏。
// ============================================================

/** 工作台概览 + 流水 + 结算台账：{ [subjectId]: {...} } */
let remoteWorkbenchCache = {};
/** 提现记录：{ [subjectId]: { roleId, records } } */
let remoteWithdrawRecords = {};
/** 核销数据：{ [subjectId]: { roleId, pool, records } } */
let remoteVerifyData = {};
/** 收益台账：{ [subjectId]: { roleId, records } } */
let remoteIncomeCache = {};
/** 渠道/资源方绑定门店与订单：{ [subjectId]: {...} } */
let remoteResourceCache = {};
/** 门店订单：{ [subjectId]: { roleId, orders } } */
let remoteStoreOrdersCache = {};
/** 提现规则（来自后端接口） */
let remoteWithdrawRule = null;
/** 收益结算说明与提现规则文案（来自 app_config） */
let remoteSettlementNotes = null;
let remoteIncomeRuleConfig = null;

/** 后端提现状态 -> 前端展示状态。 */
function mapWithdrawStatus(status) {
  const value = String(status || '').toUpperCase();
  if (value === 'APPLIED' || value === 'AUDITING') return 'pending';
  if (value === 'APPROVED') return 'processing';
  if (value === 'PAID') return 'success';
  if (value === 'REJECTED' || value === 'FAILED') return 'failed';
  return 'pending';
}

/** 后端结算状态 -> 前端展示状态（PENDING / SETTLED / CANCELED）。 */
function mapIncomeStatus(status) {
  const value = String(status || '').toUpperCase();
  if (value === 'SETTLED') return 'settled';
  if (value === 'CANCELED' || value === 'REVERSED') return 'reversed';
  return 'pending';
}

/** 后端订单状态 -> 前端提成单展示状态。 */
function mapOrderStatus(status) {
  const value = String(status || '').toUpperCase();
  if (value === 'COMPLETED' || value === 'VERIFIED') return 'settled';
  if (value === 'CANCELED' || value === 'REFUNDED') return 'reversed';
  return 'pending';
}

/** 后端 Withdrawal -> 前端提现记录结构。 */
function normalizeWithdrawal(item) {
  if (!item) return null;
  const amount = Number(item.amount) || 0;
  const fee = Number(item.fee) || 0;
  return {
    id: String(item.id),
    orderNo: item.withdrawNo || '',
    amount: fenToYuanText(amount),
    feeText: fenToYuanText(fee),
    arrivalText: fenToYuanText(Math.max(0, amount - fee)),
    channel: '微信零钱',
    status: mapWithdrawStatus(item.status),
    time: item.applyTime || item.createTime || '',
    note: item.failureReason || '',
    failReason: item.failureReason || '',
    _raw: item
  };
}

/**
 * 后端 SettlementRecord -> 前端收益记录结构。
 *
 * 后端只给台账字段（金额/状态/结算日期），不含商品名与门店名，
 * 因此标题按结算金额拼装，不伪造商品名。
 */
function normalizeSettlement(item) {
  if (!item) return null;
  const amount = Number(item.amount) || 0;
  const status = mapIncomeStatus(item.status);
  const dateText = item.settleDate || item.createTime || '';
  return {
    id: String(item.id),
    orderNo: item.recordNo || '',
    title: '结算收益 ' + fenToYuanText(amount),
    source: dateText ? '结算日期 ' + String(dateText).slice(0, 10) : '收益结算',
    // 冲正（CANCELED）为支出，展示为负号
    amount: (status === 'reversed' ? '-' : '+') + fenToYuanText(amount),
    amountFen: amount,
    status,
    time: item.createTime || dateText,
    note:
      status === 'pending'
        ? '收益已入账，T+1 转为可结算'
        : status === 'settled'
          ? '结算完成，已转入可结算余额'
          : '订单发生退款，对应收益已同步冲正',
    _raw: item
  };
}

/** 后端订单摘要 -> 前端提成订单展示结构。 */
function normalizeCommissionOrder(item, storeName) {
  if (!item) return null;
  const amount = Number(item.paidAmount) || 0;
  return {
    id: String(item.orderNo || item.id),
    orderNo: item.orderNo || '',
    storeId: item.storeSubjectId == null ? '' : item.storeSubjectId,
    storeName: storeName || '',
    title: storeName || item.orderNo || '门店订单',
    meta: fenToYuanText(amount),
    amountFen: amount,
    amount: fenToYuanText(amount),
    status: mapOrderStatus(item.status),
    time: item.createTime || '',
    _raw: item
  };
}

/** 后端 VerifyRecord -> 前端核销记录结构。 */
function normalizeVerifyRecord(item, index) {
  if (!item) return null;
  return {
    id: String(item.id == null ? index : item.id),
    title: item.orderNo || item.verifyCode || '核销订单',
    meta: '核销码 ' + (item.verifyCode || ''),
    orderNo: item.orderNo || '',
    verifyCode: item.verifyCode || '',
    time: item.createTime || '',
    image: '/assets/images/3x/menu-product.jpg'
  };
}

/** 拉取当前角色的提现记录，写入内存缓存。失败时保持缓存不变。 */
function syncWithdrawalsFromRemote(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return Promise.resolve(null);
  return api
    .fetchWithdrawals()
    .then((page) => {
      const list = page && Array.isArray(page.records) ? page.records : Array.isArray(page) ? page : [];
      const records = list
        .filter(item => item && Number(item.subjectId) === Number(subjectId))
        .map(normalizeWithdrawal)
        .filter(Boolean);
      remoteWithdrawRecords[subjectId] = { roleId: role, records };
      return remoteWithdrawRecords[subjectId];
    })
    .catch(() => null);
}

/** 当前角色对应的远端提现记录（无则 null）。 */
function getRemoteWithdrawals(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return null;
  const cached = remoteWithdrawRecords[subjectId];
  return cached && cached.roleId === role ? cached.records : null;
}

/**
 * 拉取当前角色的工作台真实数据（概览 + 流水 + 结算台账），写入内存缓存。
 * 失败时保持缓存不变，页面保留上次数据。
 */
function syncWorkbenchFromRemote(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return Promise.resolve(null);
  return Promise.all([
    api.fetchWorkbenchOverview(subjectId),
    api.fetchWorkbenchFlows(subjectId),
    api.fetchWorkbenchSettlements(subjectId)
  ])
    .then(([overview, flows, settlements]) => {
      remoteWorkbenchCache[subjectId] = {
        overview: overview || null,
        flows: Array.isArray(flows) ? flows : [],
        settlements: Array.isArray(settlements) ? settlements : [],
        roleId: role
      };
      return remoteWorkbenchCache[subjectId];
    })
    .catch(() => null);
}

/** 当前角色对应的远端工作台缓存（无则 null）。 */
function getRemoteWorkbench(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return null;
  const cached = remoteWorkbenchCache[subjectId];
  return cached && cached.roleId === role ? cached : null;
}

/** 拉取当前角色的收益台账，写入内存缓存。失败时保持缓存不变。 */
function syncIncomeFromRemote(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return Promise.resolve(null);
  // 复用工作台缓存里的结算台账，避免同一次进入重复请求
  const cached = remoteWorkbenchCache[subjectId];
  const fetch = cached && cached.roleId === role
    ? Promise.resolve(cached.settlements)
    : api.fetchWorkbenchSettlements(subjectId).catch(() => null);
  return fetch
    .then((settlements) => {
      if (!Array.isArray(settlements)) return null;
      remoteIncomeCache[subjectId] = {
        roleId: role,
        records: settlements.map(normalizeSettlement).filter(Boolean)
      };
      return remoteIncomeCache[subjectId];
    })
    .catch(() => null);
}

/**
 * 收益数据：全部来自后端结算台账与账户概览。
 * 未拉到数据返回 null（页面据此提示，不再渲染假金额）。
 */
function getIncomeData(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return null;
  const cached = remoteIncomeCache[subjectId];
  if (!cached || cached.roleId !== role) return null;

  const records = cached.records || [];
  const sumFen = status =>
    records.filter(item => item.status === status).reduce((acc, item) => acc + (Number(item.amountFen) || 0), 0);
  const overview = (getRemoteWorkbench(role) || {}).overview || null;

  // 趋势：按结算日期聚合最近 4 天（无数据则空数组，不生成假趋势）
  const trendMap = {};
  records.forEach(item => {
    const day = String(item.time || '').slice(0, 10);
    if (!day) return;
    trendMap[day] = (trendMap[day] || 0) + (Number(item.amountFen) || 0);
  });
  const trend = Object.keys(trendMap)
    .sort()
    .slice(-4)
    .map((day, index) => ({ id: 't' + index, label: day.slice(5), value: fenToYuanText(trendMap[day]) }));

  const todayKey = formatDayKey(new Date());
  const todayFen = records
    .filter(item => String(item.time || '').slice(0, 10) === todayKey)
    .reduce((acc, item) => acc + (Number(item.amountFen) || 0), 0);

  const title = role === 'store' ? '门店收益' : role === 'investor' ? '投资分佣' : '资源方提成';

  return {
    title,
    metricLabel: role === 'store' ? '今日收益' : role === 'investor' ? '本月分佣' : '今日提成',
    today: fenToYuanText(todayFen),
    month: fenToYuanText(overview && overview.totalIncome != null ? overview.totalIncome : 0),
    total: fenToYuanText(overview && overview.totalIncome != null ? overview.totalIncome : 0),
    pending: fenToYuanText(overview && overview.pendingSettlement != null ? overview.pendingSettlement : sumFen('pending')),
    settled: fenToYuanText(overview && overview.settleableAmount != null ? overview.settleableAmount : sumFen('settled')),
    trend,
    records
  };
}

/** 单条收益详情：带状态文案与结算流转链路；找不到返回 null。 */
function getIncomeRecordDetail(roleId, recordId) {
  const income = getIncomeData(roleId);
  if (!income || !recordId) return null;
  const record = (income.records || []).find(item => item.id === String(recordId));
  if (!record) return null;
  return Object.assign({}, record, {
    statusLabel: INCOME_STATUS_TEXT[record.status] || record.status || '',
    statusNote: INCOME_STATUS_NOTE[record.status] || '',
    timeline: buildIncomeTimeline(record)
  });
}

/**
 * 拉取当前角色的核销记录与待核销池，写入内存缓存。
 * 仅门店角色有门店主体，其余角色跳过。
 */
function syncVerifyFromRemote(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (role !== 'store' || subjectId == null) return Promise.resolve(null);
  return Promise.all([
    api.fetchStoreVerifyRecords(subjectId),
    api.fetchStoreVerifyPool(subjectId)
  ])
    .then(([records, pool]) => {
      remoteVerifyData[subjectId] = {
        roleId: role,
        records: (Array.isArray(records) ? records : []).map(normalizeVerifyRecord).filter(Boolean),
        pool: (Array.isArray(pool) ? pool : []).map((item, index) => ({
          id: 'rv-' + index,
          pickupCode: item.pickupCode || '',
          orderNo: item.orderNo || '',
          product: item.summary || '门店订单',
          spec: item.mealType || '',
          amount: fenToYuanText(item.paidAmount)
        }))
      };
      return remoteVerifyData[subjectId];
    })
    .catch(() => null);
}

/** 当前角色对应的远端核销数据（无则 null）。 */
function getRemoteVerify(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (role !== 'store' || subjectId == null) return null;
  const cached = remoteVerifyData[subjectId];
  if (!cached || cached.roleId !== role) return null;
  return { pool: cached.pool, records: cached.records };
}

/** 门店核销数据：以远端为准；未拉到返回空集合（不再回退本地假数据）。 */
function getVerifyData(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  if (role !== 'store') return null;
  const remote = getRemoteVerify(role);
  if (remote) return remote;
  return { pool: [], records: [] };
}

/**
 * 执行门店订单核销（真实写操作）。
 *
 * 核销会「订单置核销 + 触发五方分账」，因此：
 * - 只允许门店角色调用；
 * - 门店归属由后端校验（越权返回 403），前端传的 subjectId 不被信任；
 * - 核销成功后重新拉取核销记录与待核销池，保证页面与库内一致。
 *
 * @param {string} code 取餐码或订单号
 * @returns {Promise<{ok:boolean, message:string, result?:object}>}
 */
function verifyStoreOrderByCode(code) {
  const role = (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (role !== 'store' || subjectId == null) {
    return Promise.resolve({ ok: false, message: '请先选择门店经营角色' });
  }
  const value = String(code || '').trim();
  if (!value) {
    return Promise.resolve({ ok: false, message: '请输入取餐号或订单号' });
  }
  return api
    .verifyStoreOrder(subjectId, value)
    .then((result) => {
      // 核销成功后刷新记录与待核销池，避免页面显示过期数据
      return syncVerifyFromRemote(role).then(() => ({
        ok: true,
        message: (result && result.message) || '核销成功',
        result: result || null
      }));
    })
    .catch(error => ({
      ok: false,
      // 后端 message 已含可读原因（已核销 / 状态不可核销 / 无权核销）
      message: (error && error.message) || '核销失败，请稍后重试'
    }));
}

/**
 * 拉取资源方（渠道）绑定门店与提成订单，或门店订单，写入内存缓存。
 * 投资人点位由 role-invest 页自行拉取，这里不重复请求。
 */
function syncResourceFromRemote(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return Promise.resolve(null);

  if (role === 'resource') {
    return Promise.all([api.fetchChannelStores(subjectId), api.fetchChannelOrders(subjectId)])
      .then(([stores, orders]) => {
        remoteResourceCache[subjectId] = {
          roleId: role,
          stores: Array.isArray(stores) ? stores : [],
          orders: Array.isArray(orders) ? orders : []
        };
        return remoteResourceCache[subjectId];
      })
      .catch(() => null);
  }

  if (role === 'store') {
    return api
      .fetchStoreOrders(subjectId)
      .then((orders) => {
        remoteStoreOrdersCache[subjectId] = {
          roleId: role,
          orders: Array.isArray(orders) ? orders : []
        };
        return remoteStoreOrdersCache[subjectId];
      })
      .catch(() => null);
  }

  return Promise.resolve(null);
}

/** 资源方绑定门店（只读）。数据来自后端，未拉到返回空数组。 */
function getResourceBoundStores(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (role !== 'resource' || subjectId == null) return [];
  const cached = remoteResourceCache[subjectId];
  if (!cached || cached.roleId !== role) return [];
  return cached.stores.map(item => ({
    id: item.id,
    name: item.name,
    storeType: item.code || ''
  }));
}

/** 资源方提成订单：可按门店过滤，并附带提成合计。未拉到返回 null。 */
function getResourceOrders(roleId, storeId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (role !== 'resource' || subjectId == null) return null;
  const cached = remoteResourceCache[subjectId];
  if (!cached || cached.roleId !== role) return null;

  const stores = cached.stores.map(item => ({ id: item.id, name: item.name, storeType: item.code || '' }));
  const nameById = {};
  stores.forEach(store => {
    nameById[store.id] = store.name;
  });

  const all = cached.orders
    .map(item => normalizeCommissionOrder(item, nameById[item.storeSubjectId]))
    .filter(Boolean);
  const selected = storeId && storeId !== 'all'
    ? all.filter(item => String(item.storeId) === String(storeId))
    : all;

  const groups = stores
    .map(store => {
      const list = selected.filter(item => String(item.storeId) === String(store.id));
      const totalFen = list.reduce((sum, item) => sum + (Number(item.amountFen) || 0), 0);
      return {
        id: store.id,
        name: store.name,
        storeType: store.storeType,
        count: list.length,
        totalText: (totalFen >= 0 ? '+' : '') + (totalFen / 100).toFixed(2),
        orders: list.map(item => Object.assign({}, item, { timeline: buildIncomeTimeline(item) }))
      };
    })
    .filter(group => group.count > 0);

  const overallFen = selected.reduce((sum, item) => sum + (Number(item.amountFen) || 0), 0);
  return {
    stores,
    groups,
    count: selected.length,
    totalText: (overallFen >= 0 ? '+' : '') + (overallFen / 100).toFixed(2),
    pendingCount: selected.filter(item => item.status === 'pending').length
  };
}

// ============================================================
// 状态流转链路（纯展示定义，无业务数据）
// ============================================================

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

const INCOME_ACTIVE_STEP = { pending: 'settling', settled: 'settled', reversed: 'reversed' };

/** 按收益状态推导流转链路。 */
function buildIncomeTimeline(record) {
  const status = (record && record.status) || 'pending';
  const flow = INCOME_FLOW[status] || INCOME_FLOW.pending;
  const activeStep = INCOME_ACTIVE_STEP[status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = new Date(String((record && record.time) || '').replace(/-/g, '/')).getTime();
  const safeBase = Number.isFinite(base) ? base : Date.now();
  const followUpStepId = 'withdrawn';
  return flow.map(step => {
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
      time: state === 'todo' ? '' : formatStamp(safeBase + offset * 1000)
    };
  });
}

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

const WITHDRAW_ACTIVE_STEP = { pending: 'auditing', processing: 'paying', success: 'arrived', failed: 'rejected' };

/** 按提现状态推导流转链路。 */
function buildWithdrawTimeline(record) {
  const status = (record && record.status) || 'pending';
  const flow = WITHDRAW_FLOW[status] || WITHDRAW_FLOW.processing;
  const activeStep = WITHDRAW_ACTIVE_STEP[status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = new Date(String((record && record.time) || '').replace(/-/g, '/')).getTime();
  const safeBase = Number.isFinite(base) ? base : Date.now();
  const isTerminal = status === 'success' || status === 'failed';
  return flow.map(step => {
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
      time: state === 'todo' ? '' : formatStamp(safeBase + step.offset * 1000)
    };
  });
}

const WITHDRAW_STATUS_TEXT = {
  pending: '审核中',
  processing: '处理中',
  success: '已到账',
  failed: '已驳回'
};

const WITHDRAW_STATUS_NOTE = {
  pending: '已提交，预计 1 个工作日完成审核',
  processing: '出款处理中，预计 2 小时内到账',
  success: '款项已到账，请注意查收',
  failed: '审核未通过，金额已解冻至可提现余额'
};

const INCOME_STATUS_TEXT = {
  pending: '待结算',
  settled: '已结算',
  reversed: '已冲正'
};

const INCOME_STATUS_NOTE = {
  pending: '收益已入账，T+1 转为可结算',
  settled: '结算完成，已转入可结算余额',
  reversed: '订单发生退款，对应收益已同步冲正'
};

// ============================================================
// 工作台 / 提现 / 收益规则
// ============================================================

/**
 * 工作台数据：标题与入口来自本地 UI 元信息，指标全部来自后端概览接口。
 * 概览未拉到返回 null（页面据此提示，不再渲染假数字）。
 */
function getDashboard(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const meta = ROLE_DASHBOARD_META[role];
  if (!meta) return null;
  const remote = getRemoteWorkbench(role);
  const overview = remote && remote.overview ? remote.overview : null;

  const metrics = [];
  if (role === 'store') {
    metrics.push(
      { id: 'today-orders', label: '今日订单', value: String(overview ? overview.todayOrders || 0 : 0), icon: '/assets/icons/lucide/receipt.svg' },
      { id: 'balance', label: '可提现余额', value: fenToYuanText(overview ? overview.availableBalance : 0), icon: '/assets/icons/lucide/wallet.svg' },
      { id: 'pending', label: '待结算', value: fenToYuanText(overview ? overview.pendingSettlement : 0), icon: '/assets/icons/lucide/badge-percent.svg' }
    );
  } else if (role === 'investor') {
    metrics.push(
      { id: 'totalIncome', label: '累计分佣', value: fenToYuanText(overview ? overview.totalIncome : 0), icon: '/assets/icons/lucide/trending-up.svg' },
      { id: 'balance', label: '可提现余额', value: fenToYuanText(overview ? overview.availableBalance : 0), icon: '/assets/icons/lucide/wallet.svg' },
      { id: 'pending', label: '待结算', value: fenToYuanText(overview ? overview.pendingSettlement : 0), icon: '/assets/icons/lucide/badge-percent.svg' }
    );
  } else {
    metrics.push(
      { id: 'stores', label: '绑定门店', value: String(getResourceBoundStores(role).length) + ' 家', icon: '/assets/icons/lucide/store.svg' },
      { id: 'totalIncome', label: '累计提成', value: fenToYuanText(overview ? overview.totalIncome : 0), icon: '/assets/icons/lucide/badge-percent.svg' },
      { id: 'balance', label: '可提现余额', value: fenToYuanText(overview ? overview.availableBalance : 0), icon: '/assets/icons/lucide/wallet.svg' }
    );
  }

  // 今日核销记录：取门店核销记录前 3 条（真实数据）
  const verify = role === 'store' ? getRemoteVerify(role) : null;
  const records = verify
    ? verify.records.slice(0, 3).map(item => ({
        id: item.id,
        title: item.title,
        meta: item.meta,
        amount: '',
        time: String(item.time || '').slice(11, 16)
      }))
    : [];

  return {
    title: meta.title,
    metrics,
    actions: meta.actions.map(item => Object.assign({}, item)),
    withdrawable: fenToYuanText(overview ? overview.availableBalance : 0),
    boundStoreId: role === 'store' ? String(getCurrentSubjectId() || '') : '',
    boundStoreName: role === 'store' ? getCurrentSubjectName() : '',
    withdrawRule: getWithdrawRule(),
    settlementNotes: getSettlementNotes(),
    recordsTitle: meta.recordsTitle,
    records,
    _remote: Boolean(overview)
  };
}

/** 当前门店角色绑定的经营门店；非门店角色返回 null。 */
function getBoundStore(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  if (role !== 'store') return null;
  const subjectId = getCurrentSubjectId();
  if (subjectId == null) return null;
  return { id: String(subjectId), name: getCurrentSubjectName() };
}

/**
 * 拉取提现规则（后端接口 /api/v1/app/withdrawals/rule）。
 * 失败时返回 null，页面回退到 app_config 文案。
 */
function syncWithdrawRuleFromRemote() {
  return api
    .fetchWithdrawRule()
    .then((rule) => {
      remoteWithdrawRule = rule || null;
      return remoteWithdrawRule;
    })
    .catch(() => null);
}

/**
 * 提现规则文案与入口说明。
 *
 * 金额类（即时额度）取后端接口，保证与实际出款口径一致；
 * 说明文案取 app_config.withdraw_rule（运营可改），接口不可用时用内置兜底。
 */
function getWithdrawRule() {
  const config = remoteIncomeRuleConfig && remoteIncomeRuleConfig.withdrawRule
    ? remoteIncomeRuleConfig.withdrawRule
    : null;
  const limitFen = remoteWithdrawRule && remoteWithdrawRule.instantLimit != null
    ? Number(remoteWithdrawRule.instantLimit)
    : null;
  const instantLimit = limitFen != null ? '¥' + (limitFen / 100).toFixed(2) : (config && config.instantLimit) || '¥100.00';
  return {
    instantLimit,
    instantNote: (remoteWithdrawRule && remoteWithdrawRule.instantNote) || (config && config.instantNote) || '小额即时到账，无需人工审核',
    auditNote: (remoteWithdrawRule && remoteWithdrawRule.auditNote) || (config && config.auditNote) || '超过即时额度需后台审核，审核通过后出款',
    feeNote: (config && config.feeNote) || '提现手续费与单笔上限由后台配置',
    failureNote: (remoteWithdrawRule && remoteWithdrawRule.failureNote) || (config && config.failureNote) || '失败或驳回将自动解冻对应金额',
    items: [
      {
        id: 'instant',
        icon: '/assets/icons/lucide/banknote.svg',
        title: '即时到账',
        description: '单笔不超过即时额度时，系统自动出款，无需人工审核。',
        extra: '当前即时额度：单笔 ≤ ' + instantLimit
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
}

/** 结算说明（app_config.settlement_notes）；未拉到用内置兜底文案。 */
function getSettlementNotes() {
  if (Array.isArray(remoteSettlementNotes) && remoteSettlementNotes.length) {
    return remoteSettlementNotes.slice();
  }
  return [
    '仅按订单实付金额分账，时光币兑换与抵扣部分不参与分账',
    '收益入账后先为待结算，T+1 转为可结算，退款订单同步冲正',
    '分账比例以万分比维护，金额统一精确到分，尾差归平台'
  ];
}

/** 拉取运营配置文案（结算说明 / 提现规则）。失败时保持 null，页面用内置兜底。 */
function syncRoleConfigFromRemote() {
  return Promise.all([api.fetchConfig('settlement_notes'), api.fetchConfig('withdraw_rule')])
    .then(([notes, withdrawRule]) => {
      remoteSettlementNotes = Array.isArray(notes) ? notes : null;
      remoteIncomeRuleConfig = {
        withdrawRule: withdrawRule && typeof withdrawRule === 'object' ? withdrawRule : null
      };
      return remoteIncomeRuleConfig;
    })
    .catch(() => null);
}

/**
 * 提现页数据：余额与冻结额取后端账户，记录取提现接口。
 * 无主体或未拉到数据返回 null（页面据此提示，不再渲染假金额）。
 */
function getWithdrawData(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) return null;
  const overview = (getRemoteWorkbench(role) || {}).overview || null;
  const remote = getRemoteWithdrawals(role);
  return {
    balance: fenToYuanText(overview ? overview.availableBalance : 0),
    pending: fenToYuanText(overview ? overview.frozenBalance : 0),
    // 保证金：后端无对应字段，不再展示写死的假金额
    deposit: '',
    records: remote || []
  };
}

/** 单条提现详情：带状态文案与流转链路；找不到返回 null。 */
function getWithdrawRecordDetail(roleId, recordId) {
  const data = getWithdrawData(roleId);
  if (!data || !recordId) return null;
  const record = (data.records || []).find(item => String(item.id) === String(recordId));
  if (!record) return null;
  return Object.assign({}, record, {
    statusLabel: WITHDRAW_STATUS_TEXT[record.status] || record.status || '',
    statusNote: WITHDRAW_STATUS_NOTE[record.status] || '',
    timeline: buildWithdrawTimeline(record)
  });
}

/**
 * 发起提现（真实写库）。
 *
 * 修复说明：原实现只在本地拼一条假记录 setData 到列表，
 * 既没落库也没冻结余额 —— 用户看到「提交成功」但后台无记录、钱也没动。
 * 现改为调用 POST /api/v1/app/withdrawals，金额单位为「分」。
 *
 * @param {number} amountYuan 提现金额（元）
 * @returns {Promise<object>} 成功返回后端 Withdrawal；失败抛出异常由页面提示
 */
function submitWithdraw(amountYuan) {
  const role = (getCurrentBusinessRole() || {}).id;
  const subjectId = getCurrentSubjectId();
  if (!role || subjectId == null) {
    return Promise.reject(new Error('请先选择经营角色'));
  }
  const roleType = roleTypeOf(role);
  if (!roleType) {
    return Promise.reject(new Error('当前角色不支持提现'));
  }
  const amountFen = Math.round((Number(amountYuan) || 0) * 100);
  if (amountFen <= 0) {
    return Promise.reject(new Error('请输入有效提现金额'));
  }
  return api.applyWithdraw(subjectId, roleType, amountFen);
}

/** 收益结算说明（规则页文案）。 */
function getIncomeRule() {
  return {
    items: [
      {
        id: 'scope',
        icon: '/assets/icons/lucide/badge-percent.svg',
        title: '分账口径',
        description: '仅按订单实付金额分账，时光币兑换与抵扣部分不参与分账。',
        extra: '分账比例以万分比维护，尾差归平台'
      },
      {
        id: 'cycle',
        icon: '/assets/icons/lucide/clock-muted.svg',
        title: '结算周期',
        description: '收益入账后先记为待结算，T+1 转为可结算，可结算余额可发起提现。',
        extra: '结算时效：T+1'
      },
      {
        id: 'reversal',
        icon: '/assets/icons/lucide/shield-check-muted.svg',
        title: '退款冲正',
        description: '订单发生退款时，对应收益同步冲正，已结算部分从可结算余额扣回。',
        extra: ''
      },
      {
        id: 'precision',
        icon: '/assets/icons/lucide/banknote.svg',
        title: '金额精度',
        description: '金额统一精确到分，单笔订单的分账尾差归平台承担。',
        extra: ''
      }
    ],
    notes: getSettlementNotes(),
    footnotes: [
      '分账比例与结算周期以平台后台配置为准',
      '如对结算结果有疑问，可通过微信【五零时光】小程序-【我的】-【联系客服】咨询客服',
      '本说明最终解释权归五零时光所有'
    ]
  };
}

// ============================================================
// 角色状态管理
// ============================================================

// 发起申请：仅当该角色尚不存在时新增 pending；返回该角色条目（兼容旧调用方）。
function applyRole(roleId) {
  const state = readStorage();
  if (!getRoleMeta(roleId) || state.roles.some(item => item.roleId === roleId)) {
    return { roleId: getRoleMeta(roleId) ? roleId : '', status: deriveStatus(state) };
  }
  const next = {
    roles: state.roles.concat({ roleId, status: ROLE_STATUS_PENDING }),
    currentRoleId: state.currentRoleId
  };
  writeStorage(next);
  return { roleId, status: ROLE_STATUS_PENDING };
}

// 仅演示用：把指定（或首个待审核）角色置为已开通。
function activateRole(roleId) {
  const state = readStorage();
  const targetId = roleId || (state.roles.find(item => item.status === ROLE_STATUS_PENDING) || {}).roleId;
  if (!getRoleMeta(targetId)) return { roleId: '', status: deriveStatus(state) };
  const roles = state.roles.map(item =>
    item.roleId === targetId ? { roleId: item.roleId, status: ROLE_STATUS_ACTIVE } : item
  );
  const next = { roles, currentRoleId: state.currentRoleId || targetId };
  writeStorage(next);
  return { roleId: targetId, status: ROLE_STATUS_ACTIVE };
}

// 仅演示用：清空指定角色（无参清空全部），回到未开通状态。
function resetRole(roleId) {
  const state = readStorage();
  const roles = roleId ? state.roles.filter(item => item.roleId !== roleId) : [];
  const currentRoleId = roles.some(item => item.roleId === state.currentRoleId) ? state.currentRoleId : '';
  const next = { roles, currentRoleId };
  writeStorage(next);
  return { roleId: '', status: deriveStatus(next) };
}

// 当前选中且已开通的角色；无则 null。
function getCurrentBusinessRole() {
  const state = getRoleState();
  if (!state.currentRoleId) return null;
  return getRoleMeta(state.currentRoleId);
}

// 切换当前角色：仅允许切换到已 active 的角色。
function switchRole(roleId) {
  const state = readStorage();
  const owned = state.roles.find(item => item.roleId === roleId && item.status === ROLE_STATUS_ACTIVE);
  if (!owned) return null;
  writeStorage({ roles: state.roles, currentRoleId: roleId });
  return getRoleMeta(roleId);
}

// 兼容旧语义：选中账号拥有的 active 角色。
function setCurrentBusinessRole(roleId) {
  return switchRole(roleId);
}

// 切回消费端：清空当前角色，但保留已开通角色记录。
function switchToConsumer() {
  const state = readStorage();
  const next = { roles: state.roles, currentRoleId: '' };
  writeStorage(next);
  return next;
}

/**
 * 进入/切换经营角色时的统一数据预热。
 *
 * 按角色按需拉取，避免一次进入发过多无谓请求：
 * - 门店：概览 + 流水 + 结算、核销记录与待核销池、订单；
 * - 投资人：概览 + 流水 + 结算；
 * - 资源方：概览 + 流水 + 结算、绑定门店与提成订单。
 * 全程失败静默（各 sync 各自返回 null），页面保留上次数据。
 */
function warmUpRoleData(roleId) {
  const role = roleId || (getCurrentBusinessRole() || {}).id;
  if (!role) return Promise.resolve(null);
  const tasks = [syncWorkbenchFromRemote(role), syncRoleConfigFromRemote(), syncWithdrawRuleFromRemote()];
  if (role === 'store') {
    tasks.push(syncVerifyFromRemote(role), syncResourceFromRemote(role), syncWithdrawalsFromRemote(role));
  } else if (role === 'resource') {
    tasks.push(syncResourceFromRemote(role), syncWithdrawalsFromRemote(role));
  } else {
    tasks.push(syncWithdrawalsFromRemote(role));
  }
  return Promise.all(tasks).then(() => syncIncomeFromRemote(role));
}

module.exports = {
  ROLE_STORAGE_KEY,
  ROLE_STATUS_ACTIVE,
  ROLE_STATUS_NONE,
  ROLE_STATUS_PENDING,
  activateRole,
  applyRole,
  buildIncomeTimeline,
  buildWithdrawTimeline,
  getActiveRoles,
  getBoundStore,
  getCurrentBusinessRole,
  getCurrentSubjectId,
  getCurrentSubjectName,
  getDashboard,
  getIncomeData,
  getIncomeRecordDetail,
  getIncomeRule,
  getPendingRoles,
  getRemoteVerify,
  getRemoteWithdrawals,
  getRemoteWorkbench,
  getResourceBoundStores,
  getResourceOrders,
  getRoleDefinitions,
  getRoleMeta,
  getRoleState,
  getRoleStatus,
  getSettlementNotes,
  getVerifyData,
  getWithdrawData,
  getWithdrawRecordDetail,
  getWithdrawRule,
  hasRole,
  isRoleActive,
  normalizeCommissionOrder,
  normalizeSettlement,
  normalizeWithdrawal,
  resetRole,
  roleTypeOf,
  setCurrentBusinessRole,
  submitWithdraw,
  switchRole,
  switchToConsumer,
  syncIncomeFromRemote,
  syncResourceFromRemote,
  syncRoleConfigFromRemote,
  syncRolesFromRemote,
  syncVerifyFromRemote,
  syncWithdrawalsFromRemote,
  syncWithdrawRuleFromRemote,
  syncWorkbenchFromRemote,
  verifyStoreOrderByCode,
  warmUpRoleData,
  INCOME_STATUS_NOTE,
  INCOME_STATUS_TEXT,
  WITHDRAW_STATUS_NOTE,
  WITHDRAW_STATUS_TEXT
};