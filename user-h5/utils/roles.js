const {
  roleDefinitions,
  roleDashboardData,
  verifyPool,
  verifiedRecords,
  incomeData,
  resourceOrders,
  withdrawRule,
  withdrawData
} = require('../data/role-mock');

const ROLE_STORAGE_KEY = 'milkTea:business-role';
const ROLE_STATUS_NONE = 'none';
const ROLE_STATUS_PENDING = 'pending';
const ROLE_STATUS_ACTIVE = 'active';

const VALID_STATUSES = [ROLE_STATUS_PENDING, ROLE_STATUS_ACTIVE];

function getRoleDefinitions() {
  return roleDefinitions.map(item => Object.assign({}, item));
}

function getRoleMeta(roleId) {
  return roleDefinitions.find(item => item.id === roleId) || null;
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
    // Keep the in-memory result usable when storage is unavailable.
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
  const next = { roles: state.roles, currentRoleId: roleId };
  writeStorage(next);
  return getRoleMeta(roleId);
}

// 兼容旧语义：选中账号拥有的 active 角色。
function setCurrentBusinessRole(roleId) {
  return switchRole(roleId);
}

// 模拟切换：任意经营角色立即置为 active 并设为当前角色（绕过审核）。
function mockSwitchRole(roleId) {
  const state = readStorage();
  if (!getRoleMeta(roleId)) return null;
  const roles = state.roles.some(item => item.roleId === roleId)
    ? state.roles.map(item => (item.roleId === roleId ? { roleId: item.roleId, status: ROLE_STATUS_ACTIVE } : item))
    : state.roles.concat({ roleId, status: ROLE_STATUS_ACTIVE });
  const next = { roles, currentRoleId: roleId };
  writeStorage(next);
  return getRoleMeta(roleId);
}

// 切回消费端：清空当前角色，但保留已开通角色记录。
function switchToConsumer() {
  const state = readStorage();
  const next = { roles: state.roles, currentRoleId: '' };
  writeStorage(next);
  return next;
}

function getDashboard(roleId) {
  const data = roleDashboardData[roleId];
  if (!data) return null;
  return {
    title: data.title,
    metrics: data.metrics.map(item => Object.assign({}, item)),
    actions: data.actions.map(item => Object.assign({}, item)),
    withdrawable: data.withdrawable,
    boundStoreId: data.boundStoreId || '',
    boundStoreName: data.boundStoreName || '',
    withdrawRule: Object.assign({}, data.withdrawRule),
    settlementNotes: data.settlementNotes.slice(),
    recordsTitle: data.recordsTitle,
    records: data.records.map(item => Object.assign({}, item))
  };
}

function getVerifyData(roleId) {
  const pool = verifyPool[roleId];
  if (!pool) return null;
  return {
    pool: pool.map(item => Object.assign({}, item)),
    records: (verifiedRecords[roleId] || []).map(item => Object.assign({}, item))
  };
}

// 当前门店角色绑定的经营门店；非门店角色返回 null。
function getBoundStore(roleId) {
  const data = roleDashboardData[roleId];
  if (!data || !data.boundStoreId) return null;
  return { id: data.boundStoreId, name: data.boundStoreName || '' };
}

function getIncomeData(roleId) {
  const data = incomeData[roleId];
  if (!data) return null;
  return {
    title: data.title,
    today: data.today,
    month: data.month,
    total: data.total,
    pending: data.pending,
    settled: data.settled,
    metricLabel: data.metricLabel,
    trend: (data.trend || []).map(item => Object.assign({}, item)),
    records: (data.records || []).map(item =>
      Object.assign({}, item, { timeline: (item.timeline || []).map(step => Object.assign({}, step)) })
    )
  };
}

// 单条收益详情：带状态文案与结算流转链路；找不到返回 null。
function getIncomeRecordDetail(roleId, recordId) {
  const data = incomeData[roleId];
  if (!data || !recordId) return null;
  const record = (data.records || []).find(item => item.id === recordId);
  if (!record) return null;
  return Object.assign({}, record, {
    statusLabel: INCOME_STATUS_TEXT[record.status] || record.status || '',
    statusNote: INCOME_STATUS_NOTE[record.status] || '',
    timeline: (record.timeline || []).map(step => Object.assign({}, step))
  });
}

// 资源方绑定门店（只读，不提供绑定/解绑能力）。
function getResourceBoundStores(roleId) {
  const data = roleDashboardData[roleId];
  if (!data || !Array.isArray(data.boundStores)) return [];
  return data.boundStores.map(item => Object.assign({}, item));
}

