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
  // 使用说明改为「全局共用一份」，由后台配置（app_config.stored_value_usage），
  // 后端随套餐 DTO 一并下发，前端不再硬编码。
  const usageParagraphs = Array.isArray(packageData.usageParagraphs)
    ? packageData.usageParagraphs
    : [];

  return {
    quantity: currentQuantity,
    totalAmount,
    totalText: String(totalAmount),
    giftItems,
    usageParagraphs
  };
}

module.exports = {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity,
  normalizePackage,
  pickDefaultPackageIndex
};
