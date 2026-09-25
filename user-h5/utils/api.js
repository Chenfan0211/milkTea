const config = require('../config');
const { request, unwrap } = require('./request');

/**
 * 小程序端统一接口层。
 *
 * 阶段 C 假数据清理后的约定：
 * - 业务数据全部来自后端接口，本文件不再内置本地假数据回退；
 * - 页面只调用本文件函数，不直接 require data/mock；
 * - 金额：后端统一返回「分」，本层负责转换为页面需要的「元」。
 */

/** 分 -> 元（保留一位小数） */
function fenToYuan(fen) {
  const value = Number(fen) || 0;
  return Math.round(value / 10) / 10;
}

// ============================================================
// 门店 / 菜单 / 商品
// ============================================================

/** 门店列表（App 端） */
function fetchStores() {
  return request({ url: '/api/v1/app/stores', method: 'GET' }).then(unwrap);
}

/** 菜单（tab -> group -> category -> products） */
function fetchMenu() {
  return request({ url: '/api/v1/app/menu', method: 'GET' }).then(unwrap);
}

/** 商品详情 */
function fetchProductDetail(productId) {
  return request({ url: `/api/v1/app/products/${productId}`, method: 'GET' }).then(unwrap);
}

// ============================================================
// 地理位置（服务端代理腾讯位置服务）
// ============================================================
//
// 小程序端不持有腾讯 Key/SK：只传坐标，由服务端带签名调用腾讯接口。
// 未配置密钥时服务端返回空结果，前端需自行回落到直线距离估算。

/**
 * 逆地址解析：经纬度 -> 地址。
 * @param {number} latitude 纬度
 * @param {number} longitude 经度
 * @returns {Promise<{address?:string, formattedAddress?:string, city?:string}>}
 */
function fetchReverseGeocode(latitude, longitude) {
  return request({
    url: '/api/v1/app/geo/regeo',
    method: 'POST',
    data: { latitude, longitude }
  }).then(unwrap);
}

/**
 * 真实驾车距离：用户位置 -> 多个门店。
 * @param {number} latitude 用户纬度
 * @param {number} longitude 用户经度
 * @param {Array<{latitude:number, longitude:number}>} stores 门店坐标列表
 * @returns {Promise<Array<{index:number, distanceKm:number, durationMinutes:number}>>}
 */
function fetchStoreDistances(latitude, longitude, stores) {
  const destinations = (stores || [])
    .filter(store => store && store.latitude && store.longitude)
    .map(store => `${store.latitude},${store.longitude}`);
  if (!destinations.length) return Promise.resolve([]);
  return request({
    url: '/api/v1/app/geo/distance',
    method: 'POST',
    data: { latitude, longitude, destinations }
  })
    .then(unwrap)
    .then(result => (result && result.items) || []);
}

// ============================================================
// 订单 / 支付 / 核销
// ============================================================

/**
 * 创建订单。
 * @param {{storeSubjectId:number, mealType:string, remark?:string,
 *          items:Array<{productId:string, quantity:number, spec?:string}>}} payload
 */
function createOrder(payload) {
  return request({ url: '/api/v1/app/orders', method: 'POST', data: payload }).then(unwrap);
}

/** 我的订单（分页）：后端返回 PageResult { records, current, size, total } */
function fetchOrders(page = 1, size = 20) {
  return request({ url: '/api/v1/app/orders', method: 'GET', data: { page, size } }).then(unwrap);
}

/** 订单详情 */
function fetchOrderDetail(orderNo) {
  return request({ url: `/api/v1/app/orders/${orderNo}`, method: 'GET' }).then(unwrap);
}

/**
 * 取消订单（用户主动）。
 *
 * 后端按订单状态分流：未支付 -> 直接关闭；已支付待核销 -> 整单退款原路退回。
 * 必须在服务端执行，前端不再本地改状态，避免「界面已取消、后端仍是待核销」的数据不一致。
 */
function cancelOrder(orderNo) {
  return request({ url: `/api/v1/app/orders/${orderNo}/cancel`, method: 'POST' }).then(unwrap);
}

