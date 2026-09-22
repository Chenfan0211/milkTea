// 投资点位（阶段 D 范围）：门店与城市由页面通过 setInvestCatalog 注入，
// 数据来源为 /api/v1/app/stores 与 /api/v1/app/config/cities。
// 本模块不再内置门店假数据；注入前 getSpots() 返回空列表。
let cityCatalog = [];
let storeCatalog = [];

/** 由页面注入门店与城市目录（通常在 onLoad 拉取接口后调用）。 */
function setInvestCatalog(catalog) {
  const next = catalog || {};
  if (Array.isArray(next.stores)) storeCatalog = next.stores;
  if (Array.isArray(next.cities)) cityCatalog = next.cities;
}

function getInvestCatalog() {
  return { cities: cityCatalog.slice(), stores: storeCatalog.slice() };
}

const INVEST_STORAGE_KEY = 'milkTea:invest-applications';

// 申请状态：审核中 / 已签约 / 已驳回
const INVEST_STATUS_TEXT = {
  pending: '审核中',
  signed: '已签约',
  rejected: '已驳回'
};

// 状态流转链路：与提现 / 收益详情页保持同一套时间线语义。
const INVEST_FLOW = {
  pending: [
    { id: 'submitted', title: '已提交', description: '点位投资申请已提交，等待运营受理', offset: 0 },
    { id: 'auditing', title: '运营审核', description: '运营正在审核资质与预算', offset: 420 },
    { id: 'signed', title: '签约入驻', description: '审核通过后完成签约与入驻' },
    { id: 'deposit', title: '保证金缴纳', description: '缴纳保证金后正式开通分级权限' }
  ],
  signed: [
    { id: 'submitted', title: '已提交', description: '点位投资申请已提交', offset: 0 },
    { id: 'auditing', title: '运营审核', description: '资质与预算审核通过', offset: 420 },
    { id: 'signed', title: '签约入驻', description: '已完成签约，点位归属当前投资人', offset: 86400 },
    { id: 'deposit', title: '保证金缴纳', description: '保证金已入账，权限已开通' }
  ],
  rejected: [
    { id: 'submitted', title: '已提交', description: '点位投资申请已提交', offset: 0 },
    { id: 'auditing', title: '运营审核', description: '运营审核未通过', offset: 420 },
    { id: 'rejected', title: '已驳回', description: '申请已驳回，可调整预算后重新提交', offset: 5400 }
  ]
};

const INVEST_ACTIVE_STEP = { pending: 'auditing', signed: 'deposit', rejected: 'rejected' };

// 详情页状态头文案
const INVEST_STATUS_NOTE = {
  pending: '已提交，预计 3 个工作日完成审核',
  signed: '已完成签约，保证金缴纳后开通分级权限',
  rejected: '审核未通过，可调整预算后重新提交'
};

// 手机号脱敏：保留前三后二。
function maskPhone(phone) {
  const value = String(phone || '');
  if (value.length < 7) return value;
  return value.slice(0, 3) + '****' + value.slice(-2);
}

function pad(value) {
  return String(value).padStart(2, '0');
}

function formatStamp(timestamp) {
  const date = new Date(timestamp);
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

function buildInvestTimeline(record) {
  const flow = INVEST_FLOW[record.status] || INVEST_FLOW.pending;
  const activeStep = INVEST_ACTIVE_STEP[record.status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = new Date(String(record.time).replace(/-/g, '/')).getTime();
  // 「保证金缴纳」属于签约后的后续动作，始终视为未开始。
  const followUpStepId = 'deposit';
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
      time: state === 'todo' ? '' : formatStamp(base + offset * 1000)
    };
  });
  return Object.assign({}, record, { timeline });
}

function readApplications() {
  try {
    const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(INVEST_STORAGE_KEY) : '';
    return Array.isArray(value) ? value : [];
  } catch (error) {
    return [];
  }
}

function writeApplications(list) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(INVEST_STORAGE_KEY, list);
    }
  } catch (error) {
    // 存储不可用时保持内存结果可用。
  }
}

function getCityName(cityCode) {
  const city = cityCatalog.find(item => item.code === cityCode);
  return city ? city.name : '';
}

// 是否已签约：门店绑定了当前投资人的编号。
function isSignedByMe(store, investorId) {
  return Boolean(store.investorId) && store.investorId === investorId;
}

