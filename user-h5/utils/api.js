const config = require('../config');
const { request, unwrap } = require('./request');
const mock = require('../data/mock');

/**
 * 小程序端统一接口层。
 *
 * 设计：
 * - 每个接口都在 request 里提供 mock() 回退，USE_MOCK=true 时走本地假数据，false 时走真实后端；
 * - 页面只调用本文件的函数，不直接 require data/mock，便于随时切换数据源；
 * - 金额：后端统一返回「分」，本层负责转换为页面需要的「元」，页面代码保持不变。
 */

/** 分 -> 元（保留一位小数，与 mock 数据口径一致） */
function fenToYuan(fen) {
  const value = Number(fen) || 0;
  return Math.round(value / 10) / 10;
}

// ============================================================
// 门店 / 菜单 / 商品
// ============================================================

/** 门店列表（App 端） */
function fetchStores() {
  return request({
    url: '/api/v1/app/stores',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.stores, message: 'ok' };
    }
  }).then(unwrap);
}

/** 菜单（tab -> group -> category -> products） */
function fetchMenu() {
  return request({
    url: '/api/v1/app/menu',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.menuTabs, message: 'ok' };
    }
  }).then(unwrap);
}

/** 商品详情 */
function fetchProductDetail(productId) {
  return request({
    url: `/api/v1/app/products/${productId}`,
    method: 'GET',
    mock() {
      const found = findMockProduct(productId);
      return { code: found ? 0 : 404, data: found, message: found ? 'ok' : '商品不存在' };
    }
  }).then(unwrap);
}

/** 从 mock 菜单里查找商品（mock 回退用） */
function findMockProduct(productId) {
  const tabs = mock.menuTabs || [];
  for (const tab of tabs) {
    for (const group of tab.groups || []) {
      for (const category of group.categories || []) {
        const product = (category.products || []).find(item => item.id === productId);
        if (product) return product;
      }
    }
  }
  return null;
}

// ============================================================
// 订单 / 支付 / 核销
// ============================================================

/**
 * 创建订单。
 * @param {{userId:number, storeSubjectId:number, mealType:string, remark?:string,
 *          items:Array<{productId:string, quantity:number, spec?:string}>}} payload
 */
function createOrder(payload) {
  return request({
    url: '/api/v1/app/orders',
    method: 'POST',
    data: payload,
    mock() {
      return {
        code: 0,
        data: {
          orderNo: `MOCK${Date.now()}`,
          status: 'CREATED',
          payStatus: 'UNPAID',
          paidAmount: 0,
          store: '本地演示门店',
          summary: '',
          items: []
        },
        message: 'ok'
      };
    }
  }).then(unwrap);
}

/** 我的订单 */
function fetchOrders() {
  return request({
    url: '/api/v1/app/orders',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.orders, message: 'ok' };
    }
  }).then(unwrap);
}

/** 订单详情 */
function fetchOrderDetail(orderNo) {
  return request({
    url: `/api/v1/app/orders/${orderNo}`,
    method: 'GET',
    mock() {
      const found = (mock.orders || []).find(o => (o.orderInfo && o.orderInfo.orderNo) === orderNo);
      return { code: found ? 0 : 404, data: found, message: found ? 'ok' : '订单不存在' };
    }
  }).then(unwrap);
}

/** 发起支付（Mock 通道：直接完成支付） */
function payOrder(orderNo, amount, channel) {
  return request({
    url: `/api/v1/app/orders/${orderNo}/pay`,
    method: 'POST',
    data: { channel: channel || 'MOCK', amount, idempotentKey: `mini-${orderNo}` },
    mock() {
      return { code: 0, data: { orderNo, status: 'PAID', payStatus: 'PAID', pickupCode: '0000' }, message: 'ok' };
    }
  }).then(unwrap);
}

