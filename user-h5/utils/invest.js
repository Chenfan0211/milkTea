// 投资点位申请（阶段 E 接口化）：门店目录来自 /api/v1/app/stores，
// 申请记录来自 /api/v1/app/roles/applications（role_type=investor），
// 提交走 /api/v1/app/roles/apply。
//
// 2026-09-25 改造前：本模块用 localStorage（milkTea:invest-applications）伪造整个
// 申请流程，INVESTOR_ID 写死为 'INV-1001'，申请不落库、运营后台看不到、换设备即丢。
// 现全部改为调用后端，复用 role_application 链路（用户确认的决策）。
//
// 关键语义：
//   - role_application.subject_id = 目标门店 id（投资点位）；
//   - role_application.role_type = 'investor'；
//   - applicant_name/applicant_phone = 申请人（投资人）姓名/手机号；
//   - budget / remark / 门店名等放 extra_form JSON。
//
// 投资人主体 id 不再由前端写死：提交申请时后端按 JWT userId 识别申请人，
// 审核通过后由运营在后台指定/建立投资人主体绑定（SubjectBindingController）。
const api = require('./api');

// 门店 / 城市内存镜像：由页面调用 refreshInvestCatalog() 拉取注入。
let storeCatalog = [];
let cityCatalog = [];

// 投资申请内存镜像：由页面调用 refreshInvestApplications() 拉取注入。
let applicationList = [];

// 申请状态：审核中 / 已签约 / 已驳回（与后端 role_application.status 对齐）
const INVEST_STATUS_TEXT = {
  PENDING: '审核中',
  APPROVED: '已签约',
  REJECTED: '已驳回'
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
    date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
    ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
  );
}

// 把后端 role_application 记录映射为前端展示结构。
function normalizeRemoteApplication(item) {
  let extra = {};
  try {
    extra = (item.extra_form && typeof item.extra_form === 'object') ? item.extra_form : JSON.parse(item.extra_form || '{}');
  } catch (error) {
    extra = {};
  }
  const status = String(item.status || '').toUpperCase();
  return {
    id: item.id,
    orderNo: String(item.id),
    investorId: null, // 不再有前端写死的投资人 id；申请人由后端 JWT 识别
    storeId: item.subject_id == null ? (extra.storeId || null) : item.subject_id,
    storeName: extra.storeName || '',
    cityName: extra.cityName || '',
    address: extra.address || '',
    budget: extra.budget || '',
    contact: item.applicant_name || extra.contact || '',
    phone: item.applicant_phone || extra.phone || '',
    remark: extra.remark || '',
    status: status === 'PENDING' ? 'pending' : status === 'APPROVED' ? 'signed' : 'rejected',
    statusLabel: INVEST_STATUS_TEXT[status] || status,
    time: item.apply_time || '',
    applyTime: item.apply_time || '',
    reviewTime: item.review_time || ''
  };
}

// 用后端数据刷新门店/城市镜像（页面 onLoad/onShow 时调用）。
function refreshInvestCatalog() {
  return api
    .fetchStores()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        storeCatalog = list.map(store => ({
          // id 用于展示/路由（沿用项目 code 约定）；subjectId 是数字主体 id，提交后端用
          id: store.code || String(store.id),
          subjectId: Number(store.id) || null,
          name: store.name,
          city: store.city || '',
          cityCode: store.city === '广州市' ? 'guangzhou' : store.city === '深圳市' ? 'shenzhen' : 'changsha',
          address: store.address || '',
          phone: store.phone || '',
          businessHours: store.businessHours || '10:00-22:00',
          // 营业状态：closed=停业（不可投资），其余（open）视为可投资
          status: store.businessStatus === 'closed' ? 'disabled' : 'enabled',
          investorSubjectId: store.investorSubjectId || null,
          investorId: store.investorSubjectId || null
        }));
      }
      return api.fetchCities().then(cities => {
        if (Array.isArray(cities) && cities.length) {
          cityCatalog = cities.map(c => ({ code: c.code, name: c.name }));
        }
        return { stores: storeCatalog, cities: cityCatalog };
      }).catch(() => ({ stores: storeCatalog, cities: cityCatalog }));
    })
    .catch(() => ({ stores: storeCatalog, cities: cityCatalog }));
}

// 用后端数据刷新投资申请镜像（只取 role_type=investor 的记录）。
function refreshInvestApplications() {
  return api
    .fetchRoleApplications()
    .then(list => {
      if (Array.isArray(list)) {
        applicationList = list
          .filter(item => String(item.role_type || '').toLowerCase() === 'investor')
          .map(normalizeRemoteApplication);
      }
      return applicationList;
    })
    .catch(() => applicationList);
}

/** 直接注入门店/城市目录（仅供测试使用，绕过 api 调用）。 */
function setInvestCatalogForTest(stores, cities) {
  storeCatalog = Array.isArray(stores) ? stores.map(s => ({
    id: s.code || String(s.id),
    subjectId: Number(s.subjectId || s.id) || null,
    name: s.name,
    city: s.city || '',
    cityCode: s.cityCode || (s.city === '广州市' ? 'guangzhou' : s.city === '深圳市' ? 'shenzhen' : 'changsha'),
    address: s.address || '',
    phone: s.phone || '',
    businessHours: s.businessHours || '10:00-22:00',
    status: s.status || 'enabled',
    investorSubjectId: s.investorSubjectId || null,
    investorId: s.investorId || s.investorSubjectId || null
  })) : [];
  cityCatalog = Array.isArray(cities) ? cities.map(c => ({ code: c.code, name: c.name })) : [];
  return storeCatalog;
}