/**
 * 发起支付。
 *
 * 支付通道由后端 app.pay.channel 决定：
 * - mock：下单即置为已支付；
 * - wxpay：返回小程序唤起收银台参数，最终以微信回调为准。
 * 前端不感知通道差异，也不在客户端判断支付结果。
 */
function payOrder(orderNo, amount, channel) {
  return request({
    url: `/api/v1/app/orders/${orderNo}/pay`,
    method: 'POST',
    data: { channel: channel || 'MOCK', amount, idempotentKey: `mini-${orderNo}` }
  }).then(unwrap);
}

/**
 * 支付回调 —— 不供小程序调用。
 *
 * 安全说明：该接口要求 HMAC 签名（orderNo + transactionId + timestamp，
 * 密钥仅服务端持有），属「服务端到服务端」接口，由支付通道/网关调用。
 * 小程序端无法也不应构造签名，因此这里不提供调用方法。
 */

// ============================================================
// 用户 / 会员
// ============================================================

function fetchUserProfile() {
  return request({ url: '/api/v1/app/auth/me', method: 'GET' }).then(unwrap);
}

// ============================================================
// 优惠券
// ============================================================

function fetchCoupons() {
  return request({ url: '/api/v1/app/coupons', method: 'GET' }).then(unwrap);
}

/**
 * 我的优惠券。
 * userId 仅为兼容路径，实际归属以 JWT 为准。
 */
function fetchUserCoupons(status) {
  return request({
    url: '/api/v1/app/users/0/coupons',
    method: 'GET',
    data: status ? { status } : {}
  }).then(unwrap);
}

function receiveCoupon(couponId) {
  return request({ url: `/api/v1/app/users/0/coupons/${couponId}/receive`, method: 'POST' }).then(unwrap);
}

// ============================================================
// 储值 / 礼品卡
// ============================================================

function fetchStoredValuePackages() {
  return request({ url: '/api/v1/app/stored-value/packages', method: 'GET' }).then(unwrap);
}

/**
 * 创建储值订单（待支付）。
 *
 * 注意：本接口只建单，**不入账**。余额入账由微信支付回调驱动，
 * 前端拿到订单号后需调起收银台，再轮询 {@link fetchStoredValueOrder} 确认结果。
 */
function createStoredValueOrder(packageId) {
  return request({
    url: '/api/v1/app/stored-value/orders',
    method: 'POST',
    data: { packageId }
  }).then(unwrap);
}

/** 储值订单详情：支付后主动查单，以服务端状态为准 */
function fetchStoredValueOrder(orderNo) {
  return request({ url: `/api/v1/app/stored-value/orders/${orderNo}`, method: 'GET' }).then(unwrap);
}

/**
 * 储值支付：发起微信支付统一下单，返回小程序唤起收银台所需参数。
 *
 * @param {string} orderNo 储值订单号
 * @returns {Promise<{prepayId:string, params:object}>}
 */
function prepayStoredValue(orderNo) {
  return request({
    url: '/api/v1/app/payments/stored-value/prepay',
    method: 'POST',
    data: { orderNo }
  }).then(unwrap);
}

/** 我的储值订单（分页）：后端返回 PageResult */
function fetchStoredValueOrders(page = 1, size = 20) {
  return request({ url: '/api/v1/app/stored-value/orders', method: 'GET', data: { page, size } }).then(unwrap);
}

function fetchGiftCardDenominations() {
  return request({ url: '/api/v1/app/gift-cards/denominations', method: 'GET' }).then(unwrap);
}

function purchaseGiftCard(denominationId) {
  return request({
    url: '/api/v1/app/gift-cards/purchase',
    method: 'POST',
    data: { denominationId }
  }).then(unwrap);
}

function fetchMyGiftCards() {
  return request({ url: '/api/v1/app/gift-cards', method: 'GET' }).then(unwrap);
}

/** 我的礼品卡订单（分页）：后端返回 PageResult */
function fetchGiftCardOrders(page = 1, size = 20) {
  return request({ url: '/api/v1/app/gift-cards/orders', method: 'GET', data: { page, size } }).then(unwrap);
}

/**
 * 核销礼品卡 / 兑换订单（按订单号）。
 *
 * 用于「我的订单」扫码核销与门店核销兑换自提码：
 * 后端会校验归属（订单必须属于当前登录用户）与支付状态，
 * 重复核销会被拒绝，因此前端不再本地自行判定核销结果。
 */