// 资源方提成订单：可按门店过滤，并附带提成合计。
function getResourceOrders(roleId, storeId) {
  const data = roleDashboardData[roleId];
  if (!data || !Array.isArray(data.boundStores)) return null;
  const boundIds = data.boundStores.map(item => item.id);
  const all = resourceOrders.filter(item => boundIds.indexOf(item.storeId) >= 0);
  const selected = storeId && storeId !== 'all' ? all.filter(item => item.storeId === storeId) : all;
  const groups = data.boundStores
    .map(store => {
      const list = selected.filter(item => item.storeId === store.id);
      const total = list.reduce((sum, item) => {
        const value = Number(String(item.amount).replace(/[^\d.-]/g, '')) || 0;
        return sum + value;
      }, 0);
      return {
        id: store.id,
        name: store.name,
        storeType: store.storeType,
        count: list.length,
        totalText: (total >= 0 ? '+' : '') + total.toFixed(2),
        orders: list.map(item =>
          Object.assign({}, item, { timeline: (item.timeline || []).map(step => Object.assign({}, step)) })
        )
      };
    })
    .filter(group => group.count > 0);
  const overall = selected.reduce((sum, item) => {
    const value = Number(String(item.amount).replace(/[^\d.-]/g, '')) || 0;
    return sum + value;
  }, 0);
  return {
    stores: data.boundStores.map(item => Object.assign({}, item)),
    groups,
    count: selected.length,
    totalText: (overall >= 0 ? '+' : '') + overall.toFixed(2),
    pendingCount: selected.filter(item => item.status === 'pending').length
  };
}

const WITHDRAW_STATUS_TEXT = {
  pending: '审核中',
  processing: '处理中',
  success: '已到账',
  failed: '已驳回'
};

// 收益结算说明：分账口径、结算周期与退款冲正。
const INCOME_RULE = {
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
  footnotes: [
    '分账比例与结算周期以平台后台配置为准',
    '如对结算结果有疑问，可通过微信【五零时光】小程序-【我的】-【联系客服】咨询客服',
    '本说明最终解释权归五零时光所有'
  ]
};

function getIncomeRule() {
  return {
    items: INCOME_RULE.items.map(item => Object.assign({}, item)),
    footnotes: INCOME_RULE.footnotes.slice()
  };
}

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

// 详情页状态头文案：标题 + 说明
const WITHDRAW_STATUS_NOTE = {
  pending: '已提交，预计 1 个工作日完成审核',
  processing: '出款处理中，预计 2 小时内到账',
  success: '款项已到账，请注意查收',
  failed: '审核未通过，金额已解冻至可提现余额'
};

function getWithdrawRule() {
  return {
    instantLimit: withdrawRule.instantLimit,
    instantNote: withdrawRule.instantNote,
    auditNote: withdrawRule.auditNote,
    feeNote: withdrawRule.feeNote,
    failureNote: withdrawRule.failureNote,
    items: (withdrawRule.items || []).map(item => Object.assign({}, item))
  };
}

function getWithdrawData(roleId) {
  const data = withdrawData[roleId];
  if (!data) return null;
  return {
    balance: data.balance,
    pending: data.pending,
    deposit: data.deposit,
    records: (data.records || []).map(item =>
      Object.assign({}, item, { timeline: (item.timeline || []).map(step => Object.assign({}, step)) })
    )
  };
}

// 单条提现详情：带状态文案与流转链路；找不到返回 null。
function getWithdrawRecordDetail(roleId, recordId) {
  const data = withdrawData[roleId];
  if (!data || !recordId) return null;
  const record = (data.records || []).find(item => item.id === recordId);
  if (!record) return null;
  return Object.assign({}, record, {
    statusLabel: WITHDRAW_STATUS_TEXT[record.status] || record.status || '',
    statusNote: WITHDRAW_STATUS_NOTE[record.status] || '',
    timeline: (record.timeline || []).map(step => Object.assign({}, step))
  });
}

module.exports = {
  ROLE_STORAGE_KEY,
  ROLE_STATUS_ACTIVE,
  ROLE_STATUS_NONE,
  ROLE_STATUS_PENDING,
  activateRole,
  applyRole,
  getActiveRoles,
  getBoundStore,
  getCurrentBusinessRole,
  getDashboard,
  getPendingRoles,
  getRoleDefinitions,
  getRoleMeta,
  getRoleState,
  getRoleStatus,
  hasRole,
  isRoleActive,
  mockSwitchRole,
  resetRole,
  setCurrentBusinessRole,
  switchRole,
  switchToConsumer,
  getVerifyData,
  getIncomeData,
  getIncomeRecordDetail,
  getIncomeRule,
  getResourceBoundStores,
  getResourceOrders,
  getWithdrawData,
  getWithdrawRule,
  getWithdrawRecordDetail,
  WITHDRAW_STATUS_TEXT,
  WITHDRAW_STATUS_NOTE,
  INCOME_STATUS_TEXT,
  INCOME_STATUS_NOTE
};