/** 直接注入投资申请镜像（仅供测试使用，绕过 api 调用）。 */
function setInvestApplicationsForTest(list) {
  applicationList = Array.isArray(list) ? list.map(normalizeRemoteApplication) : [];
  return applicationList;
}

function getCityName(cityCode) {
  const city = cityCatalog.find(item => item.code === cityCode);
  return city ? city.name : '';
}

// 是否已签约：门店绑定的投资人主体 id 等于当前投资人主体 id。
// investorSubjectId 是数字（biz_subject.investor_subject_id），需与当前用户一致才算「我签的」。
function isSignedByMe(store, investorSubjectId) {
  return Boolean(investorSubjectId) && Boolean(store.investorSubjectId) &&
    Number(store.investorSubjectId) === Number(investorSubjectId);
}

// 点位主列表：展示所有启用门店，按申请人视角标记可申请 / 审核中 / 已签约 / 已停用。
// investorSubjectId：当前投资人的主体 id（来自 /roles/mine 的 boundSubjectId），
// 用于区分「我签的」（signed）与「别人签的」（occupied）。
function getSpots(investorSubjectId) {
  const applications = applicationList;
  return storeCatalog.map(store => {
    const pending = applications.find(item => Number(item.storeId) === Number(store.subjectId) && item.status === 'pending');
    const signedByMe = isSignedByMe(store, investorSubjectId);
    let spotStatus = 'available';
    if (store.status !== 'enabled') {
      spotStatus = 'disabled';
    } else if (signedByMe) {
      spotStatus = 'signed';
    } else if (store.investorSubjectId || store.investorId) {
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
      businessHours: store.businessHours,
      phone: store.phone,
      storeStatus: store.status,
      spotStatus,
      canApply: spotStatus === 'available',
      appliedOrderNo: pending ? pending.orderNo : ''
    };
  });
}

function getSpotById(storeId, investorSubjectId) {
  return getSpots(investorSubjectId).find(item => item.id === storeId) || null;
}

function listApplications() {
  return applicationList.map(item => Object.assign({}, item, {
    statusLabel: INVEST_STATUS_TEXT[item.status.toUpperCase()] || item.status
  }));
}

// 申请统计：可投点位 / 已签约 / 审核中 / 已驳回
function getInvestStats(investorSubjectId) {
  const spots = getSpots(investorSubjectId);
  const apps = listApplications();
  return {
    total: spots.length,
    available: spots.filter(item => item.spotStatus === 'available').length,
    signed: spots.filter(item => item.spotStatus === 'signed').length,
    pending: apps.filter(item => item.status === 'pending').length,
    rejected: apps.filter(item => item.status === 'rejected').length
  };
}

function buildInvestTimeline(record) {
  const status = record.status || 'pending';
  const flow = INVEST_FLOW[status] || INVEST_FLOW.pending;
  const activeStep = INVEST_ACTIVE_STEP[status];
  const activeOffset = (flow.find(step => step.id === activeStep) || {}).offset || 0;
  const base = record.time ? new Date(String(record.time).replace(/-/g, '/')).getTime() : Date.now();
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

// 提交申请（异步）：走后端 /api/v1/app/roles/apply，role_type=investor。
// 成功后刷新本地申请镜像。
function submitApplication(payload = {}) {
  const storeId = payload.storeId;
  if (!storeId) return Promise.resolve({ ok: false, message: '请选择投资点位' });
  const spot = getSpotById(storeId, payload.investorSubjectId);
  if (!spot) return Promise.resolve({ ok: false, message: '点位不存在' });
  if (spot.spotStatus === 'disabled') return Promise.resolve({ ok: false, message: '该点位已停用，暂不可申请' });
  if (spot.spotStatus === 'signed' || spot.spotStatus === 'occupied') {
    return Promise.resolve({ ok: false, message: '该点位已绑定投资人' });
  }
  if (spot.spotStatus === 'pending') return Promise.resolve({ ok: false, message: '该点位已有审核中的申请' });

  const form = {
    subjectId: spot.subjectId,
    name: payload.contact || '',
    phone: payload.phone || '',
    budget: payload.budget || '',
    remark: payload.remark || '',
    storeName: spot.name,
    cityName: spot.cityName,
    address: spot.address
  };

  return api
    .applyBusinessRole('investor', form)
    .then(() => refreshInvestApplications())
    .then(list => {
      const record = list.find(item => Number(item.storeId) === Number(spot.subjectId));
      return { ok: true, record: record ? buildInvestTimeline(record) : { id: '', status: 'pending', timeline: [] } };
    })
    .catch(error => ({ ok: false, message: (error && error.message) || '提交失败，请稍后重试' }));
}

function getApplicationDetail(recordId) {
  if (!recordId) return null;
  const record = applicationList.find(item => String(item.id) === String(recordId));
  if (!record) return null;
  const detail = Object.assign({}, record, {
    statusLabel: INVEST_STATUS_TEXT[record.status.toUpperCase()] || record.status,
    statusNote: INVEST_STATUS_NOTE[record.status] || '',
    phoneText: maskPhone(record.phone)
  });
  return Object.assign(detail, { timeline: buildInvestTimeline(record).timeline });
}

module.exports = {
  INVEST_STATUS_TEXT,
  INVEST_STATUS_NOTE,
  INVEST_FLOW,
  refreshInvestCatalog,
  refreshInvestApplications,
  setInvestCatalogForTest,
  setInvestApplicationsForTest,
  getApplicationDetail,
  getInvestStats,
  getSpotById,
  getSpots,
  listApplications,
  submitApplication
};