function verifyGiftCardOrder(orderNo) {
  return request({
    url: '/api/v1/app/gift-cards/verify',
    method: 'POST',
    data: { orderNo }
  }).then(unwrap);
}

/** 取消礼品卡订单 */
function cancelGiftCardOrder(orderId) {
  return request({ url: `/api/v1/app/gift-cards/orders/${orderId}/cancel`, method: 'POST' }).then(unwrap);
}

// ============================================================
// 积分（时光币）
// ============================================================

function fetchPointsProducts() {
  return request({ url: '/api/v1/app/points/products', method: 'GET' }).then(unwrap);
}

function fetchPointsRules() {
  return request({ url: '/api/v1/app/points/rules', method: 'GET' }).then(unwrap);
}

function fetchPointsRecords() {
  return request({ url: '/api/v1/app/points/records', method: 'GET' }).then(unwrap);
}

function fetchSigninDates() {
  return request({ url: '/api/v1/app/points/signin-dates', method: 'GET' }).then(unwrap);
}

function signIn() {
  return request({ url: '/api/v1/app/points/signin', method: 'POST' }).then(unwrap);
}

function exchangePointsProduct(productId) {
  return request({
    url: '/api/v1/app/points/exchange',
    method: 'POST',
    data: { productId }
  }).then(unwrap);
}

function fetchExchangeOrders() {
  return request({ url: '/api/v1/app/points/exchange-orders', method: 'GET' }).then(unwrap);
}

// ============================================================
// 经营角色
// ============================================================

/** 当前登录用户的经营角色与绑定主体（经营角色的唯一数据来源） */
function fetchMyRoles() {
  return request({ url: '/api/v1/app/roles/mine', method: 'GET' }).then(unwrap);
}

/** 提交经营角色申请 */
function applyBusinessRole(roleType, form) {
  return request({
    url: '/api/v1/app/roles/apply',
    method: 'POST',
    data: Object.assign({ roleType }, form || {})
  }).then(unwrap);
}

/** 我的角色申请记录 */
function fetchRoleApplications() {
  return request({ url: '/api/v1/app/roles/applications', method: 'GET' }).then(unwrap);
}

/** 门店核销记录（按主体） */
function fetchStoreVerifyRecords(subjectId) {
  return request({
    url: `/api/v1/app/workbench/store/${subjectId}/verify-records`,
    method: 'GET'
  }).then(unwrap);
}

/** 门店待核销池（按主体） */
function fetchStoreVerifyPool(subjectId) {
  return request({
    url: `/api/v1/app/workbench/store/${subjectId}/verify-pool`,
    method: 'GET'
  }).then(unwrap);
}

/**
 * 执行门店核销（扫码 / 输码）。
 *
 * 核销是资金相关写操作：后端会校验「当前登录用户确实经营该门店」
 * （越权返回 403），并按订单状态机拒绝重复核销 / 未支付订单。
 *
 * @param {number|string} subjectId 门店主体 ID
 * @param {string} code 取餐码或订单号
 * @param {{operator?:string, device?:string}} meta 可选：操作人 / 设备标识
 */
function verifyStoreOrder(subjectId, code, meta) {
  return request({
    url: `/api/v1/app/workbench/store/${subjectId}/verify`,
    method: 'POST',
    data: Object.assign({ code, type: 'ORDER' }, meta || {})
  }).then(unwrap);
}

// ============================================================
// 会员等级
// ============================================================

function fetchMemberLevels() {
  return request({ url: '/api/v1/app/member-levels', method: 'GET' }).then(unwrap);
}

// ============================================================
// 角色工作台（P1）
// ============================================================

function fetchWorkbenchOverview(subjectId) {
  return request({ url: `/api/v1/app/workbench/subject/${subjectId}/overview`, method: 'GET' }).then(unwrap);
}

function fetchWorkbenchFlows(subjectId) {
  return request({ url: `/api/v1/app/workbench/subject/${subjectId}/flows`, method: 'GET' }).then(unwrap);
}