// 点位主列表：展示所有启用门店，按申请人视角标记可申请 / 审核中 / 已签约 / 已停用。
function getSpots(investorId = 'INV-1001') {
  const applications = readApplications();
  return storeCatalog.map(store => {
    const pending = applications.find(item => item.storeId === store.id && item.status === 'pending' && item.investorId === investorId);
    const signedByMe = isSignedByMe(store, investorId);
    let spotStatus = 'available';
    if (store.status !== 'enabled') {
      spotStatus = 'disabled';
    } else if (signedByMe) {
      spotStatus = 'signed';
    } else if (store.investorId) {
      // 已绑定其他投资人，当前投资人不可申请。
      spotStatus = 'occupied';
    } else if (pending) {
      spotStatus = 'pending';
    }
    return {
      id: store.id,
      name: store.name,
      cityCode: store.cityCode,
      cityName: getCityName(store.cityCode),
      address: store.address,
      distanceText: store.distanceText,
      distanceKm: store.distanceKm,
      businessHours: store.businessHours,
      phone: store.phone,
      storeStatus: store.status,
      spotStatus,
      canApply: spotStatus === 'available',
      appliedOrderNo: pending ? pending.orderNo : ''
    };
  });
}

function getSpotById(storeId) {
  return getSpots().find(item => item.id === storeId) || null;
}

function listApplications(investorId = 'INV-1001') {
  return readApplications()
    .filter(item => item.investorId === investorId)
    .map(item => Object.assign({}, item, { statusLabel: INVEST_STATUS_TEXT[item.status] || item.status }));
}

// 申请统计：可投点位 / 已签约 / 审核中 / 已驳回
function getInvestStats(investorId = 'INV-1001') {
  const spots = getSpots(investorId);
  const apps = listApplications(investorId);
  return {
    total: spots.length,
    available: spots.filter(item => item.spotStatus === 'available').length,
    signed: spots.filter(item => item.spotStatus === 'signed').length,
    pending: apps.filter(item => item.status === 'pending').length,
    rejected: apps.filter(item => item.status === 'rejected').length
  };
}

function buildOrderNo() {
  const now = new Date();
  const stamp =
    now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate()) + pad(now.getHours()) + pad(now.getMinutes());
  return 'INV' + stamp + pad(now.getSeconds()).slice(-2);
}

function nowStamp() {
  return formatStamp(Date.now());
}

// 提交申请：已绑定 / 停用 / 重复待审的点位会被拒绝。
function submitApplication(payload = {}) {
  const investorId = payload.investorId || 'INV-1001';
  const storeId = payload.storeId;
  if (!storeId) return { ok: false, message: '请选择投资点位' };
  const spot = getSpotById(storeId);
  if (!spot) return { ok: false, message: '点位不存在' };
  if (spot.spotStatus === 'disabled') return { ok: false, message: '该点位已停用，暂不可申请' };
  if (spot.spotStatus === 'signed' || spot.spotStatus === 'occupied') {
    return { ok: false, message: '该点位已绑定投资人' };
  }
  if (spot.spotStatus === 'pending') return { ok: false, message: '该点位已有审核中的申请' };

  const record = {
    id: 'inv-' + Date.now(),
    orderNo: buildOrderNo(),
    investorId,
    storeId: spot.id,
    storeName: spot.name,
    cityName: spot.cityName,
    address: spot.address,
    budget: payload.budget || '',
    contact: payload.contact || '',
    phone: payload.phone || '',
    remark: payload.remark || '',
    status: 'pending',
    time: nowStamp()
  };
  const list = readApplications().concat(record);
  writeApplications(list);
  return { ok: true, record: buildInvestTimeline(record) };
}

function getApplicationDetail(investorId, recordId) {
  if (!investorId || !recordId) return null;
  const record = readApplications().find(item => item.id === recordId && item.investorId === investorId);
  if (!record) return null;
  const detail = Object.assign({}, record, {
    statusLabel: INVEST_STATUS_TEXT[record.status] || record.status,
    statusNote: INVEST_STATUS_NOTE[record.status] || '',
    phoneText: maskPhone(record.phone)
  });
  return Object.assign(detail, { timeline: buildInvestTimeline(record).timeline });
}

module.exports = {
  INVEST_STORAGE_KEY,
  setInvestCatalog,
  getInvestCatalog,
  INVEST_STATUS_TEXT,
  INVEST_STATUS_NOTE,
  INVEST_FLOW,
  getApplicationDetail,
  getInvestStats,
  getSpotById,
  getSpots,
  listApplications,
  submitApplication
};