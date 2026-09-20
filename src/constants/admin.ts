export interface AdminRouteMeta {
  title: string;
  icon?: string;
  order?: number;
  roles?: string[];
  featureFlag?: string;
}

export const ADMIN_ROLE = {
  SUPER: 'R_SUPER',
  OPERATION: 'R_OPERATION',
  FINANCE: 'R_FINANCE',
  AUDIT: 'R_AUDIT'
} as const;

const op = [ADMIN_ROLE.SUPER, ADMIN_ROLE.OPERATION];
const fin = [ADMIN_ROLE.SUPER, ADMIN_ROLE.FINANCE];
const audit = [ADMIN_ROLE.SUPER, ADMIN_ROLE.AUDIT];

export const adminRouteMeta: Record<string, AdminRouteMeta> = {
  subject: { title: '主体管理', icon: 'mdi:domain', order: 10, roles: op },

  user: { title: '用户管理', icon: 'mdi:account-group', order: 15, roles: op },
  user_list: { title: '用户列表', order: 1, roles: op },

  review: { title: '申请审核', icon: 'mdi:clipboard-text-clock', order: 25, roles: op },
  review_role: { title: '角色开通审核', order: 1, roles: op },
  subject_platform: { title: '平台主体', order: 1, roles: op },
  subject_store: { title: '门店管理', order: 2, roles: op },
  subject_channel: { title: '渠道管理', order: 3, roles: op },
  subject_investor: { title: '投资人管理', order: 4, roles: op },
  subject_supplier: { title: '供应商管理', order: 5, roles: op },

  auth: { title: '授权中心', icon: 'mdi:shield-account', order: 20, roles: op },
  auth_role: { title: '角色与权限', order: 1, roles: op },
  auth_wechat: { title: '微信账号绑定', order: 2, roles: op },
  auth_grant: { title: '角色授权记录', order: 3, roles: op },

  product: { title: '商品中心', icon: 'mdi:cup-outline', order: 30, roles: op },
  product_list: { title: '商品管理', order: 1, roles: op },
  product_spec: { title: '规格管理', order: 2, roles: op },
  product_split: { title: '分账规则', order: 3, roles: op },

  trade: { title: '交易中心', icon: 'mdi:receipt-text', order: 40, roles: op },
  trade_order: { title: '订单管理', order: 1, roles: op },
  trade_payment: { title: '支付记录', order: 2, roles: op },
  trade_refund: { title: '退款管理', order: 3, roles: op },
  'trade_verify-pool': { title: '待核销池', order: 4, roles: op },
  trade_verify: { title: '核销记录', order: 5, roles: op },

  finance: { title: '财务中心', icon: 'mdi:wallet', order: 50, roles: fin },
  finance_snapshot: { title: '分账快照', order: 1, roles: fin },
  finance_execute: { title: '分账执行', order: 2, roles: fin },
  finance_ledger: { title: '资金台账', order: 3, roles: fin },
  finance_reconcile: { title: '对账异常池', order: 4, roles: fin },
  finance_withdraw: { title: '提现管理', order: 5, roles: fin },

  marketing: { title: '营销中心', icon: 'mdi:bullhorn', order: 45, roles: op },
  marketing_coupon: { title: '优惠券管理', order: 1, roles: op, featureFlag: 'ENABLE_COUPON' },
  marketing_stored: { title: '储值套餐', order: 2, roles: op, featureFlag: 'ENABLE_STORED_VALUE' },
  marketing_gift: { title: '礼品卡', order: 3, roles: op, featureFlag: 'ENABLE_STORED_VALUE' },
  marketing_points: { title: '积分商城', order: 4, roles: op, featureFlag: 'ENABLE_STORED_VALUE' },
  marketing_member: { title: '会员等级', order: 5, roles: op, featureFlag: 'ENABLE_STORED_VALUE' },
  marketing_comment: { title: '评论审核', order: 6, roles: op, featureFlag: 'ENABLE_REVIEW' },

  system: { title: '系统审计', icon: 'mdi:shield', order: 60, roles: audit },
  system_audit: { title: '审计日志', order: 1, roles: audit },
  system_feature: { title: '功能开关', order: 2, roles: audit }
};