function fetchWorkbenchSettlements(subjectId) {
  return request({ url: `/api/v1/app/workbench/subject/${subjectId}/settlements`, method: 'GET' }).then(unwrap);
}

function fetchStoreOrders(storeSubjectId) {
  return request({ url: `/api/v1/app/workbench/store/${storeSubjectId}/orders`, method: 'GET' }).then(unwrap);
}

function fetchChannelStores(channelSubjectId) {
  return request({ url: `/api/v1/app/workbench/channel/${channelSubjectId}/stores`, method: 'GET' }).then(unwrap);
}

function fetchChannelOrders(channelSubjectId) {
  return request({ url: `/api/v1/app/workbench/channel/${channelSubjectId}/orders`, method: 'GET' }).then(unwrap);
}

function fetchInvestorStores(investorSubjectId) {
  return request({ url: `/api/v1/app/workbench/investor/${investorSubjectId}/stores`, method: 'GET' }).then(unwrap);
}

// ============================================================
// 提现（P2）
// ============================================================

function fetchWithdrawRule() {
  return request({ url: '/api/v1/app/withdrawals/rule', method: 'GET' }).then(unwrap);
}

/**
 * 申请提现。
 *
 * 注意：后端该接口用 @RequestParam 接收参数（查询串形式），
 * 而非 JSON 请求体。若按 body 传参，服务端会因取不到 subjectId
 * 抛 MissingServletRequestParameterException（HTTP 500）。
 * 因此这里显式拼查询串，amount 单位为「分」。
 */
function applyWithdraw(subjectId, roleType, amount) {
  const query =
    'subjectId=' + encodeURIComponent(subjectId) +
    '&roleType=' + encodeURIComponent(roleType) +
    '&amount=' + encodeURIComponent(amount);
  return request({
    url: '/api/v1/app/withdrawals?' + query,
    method: 'POST'
  }).then(unwrap);
}

function fetchWithdrawals() {
  return request({ url: '/api/v1/app/withdrawals', method: 'GET' }).then(unwrap);
}

// ============================================================
// 评论
// ============================================================

function submitComment(orderId, rating, content, images) {
  return request({
    url: '/api/v1/app/comments',
    method: 'POST',
    data: { orderId, rating, content, images }
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
    const current = auth.getCachedUser() || {};
    auth.saveSession(Object.assign({}, current, { phone: data.phone }));
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
  return request({ url: '/api/v1/app/config/home', method: 'GET' }).then(unwrap);
}

/** 我的页配置：功能宫格 */
function fetchProfileConfig() {
  return request({ url: '/api/v1/app/config/profile', method: 'GET' }).then(unwrap);
}

/** 城市列表 */
function fetchCities() {
  return request({ url: '/api/v1/app/config/cities', method: 'GET' }).then(unwrap);
}

/** 签到规则与奖励 */
function fetchSigninConfig() {
  return request({ url: '/api/v1/app/config/signin', method: 'GET' }).then(unwrap);
}

/** 通用配置读取（按 key） */
function fetchConfig(key) {
  return request({ url: `/api/v1/app/config/${key}`, method: 'GET' }).then(unwrap);
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
  createOrder,
  fetchOrders,
  fetchOrderDetail,
  cancelOrder,
  payOrder,
  fetchUserProfile,
  fetchCoupons,
  fetchUserCoupons,
  receiveCoupon,
  fetchStoredValuePackages,
  createStoredValueOrder,
  fetchStoredValueOrder,
  prepayStoredValue,
  fetchStoredValueOrders,
  fetchGiftCardDenominations,
  purchaseGiftCard,
  fetchMyGiftCards,
  fetchGiftCardOrders,
  cancelGiftCardOrder,
  verifyGiftCardOrder,
  fetchPointsProducts,
  fetchPointsRules,
  fetchPointsRecords,
  fetchSigninDates,
  signIn,
  exchangePointsProduct,
  fetchExchangeOrders,
  fetchMyRoles,
  applyBusinessRole,
  fetchRoleApplications,
  fetchStoreVerifyRecords,
  fetchStoreVerifyPool,
  verifyStoreOrder,
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
  submitComment,
  fetchReverseGeocode,
  fetchStoreDistances
};
