const MAX_STORED_VALUE_QUANTITY = 10

function normalizeQuantity(value) {
  const quantity = Number(value)
  if (!Number.isFinite(quantity)) return 1
  return Math.max(1, Math.min(MAX_STORED_VALUE_QUANTITY, Math.round(quantity)))
}

function changeStoredValueQuantity(current, delta) {
  return normalizeQuantity(current + Number(delta || 0))
}

function buildStoredValueSummary(storedValuePackage, quantity) {
  const packageData = storedValuePackage || {}
  const currentQuantity = normalizeQuantity(quantity)
  const totalAmount = Number(packageData.amount || 0) * currentQuantity
  const giftItems = (packageData.coupons || []).map(coupon => {
    const couponQuantity = Number(coupon.quantity || 0) * currentQuantity
    return {
      id: coupon.id,
      amount: coupon.amount,
      quantity: couponQuantity,
      text: `${coupon.description}${couponQuantity}张`
    }
  })
  const couponDescription = giftItems.map(item => item.text).join('、')

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
  }
}

module.exports = {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity
}