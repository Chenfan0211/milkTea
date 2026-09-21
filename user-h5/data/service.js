const { stores } = require('./mock');

// 客服信息。服务热线与在线客服时段为占位配置，
// 正式上线前需替换为真实号码并由运营确认服务时间。
const SERVICE_INFO = {
  hotline: '400-000-0000',
  hotlineNote: '热线号码为占位配置，正式上线前需替换',
  serviceHours: '每日 9:00 - 21:00',
  onlineNote: '在线客服由微信提供，会话记录可在微信内查看',
  responseNote: '客服将在收到消息后尽快回复，高峰期可能延迟'
};

// 常见问题：按用户高频咨询场景整理
const SERVICE_FAQS = [
  {
    id: 'order-cancel',
    question: '如何取消订单？',
    answer:
      '在【订单】页找到对应订单，进入订单详情后可自行取消。已支付订单在门店开始制作前可申请退款，退款按原支付路径退回。'
  },
  {
    id: 'stored-value',
    question: '会员储值余额如何使用？',
    answer:
      '储值余额可用于本小程序内下单支付。在订单确认页选择「储值支付」即可使用，余额不可提现、不可转让。'
  },
  {
    id: 'coupon-stack',
    question: '优惠券为什么不能叠加？',
    answer:
      '同一订单默认仅可使用一张优惠券。部分活动商品不参与优惠券抵扣，具体适用范围以优惠券详情页展示为准。'
  },
  {
    id: 'points-expire',
    question: '时光币有效期是多久？',
    answer:
      '时光币存在有效期限制，过期未使用的时光币会自动失效，请在有效期内通过【时光币兑换】使用。'
  },
  {
    id: 'pickup-code',
    question: '取餐码在哪里查看？',
    answer:
      '下单成功后可在【订单】页或订单详情页查看取餐码。到店后向店员出示取餐码或核销二维码即可。'
  },
  {
    id: 'invoice',
    question: '如何开具发票？',
    answer:
      '发票功能正在接入中，如需开票可通过在线客服联系我们，提供订单号后由客服协助处理。'
  }
];

// 门店联系电话：取门店数据中的真实字段，便于用户直接拨打
function getServiceStores() {
  return stores.map(store => ({
    id: store.id,
    name: store.name,
    phone: store.phone,
    businessHours: store.businessHours
  }));
}

function getServiceInfo() {
  return Object.assign({}, SERVICE_INFO);
}

function getServiceFaqs() {
  return SERVICE_FAQS.map(item => Object.assign({}, item));
}

module.exports = {
  SERVICE_INFO,
  SERVICE_FAQS,
  getServiceFaqs,
  getServiceInfo,
  getServiceStores
};