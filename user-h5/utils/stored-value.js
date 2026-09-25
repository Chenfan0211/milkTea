const MAX_STORED_VALUE_QUANTITY = 10;

function normalizeQuantity(value) {
  const quantity = Number(value);
  if (!Number.isFinite(quantity)) return 1;
  return Math.max(1, Math.min(MAX_STORED_VALUE_QUANTITY, Math.round(quantity)));
}

function changeStoredValueQuantity(current, delta) {
  return normalizeQuantity(current + Number(delta || 0));
}

/**
 * 把后端套餐 DTO 规整为页面渲染结构。
 *
 * 后端金额单位是「分」，页面展示为「元」，换算只在这一处做，
 * 避免金额在页面与工具函数间反复换算导致口径不一致。
 *
 * @param {object} raw 后端返回的套餐 DTO
 */
function normalizePackage(raw) {
  const pkg = raw || {};
  const amountFen = Number(pkg.amount) || 0;
  const coupons = (Array.isArray(pkg.coupons) ? pkg.coupons : []).map((coupon, index) => ({
    id: coupon.couponId != null ? `coupon-${coupon.couponId}` : `coupon-${index}`,
    amount: (Number(coupon.amount) || 0) / 100,
    quantity: Number(coupon.quantity) || 0,
    description: coupon.description || ''
  }));
  // 卡片副标题：把赠券摘要提前到卡面，用户无需滚动即可比较各档权益
  const giftSummary = coupons
    .filter(coupon => coupon.quantity > 0)
    .map(coupon => `${coupon.amount}元代金券×${coupon.quantity}`)
    .join('、');
  return {
    id: pkg.id,
    code: pkg.code || '',
    name: pkg.name || '',
    amount: amountFen / 100,
    benefitText: giftSummary ? `赠${giftSummary}` : '无赠券',
    coupons,
    usageParagraphs: Array.isArray(pkg.usageParagraphs) ? pkg.usageParagraphs : []
  };
}

/**
 * 选择默认选中的套餐下标。
 *
 * 采用「中位」而非第一个：后台通常按金额升序返回，
 * 选第一个会让用户一进页面就看到最低档，与实际主推档位不符；
 * 选中间档在无「推荐」标记时是更稳妥的默认。单张卡时返回 0。
 *
 * @param {Array} packages 规整后的套餐数组
 * @returns {number} 默认选中下标；空数组返回 -1
 */
function pickDefaultPackageIndex(packages) {
  if (!Array.isArray(packages) || !packages.length) return -1;
  return Math.floor((packages.length - 1) / 2);
}
function buildStoredValueSummary(storedValuePackage, quantity) {
  const packageData = storedValuePackage || {};
  const currentQuantity = normalizeQuantity(quantity);
  const totalAmount = Number(packageData.amount || 0) * currentQuantity;
  const giftItems = (packageData.coupons || []).map(coupon => {
    const couponQuantity = Number(coupon.quantity || 0) * currentQuantity;
    return {
      id: coupon.id,
      amount: coupon.amount,
      quantity: couponQuantity,
      text: `${coupon.description}${couponQuantity}张`
    };
  });
  const couponDescription = giftItems.map(item => item.text).join('、');

  return {
    quantity: currentQuantity,
    totalAmount,
    totalText: String(totalAmount),
    giftItems,
    usageParagraphs: [
      `1、本储值套餐包含：储值金额${totalAmount}元、${couponDescription}(满9.9可使用)`,
      '2、储值赠送的券自充值当天起365天有效，请在有效期内尽快使用，单笔订单仅限使用一张优惠券。',
      '3、退款',
      '(1)成功充值后，如有申请退款需求，可以通过小程序“我的” - “联系客服”页，联系小程序客服人员咨询退款。',
      '(2)退款时，如未使用赠送的优惠券，优惠券将会和储值金完整退回;如已使用优惠券，退款时将会扣除使用过优惠券的面额后，退回剩余金额。（以充值100元为例，假设赠送5元优惠券1张、3元优惠券1张、2元优惠券1张，若已使用5元优惠券、10元储值金，退款金额为：100-5-10=85元）。',
      '最终解释权归五零时光所有。'
    ]
  };
}

module.exports = {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity,
  normalizePackage,
  pickDefaultPackageIndex
};