/** 支付回调（真实接入后由第三方通道调用，这里供联调） */
function paymentCallback(orderNo, transactionId) {
  return request({
    url: '/api/v1/app/payments/callback',
    method: 'POST',
    data: { orderNo, transactionId },
    mock() {
      return { code: 0, data: { orderNo, status: 'PAID' }, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 用户 / 会员
// ============================================================

function fetchUserProfile() {
  return request({
    url: '/api/v1/app/auth/me',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.userProfile, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 优惠券
// ============================================================

function fetchCoupons() {
  return request({
    url: '/api/v1/app/coupons',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.coupons, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchUserCoupons(status) {
  return request({
    url: '/api/v1/app/users/0/coupons',
    method: 'GET',
    data: status ? { status } : {},
    mock() {
      return { code: 0, data: mock.coupons, message: 'ok' };
    }
  }).then(unwrap);
}

function receiveCoupon(couponId) {
  return request({
    url: `/api/v1/app/users/0/coupons/${couponId}/receive`,
    method: 'POST',
    mock() {
      return { code: 0, data: { id: Date.now(), couponId, status: 'UNUSED' }, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 储值 / 礼品卡
// ============================================================

function fetchStoredValuePackages() {
  return request({
    url: '/api/v1/app/stored-value/packages',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.storedValuePackages, message: 'ok' };
    }
  }).then(unwrap);
}

function rechargeStoredValue(packageId) {
  return request({
    url: '/api/v1/app/stored-value/recharge',
    method: 'POST',
    data: { packageId },
    mock() {
      return { code: 0, data: { orderNo: `MOCKCZ${Date.now()}`, payStatus: 'PAID' }, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchGiftCardDenominations() {
  return request({
    url: '/api/v1/app/gift-cards/denominations',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.giftCardDenominations, message: 'ok' };
    }
  }).then(unwrap);
}

function purchaseGiftCard(denominationId) {
  return request({
    url: '/api/v1/app/gift-cards/purchase',
    method: 'POST',
    data: { denominationId },
    mock() {
      return { code: 0, data: { orderNo: `MOCKGC${Date.now()}`, payStatus: 'PAID' }, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchMyGiftCards() {
  return request({
    url: '/api/v1/app/gift-cards',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.userProfile.giftCards || [], message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 积分（时光币）
// ============================================================

function fetchPointsProducts() {
  return request({
    url: '/api/v1/app/points/products',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.pointsProducts, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchPointsRules() {
  return request({
    url: '/api/v1/app/points/rules',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.pointsEarningRules, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchPointsRecords() {
  return request({
    url: '/api/v1/app/points/records',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.pointsRecords, message: 'ok' };
    }
  }).then(unwrap);
}

function signIn() {
  return request({
    url: '/api/v1/app/points/signin',
    method: 'POST',
    mock() {
      return { code: 0, data: { balance: 0, message: '签到成功' }, message: 'ok' };
    }
  }).then(unwrap);
}

function exchangePointsProduct(productId) {
  return request({
    url: '/api/v1/app/points/exchange',
    method: 'POST',
    data: { productId },
    mock() {
      return { code: 0, data: { pickupCode: `MOCKCZ${Date.now()}`, status: 'PENDING' }, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchExchangeOrders() {
  return request({
    url: '/api/v1/app/points/exchange-orders',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.exchangeRecords, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 会员等级 / 城市
// ============================================================

function fetchMemberLevels() {
  return request({
    url: '/api/v1/app/member-levels',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.memberLevels, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 角色工作台（P1）
// ============================================================

function fetchWorkbenchOverview(subjectId) {
  return request({
    url: `/api/v1/app/workbench/subject/${subjectId}/overview`,
    method: 'GET',
    mock() {
      return {
        code: 0,
        data: { subjectId, availableBalance: 0, frozenBalance: 0, totalIncome: 0, todayOrders: 0 },
        message: 'ok'
      };
    }
  }).then(unwrap);
}

function fetchWorkbenchFlows(subjectId) {
  return request({
    url: `/api/v1/app/workbench/subject/${subjectId}/flows`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

function fetchWorkbenchSettlements(subjectId) {
  return request({
    url: `/api/v1/app/workbench/subject/${subjectId}/settlements`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

function fetchStoreOrders(storeSubjectId) {
  return request({
    url: `/api/v1/app/workbench/store/${storeSubjectId}/orders`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

function fetchChannelStores(channelSubjectId) {
  return request({
    url: `/api/v1/app/workbench/channel/${channelSubjectId}/stores`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

function fetchChannelOrders(channelSubjectId) {
  return request({
    url: `/api/v1/app/workbench/channel/${channelSubjectId}/orders`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

function fetchInvestorStores(investorSubjectId) {
  return request({
    url: `/api/v1/app/workbench/investor/${investorSubjectId}/stores`,
    method: 'GET',
    mock() {
      return { code: 0, data: [], message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 提现（P2）
// ============================================================

function fetchWithdrawRule() {
  return request({
    url: '/api/v1/app/withdrawals/rule',
    method: 'GET',
    mock() {
      return {
        code: 0,
        data: { instantLimit: 10000, instantNote: '小额即时到账，无需人工审核' },
        message: 'ok'
      };
    }
  }).then(unwrap);
}

function applyWithdraw(subjectId, roleType, amount) {
  return request({
    url: '/api/v1/app/withdrawals',
    method: 'POST',
    data: { subjectId, roleType, amount },
    mock() {
      return { code: 0, data: { withdrawNo: `MOCKWD${Date.now()}`, status: 'APPLIED' }, message: 'ok' };
    }
  }).then(unwrap);
}

function fetchWithdrawals() {
  return request({
    url: '/api/v1/app/withdrawals',
    method: 'GET',
    mock() {
      return { code: 0, data: { records: [], total: 0 }, message: 'ok' };
    }
  }).then(unwrap);
}

// ============================================================
// 评论
// ============================================================

function submitComment(orderId, rating, content, images) {
  return request({
    url: '/api/v1/app/comments',
    method: 'POST',
    data: { orderId, rating, content, images },
    mock() {
      return { code: 0, data: { id: Date.now(), status: 'PENDING' }, message: 'ok' };
    }
  }).then(unwrap);
}


// ============================================================
// 登录（微信）
// ============================================================

const auth = require('./auth');

/** 微信登录：wx.login -> code -> token（登录入口，不携带 token） */
function wxLogin() {
  return auth.ensureLogin();
}

/** 当前登录用户资料 */
function fetchMe() {
  return auth.fetchMe();
}

/** 上报定位（需登录） */
function reportLocation(location) {
  return auth.reportLocation(location);
}

/** 绑定手机号 */
function bindPhone(encryptedData, iv) {
  return auth.bindPhone(encryptedData, iv);
}

/** 更新头像昵称 */
function bindProfile(encryptedData, iv) {
  return auth.bindProfile(encryptedData, iv);
}

/** 发送短信验证码（降级方案） */
function sendSmsCode(phone) {
  return request({
    url: '/api/v1/app/auth/sms/send',
    method: 'POST',
    data: { phone }
  }).then(unwrap);
}

/** 校验验证码并绑定手机号（降级方案） */
function bindPhoneBySms(phone, code) {
  return request({
    url: '/api/v1/app/auth/sms/bind',
    method: 'POST',
    data: { phone, code }
  }).then(res => {
    const data = unwrap(res);
    const auth = require('./auth');
    const cached = auth.getCachedUser() || {};
    auth.saveSession(Object.assign({}, cached, { phone: data.phone }));
    return data;
  });
}

/** 更新昵称（新版 input type=nickname） */
function updateNickName(nickName) {
  return request({
    url: '/api/v1/app/auth/nickname',
    method: 'POST',
    data: { nickName }
  }).then(unwrap);
}

/** 更新头像（新版 chooseAvatar） */
function updateAvatar(avatar) {
  return request({
    url: '/api/v1/app/auth/avatar',
    method: 'POST',
    data: { avatar }
  }).then(unwrap);
}

// ============================================================
// 运营配置（后台可编辑，避免发版）
// ============================================================

/** 首页配置：快捷入口 + 点单活动 */
function fetchHomeConfig() {
  return request({
    url: '/api/v1/app/config/home',
    method: 'GET',
    mock() {
      return { code: 0, data: { shortcuts: mock.homeShortcuts, menuActivity: mock.menuActivity }, message: 'ok' };
    }
  }).then(unwrap);
}

/** 我的页配置：功能宫格 */
function fetchProfileConfig() {
  return request({
    url: '/api/v1/app/config/profile',
    method: 'GET',
    mock() {
      return { code: 0, data: { functions: mock.profileFunctions }, message: 'ok' };
    }
  }).then(unwrap);
}

/** 城市列表 */
function fetchCities() {
  return request({
    url: '/api/v1/app/config/cities',
    method: 'GET',
    mock() {
      return { code: 0, data: mock.cities, message: 'ok' };
    }
  }).then(unwrap);
}

/** 签到规则与奖励 */
function fetchSigninConfig() {
  return request({
    url: '/api/v1/app/config/signin',
    method: 'GET',
    mock() {
      return { code: 0, data: { rules: mock.signInRules, rewards: mock.signInRewards }, message: 'ok' };
    }
  }).then(unwrap);
}

/** 通用配置读取（按 key） */
function fetchConfig(key) {
  return request({
    url: `/api/v1/app/config/${key}`,
    method: 'GET',
    mock() {
      return { code: 0, data: null, message: 'ok' };
    }
  }).then(unwrap);
}
module.exports = {
  fenToYuan,
  fetchHomeConfig,
  fetchProfileConfig,
  fetchCities,
  fetchSigninConfig,
  fetchConfig,
  sendSmsCode,
  bindPhoneBySms,
  updateNickName,
  updateAvatar,
  wxLogin,
  fetchMe,
  reportLocation,
  bindPhone,
  bindProfile,
  fetchStores,
  fetchMenu,
  fetchProductDetail,
  findMockProduct,
  createOrder,
  fetchOrders,
  fetchOrderDetail,
  payOrder,
  paymentCallback,
  fetchUserProfile,
  fetchCoupons,
  fetchUserCoupons,
  receiveCoupon,
  fetchStoredValuePackages,
  rechargeStoredValue,
  fetchGiftCardDenominations,
  purchaseGiftCard,
  fetchMyGiftCards,
  fetchPointsProducts,
  fetchPointsRules,
  fetchPointsRecords,
  signIn,
  exchangePointsProduct,
  fetchExchangeOrders,
  fetchMemberLevels,
  fetchWorkbenchOverview,
  fetchWorkbenchFlows,
  fetchWorkbenchSettlements,
  fetchStoreOrders,
  fetchChannelStores,
  fetchChannelOrders,
  fetchInvestorStores,
  fetchWithdrawRule,
  applyWithdraw,
  fetchWithdrawals,
  submitComment
};





