const fs = require('fs');

function yuanToFen(s) {
  if (s == null) return 0;
  const m = String(s).replace(/[¥+\-,]/g, '').trim();
  if (m === '' || m === '—') return 0;
  return Math.round(parseFloat(m) * 100);
}
function esc(v) { return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"; }

// 正确的枚举映射
const WITHDRAW_STATUS = { pending: 'APPLIED', processing: 'APPROVED', success: 'PAID', failed: 'REJECTED' };
const SETTLE_STATUS = { pending: 'PENDING', settled: 'SETTLED', reversed: 'CANCELED' };

const SUBJECT = { store: 101, investor: 201, resource: 301 };
const ROLE_TYPE = { store: 'STORE', investor: 'INVESTOR', resource: 'CHANNEL' };

const L = [];
const push = (...xs) => xs.forEach(x => L.push(x));

push('-- =============================================================');
push('-- 经营角色体系 + 客服配置 seed（阶段 C 假数据清理 · 补充）');
push('-- 来源：user-h5/data/role-mock.js 与 user-h5/data/service.js');
push('--');
push('-- 归属：模拟业务数据绑定到真实 subject');
push('--   store    -> subject_id 101 (星沙乐运魔方店)');
push('--   investor -> subject_id 201 (投资人甲)');
push('--   resource -> subject_id 301 (资源方甲)');
push('-- 金额单位：分。演示数据 record_no/withdraw_no 统一加 DEMO- 前缀，');
push('-- 与真实业务数据（SR.../WD2026...）区分。');
push('-- =============================================================');
push('');

// ---------- 1. 配置类 ----------
push('-- ---------- 1. 配置类数据（app_config） ----------');
const configs = [
  ['settlement_notes', '结算说明', JSON.stringify([
    '仅按订单实付金额分账，时光币兑换与抵扣部分不参与分账',
    '收益入账后先为待结算，T+1 转为可结算，退款订单同步冲正',
    '分账比例以万分比维护，金额统一精确到分，尾差归平台'
  ]), 8],
  ['service_info', '客服信息', JSON.stringify({
    hotline: '400-000-0000',
    hotlineNote: '热线号码为占位配置，正式上线前需替换',
    serviceHours: '每日 9:00 - 21:00',
    onlineNote: '在线客服由微信提供，会话记录可在微信内查看',
    responseNote: '客服将在收到消息后尽快回复，高峰期可能延迟'
  }), 9],
  ['service_faqs', '常见问题', JSON.stringify([
    { id: 'order-cancel', question: '如何取消订单？', answer: '在【订单】页找到对应订单，进入订单详情后可自行取消。已支付订单在门店开始制作前可申请退款，退款按原支付路径退回。' },
    { id: 'stored-value', question: '会员储值余额如何使用？', answer: '储值余额可用于本小程序内下单支付。在订单确认页选择「储值支付」即可使用，余额不可提现、不可转让。' },
    { id: 'coupon-stack', question: '优惠券为什么不能叠加？', answer: '同一订单默认仅可使用一张优惠券。部分活动商品不参与优惠券抵扣，具体适用范围以优惠券详情页展示为准。' },
    { id: 'points-expire', question: '时光币有效期是多久？', answer: '时光币存在有效期限制，过期未使用的时光币会自动失效，请在有效期内通过【时光币兑换】使用。' },
    { id: 'pickup-code', question: '取餐码在哪里查看？', answer: '下单成功后可在【订单】页或订单详情页查看取餐码。到店后向店员出示取餐码或核销二维码即可。' },
    { id: 'invoice', question: '如何开具发票？', answer: '发票功能正在接入中，如需开票可通过在线客服联系我们，提供订单号后由客服协助处理。' }
  ]), 10],
  ['withdraw_rule', '提现规则', JSON.stringify({
    instantLimit: '¥100.00',
    instantNote: '小额即时到账，无需人工审核',
    auditNote: '超过即时额度需后台审核，审核通过后出款',
    feeNote: '提现手续费与单笔上限由后台配置',
    failureNote: '失败或驳回将自动解冻对应金额'
  }), 11]
];
for (const [key, name, value, sort] of configs) {
  push(`INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES`);
  push(`(${esc(key)}, ${esc(name)}, ${esc(value)}, ${sort}, '阶段C补充：经营角色/客服配置')`);
  push(`ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);`);
  push('');
}

// ---------- 2. 核销记录 ----------
push('-- ---------- 2. 核销记录（verify_record，store subject 101，DEMO 前缀） ----------');
const verifyPool = [
  { pickupCode: 'DEMO-A026', orderNo: 'DEMO-D00235803499139801085', product: '抹茶芝士芭乐（首创）' },
  { pickupCode: 'DEMO-B012', orderNo: 'DEMO-D00235803499139801087', product: '红苹果乌龙冰奶' },
  { pickupCode: 'DEMO-A015', orderNo: 'DEMO-D00235803499139801090', product: '五窨茉莉抹茶' }
];
for (const v of verifyPool) {
  push(`INSERT INTO verify_record (verify_code, order_no, type, store_subject_id, result) VALUES`);
  push(`(${esc(v.pickupCode)}, ${esc(v.orderNo)}, 'order', 101, 'success')`);
  push(`ON DUPLICATE KEY UPDATE result=VALUES(result);`);
}

// ---------- 3. 提现记录 ----------
push('');
push('-- ---------- 3. 提现记录（withdrawal，DEMO 前缀，正确枚举） ----------');
const withdrawSeeds = {
  store: [
    { no: 'DEMO-WD202609200003', amount: 500.00, fee: 0, status: 'pending', time: '2026-09-20 15:42:08' },
    { no: 'DEMO-WD202609190002', amount: 88.00, fee: 0, status: 'processing', time: '2026-09-19 11:08:36' },
    { no: 'DEMO-WD202609120001', amount: 1200.00, fee: 6.00, status: 'success', time: '2026-09-12 09:24:15' },
    { no: 'DEMO-WD202609050001', amount: 2000.00, fee: 0, status: 'failed', time: '2026-09-05 20:16:47', reason: '收款账户信息有误，请核对后重新提交' }
  ],
  investor: [
    { no: 'DEMO-WD202609210001', amount: 612.00, fee: 0, status: 'pending', time: '2026-09-21 10:05:22' },
    { no: 'DEMO-WD202609150002', amount: 1806.00, fee: 9.03, status: 'success', time: '2026-09-15 14:32:51' },
    { no: 'DEMO-WD202609080001', amount: 96.00, fee: 0, status: 'processing', time: '2026-09-08 19:47:03' }
  ],
  resource: [
    { no: 'DEMO-WD202609210002', amount: 86.40, fee: 0, status: 'processing', time: '2026-09-21 08:19:30' },
    { no: 'DEMO-WD202609140001', amount: 3400.00, fee: 17.00, status: 'success', time: '2026-09-14 16:58:12' }
  ]
};
for (const [role, list] of Object.entries(withdrawSeeds)) {
  for (const w of list) {
    const sid = SUBJECT[role]; const rt = ROLE_TYPE[role]; const st = WITHDRAW_STATUS[w.status];
    const reason = w.reason ? `, ${esc(w.reason)}` : ', NULL';
    push(`INSERT INTO withdrawal (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, failure_reason) VALUES`);
    push(`(${esc(w.no)}, 1, ${sid}, ${esc(rt)}, ${yuanToFen(w.amount)}, ${yuanToFen(w.fee)}, ${esc(st)}, ${esc(w.time)}${reason})`);
    push(`ON DUPLICATE KEY UPDATE status=VALUES(status);`);
  }
}

// ---------- 4. 收益流水 ----------
push('');
push('-- ---------- 4. 收益流水（settlement_record，DEMO 前缀，正确枚举） ----------');
const incomeSeeds = {
  store: [
    { no: 'DEMO-IC202609180004', amount: 18.90, status: 'pending', time: '2026-09-18 20:31:02' },
    { no: 'DEMO-IC202609180003', amount: 14.90, status: 'pending', time: '2026-09-18 19:16:44' },
    { no: 'DEMO-IC202609170002', amount: 27.80, status: 'settled', time: '2026-09-17 15:08:20' },
    { no: 'DEMO-IC202609160001', amount: 18.90, status: 'reversed', time: '2026-09-16 11:42:36' }
  ],
  investor: [
    { no: 'DEMO-IC202608310002', amount: 1806.00, status: 'settled', time: '2026-08-31 23:59:59' },
    { no: 'DEMO-IC202608310001', amount: 612.00, status: 'pending', time: '2026-08-31 23:59:58' },
    { no: 'DEMO-IC202607310001', amount: 1740.00, status: 'settled', time: '2026-07-31 23:59:59' },
    { no: 'DEMO-IC202607150003', amount: 86.00, status: 'reversed', time: '2026-07-15 16:20:11' }
  ],
  resource: [
    { no: 'DEMO-IC202609210002', amount: 4.20, status: 'pending', time: '2026-09-21 08:19:30' },
    { no: 'DEMO-IC202609210001', amount: 3.60, status: 'pending', time: '2026-09-21 07:40:12' },
    { no: 'DEMO-IC202609200004', amount: 6.30, status: 'settled', time: '2026-09-20 15:08:44' },
    { no: 'DEMO-IC202609190001', amount: 3.60, status: 'reversed', time: '2026-09-19 13:26:05' }
  ]
};
for (const [role, list] of Object.entries(incomeSeeds)) {
  for (const s of list) {
    const sid = SUBJECT[role]; const st = SETTLE_STATUS[s.status];
    push(`INSERT INTO settlement_record (record_no, subject_id, amount, status, settle_date, create_time) VALUES`);
    push(`(${esc(s.no)}, ${sid}, ${yuanToFen(s.amount)}, ${esc(st)}, '${s.time.slice(0,10)}', ${esc(s.time)})`);
    push(`ON DUPLICATE KEY UPDATE status=VALUES(status);`);
  }
}

// ---------- 5. 资金流水 ----------
push('');
push('-- ---------- 5. 资金流水（fund_flow，DEMO 前缀） ----------');
const flowSeeds = [
  { no: 'DEMO-FF202609200003', sid: 101, rt: 'STORE', type: 'WITHDRAW', dir: 'out', amount: 50000, order: 'DEMO-WD202609200003', balance: 120600, remark: '提现申请冻结' },
  { no: 'DEMO-FF202609190002', sid: 101, rt: 'STORE', type: 'WITHDRAW', dir: 'out', amount: 8800, order: 'DEMO-WD202609190002', balance: 120600, remark: '提现出款' },
  { no: 'DEMO-FF202609210001', sid: 201, rt: 'INVESTOR', type: 'WITHDRAW', dir: 'out', amount: 61200, order: 'DEMO-WD202609210001', balance: 180600, remark: '提现申请冻结' },
  { no: 'DEMO-FF202609210002', sid: 301, rt: 'CHANNEL', type: 'WITHDRAW', dir: 'out', amount: 8640, order: 'DEMO-WD202609210002', balance: 342050, remark: '提现申请冻结' }
];
for (const f of flowSeeds) {
  push(`INSERT INTO fund_flow (flow_no, subject_id, role_type, type, direction, amount, order_no, balance_after, remark) VALUES`);
  push(`(${esc(f.no)}, ${f.sid}, ${esc(f.rt)}, ${esc(f.type)}, ${esc(f.dir)}, ${f.amount}, ${esc(f.order)}, ${f.balance}, ${esc(f.remark)})`);
  push(`ON DUPLICATE KEY UPDATE remark=VALUES(remark);`);
}

const out = 'server/src/main/resources/db/migration/V17__seed_role_service_data.sql';
fs.writeFileSync(out, L.join('\n') + '\n', 'utf8');
console.log('V17 rewritten, lines=', L.length);
