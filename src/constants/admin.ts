export interface AdminRouteMeta {
  title: string;
  icon?: string;
  order?: number;
  roles?: string[];
  featureFlag?: string;
  keepAlive?: boolean;
  hideInMenu?: boolean;
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
  user_list: { title: '用户列表', order: 1, roles: op, keepAlive: true },

  review: { title: '申请审核', icon: 'mdi:clipboard-text-clock', order: 25, roles: op },
  review_role: { title: '角色开通审核', order: 1, roles: op, keepAlive: true },
  'review_role-detail': { title: '角色开通审核详情', roles: op, hideInMenu: true },
  subject_platform: { title: '平台主体', order: 1, roles: op, keepAlive: true },
  subject_store: { title: '门店管理', order: 2, roles: op, keepAlive: true },
  subject_channel: { title: '资源方管理', order: 3, roles: op, keepAlive: true },
  subject_investor: { title: '投资人管理', order: 4, roles: op, keepAlive: true },
  subject_supplier: { title: '供应商管理', order: 5, roles: op, keepAlive: true },

  auth: { title: '授权中心', icon: 'mdi:shield-account', order: 20, roles: [ADMIN_ROLE.SUPER] },
  // 授权中心：按需求「只有超级管理员才有这个菜单的权限，其他角色都没有菜单的权限」，
  // 三个子菜单的 roles 一律收紧为仅 R_SUPER。
  auth_role: { title: '角色与权限', order: 1, roles: [ADMIN_ROLE.SUPER], keepAlive: true },
  auth_account: { title: '账号管理', order: 2, roles: [ADMIN_ROLE.SUPER], keepAlive: true },
  auth_grant: { title: '角色授权记录', order: 3, roles: [ADMIN_ROLE.SUPER], keepAlive: true },

  product: { title: '商品中心', icon: 'mdi:cup-outline', order: 30, roles: op },
  product_category: { title: '分类管理', order: 1, roles: op, keepAlive: true },
  product_list: { title: '商品管理', order: 2, roles: op, keepAlive: true },
  product_split: { title: '分账规则', order: 4, roles: op, keepAlive: true },

  trade: { title: '交易中心', icon: 'mdi:receipt-text', order: 40, roles: op },
  trade_order: { title: '订单管理', order: 1, roles: op, keepAlive: true },
  'trade_order-detail': { title: '订单详情', roles: op, hideInMenu: true },
  trade_payment: { title: '支付记录', order: 2, roles: op, keepAlive: true },
  trade_refund: { title: '退款管理', order: 3, roles: op, keepAlive: true },
  'trade_verify-pool': { title: '待核销池', order: 4, roles: op, keepAlive: true },
  trade_verify: { title: '核销记录', order: 5, roles: op, keepAlive: true },
  'trade_verify-detail': { title: '核销记录详情', roles: op, hideInMenu: true },

  finance: { title: '财务中心', icon: 'mdi:wallet', order: 50, roles: fin },
  finance_pool: { title: '资金池', order: 1, roles: fin, keepAlive: true },
  finance_account: { title: '经营方账户', order: 2, roles: fin, keepAlive: true },
  finance_flow: { title: '资金流水', order: 3, roles: fin, keepAlive: true },
  finance_snapshot: { title: '分账快照', order: 4, roles: fin, keepAlive: true },
  'finance_snapshot-detail': { title: '分账快照详情', roles: fin, hideInMenu: true },
  finance_reconcile: { title: '对账异常池', order: 5, roles: fin, keepAlive: true },
  finance_withdraw: { title: '提现管理', order: 6, roles: fin, keepAlive: true },

  marketing: { title: '营销中心', icon: 'mdi:bullhorn', order: 45, roles: op },
  marketing_coupon: { title: '优惠券管理', order: 1, roles: op, featureFlag: 'ENABLE_COUPON', keepAlive: true },
  marketing_stored: { title: '储值套餐', order: 2, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  marketing_gift: { title: '礼品卡', order: 3, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  'marketing_points-category': { title: '积分商城分类', order: 4, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  marketing_points: { title: '积分商城', order: 5, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  marketing_member: { title: '会员等级', order: 6, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  'marketing_gift-order': { title: '礼品卡订单', order: 8, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  marketing_exchange: { title: '兑换记录', order: 9, roles: op, featureFlag: 'ENABLE_STORED_VALUE', keepAlive: true },
  marketing_referral: { title: '分享有礼', order: 10, roles: op, keepAlive: true },

  system: { title: '系统审计', icon: 'mdi:shield', order: 60, roles: audit },
  system_audit: { title: '审计日志', order: 1, roles: audit, keepAlive: true },
  system_feature: { title: '功能开关', order: 2, roles: audit, keepAlive: true },
  system_dict: { title: '数据字典', order: 3, roles: op, keepAlive: true },
  system_city: { title: '城市管理', order: 4, roles: op, keepAlive: true },
};
