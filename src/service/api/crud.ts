import { request } from '../request';

/**
 * 后台通用 CRUD 客户端。
 *
 * 对应后端 /api/v1/admin/crud/{resource}，资源白名单由后端 CrudRegistry 维护。
 * 注意：底层 request 为 flatRequest，失败时返回 { isSuccess:false, error }，
 * 这里统一解包为「成功返回数据 / 失败抛异常」，便于 store 用 try/catch 处理。
 */

export interface CrudPage<T = any> {
  records: T[];
  current: number;
  size: number;
  total: number;
}

/** 解包 flatRequest 结果 */
function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'isSuccess' in result) {
    if (!result.isSuccess) {
      throw result.error || new Error('请求失败');
    }
    return result.data as T;
  }
  return result as T;
}

/** 分页查询 */
export async function crudPage<T = any>(resource: string, params?: Record<string, any>): Promise<CrudPage<T>> {
  const res = await request<CrudPage<T>>({ url: `/api/v1/admin/crud/${resource}`, method: 'get', params });
  return unwrap<CrudPage<T>>(res);
}

/** 详情 */
export async function crudDetail<T = any>(resource: string, id: number): Promise<T> {
  const res = await request<T>({ url: `/api/v1/admin/crud/${resource}/${id}`, method: 'get' });
  return unwrap<T>(res);
}

/** 新增 */
export async function crudCreate<T = any>(resource: string, data: Record<string, any>): Promise<T> {
  const res = await request<T>({ url: `/api/v1/admin/crud/${resource}`, method: 'post', data });
  return unwrap<T>(res);
}

/** 更新 */
export async function crudUpdate<T = any>(resource: string, id: number, data: Record<string, any>): Promise<T> {
  const res = await request<T>({ url: `/api/v1/admin/crud/${resource}/${id}`, method: 'put', data });
  return unwrap<T>(res);
}

/** 删除（后端为逻辑删除） */
export async function crudDelete(resource: string, id: number): Promise<void> {
  const res = await request<void>({ url: `/api/v1/admin/crud/${resource}/${id}`, method: 'delete' });
  return unwrap<void>(res);
}

/** 支持的资源清单（自检用） */
export async function crudResources(): Promise<string[]> {
  const res = await request<string[]>({ url: '/api/v1/admin/crud/meta/resources', method: 'get' });
  return unwrap<string[]>(res);
}

// ---------------- 主体绑定关系 ----------------

export async function bindInvestor(storeSubjectId: number, investorSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/investor/${investorSubjectId}`,
    method: 'post'
  });
  return unwrap<void>(res);
}

export async function unbindInvestor(storeSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/investor`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function bindChannelStore(channelSubjectId: number, storeSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/channel/${channelSubjectId}/store/${storeSubjectId}`,
    method: 'post'
  });
  return unwrap<void>(res);
}

export async function unbindChannelStore(channelSubjectId: number, storeSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/channel/${channelSubjectId}/store/${storeSubjectId}`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function bindSubjectUser(subjectId: number, userId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/subject/${subjectId}/user/${userId}`,
    method: 'post'
  });
  return unwrap<void>(res);
}

export async function unbindSubjectUser(subjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/subject/${subjectId}/user`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function bindUserRole(userId: number, roleCode: string, subjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/user/${userId}/role/${roleCode}/subject/${subjectId}`,
    method: 'post'
  });
  return unwrap<void>(res);
}

export async function unbindUserRole(userId: number, roleCode: string, subjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/user/${userId}/role/${roleCode}/subject/${subjectId}`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function reviewRoleApplication(
  id: number,
  approve: boolean,
  reason?: string,
  subjectId?: number
): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/application/${id}/review`,
    method: 'post',
    params: { approve, reason, subjectId }
  });
  return unwrap<void>(res);
}

export async function fetchRoleApplications(status?: string): Promise<any[]> {
  const res = await request<any[]>({
    url: '/api/v1/admin/subject/binding/applications',
    method: 'get',
    params: status ? { status } : {}
  });
  return unwrap<any[]>(res);
}

export async function fetchSubjectSummary(): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>({
    url: '/api/v1/admin/subject/binding/summary',
    method: 'get'
  });
  return unwrap<Record<string, number>>(res);
}

export async function fetchSubjectsByType(type: string): Promise<any[]> {
  const res = await request<any[]>({
    url: `/api/v1/admin/subject/binding/list/${type}`,
    method: 'get'
  });
  return unwrap<any[]>(res);
}


// ---------------- 营销配置特殊动作 ----------------

/** 启用/停用分账规则（后端会校验万分比合计 = 10000） */
export async function toggleSplitRule(id: number, enabled: boolean): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/marketing/config/split-rule/${id}/toggle`,
    method: 'post',
    params: { enabled }
  });
  return unwrap<void>(res);
}

/** 读取签到规则 */
export async function fetchSigninRule(): Promise<Record<string, any>> {
  const res = await request<Record<string, any>>({
    url: '/api/v1/admin/marketing/config/signin-rule',
    method: 'get'
  });
  return unwrap<Record<string, any>>(res);
}

/** 保存签到规则 */
export async function saveSigninRule(payload: Record<string, any>): Promise<void> {
  const res = await request<void>({
    url: '/api/v1/admin/marketing/config/signin-rule',
    method: 'post',
    data: payload
  });
  return unwrap<void>(res);
}

/** 读取邀请配置 */
export async function fetchReferralConfig(): Promise<Record<string, any>> {
  const res = await request<Record<string, any>>({
    url: '/api/v1/admin/marketing/config/referral-config',
    method: 'get'
  });
  return unwrap<Record<string, any>>(res);
}

/** 保存邀请配置 */
export async function saveReferralConfigApi(payload: Record<string, any>): Promise<void> {
  const res = await request<void>({
    url: '/api/v1/admin/marketing/config/referral-config',
    method: 'post',
    data: payload
  });
  return unwrap<void>(res);
}

/** 评论审核 */
export async function reviewComment(id: number, approve: boolean, reason?: string): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/marketing/config/comment/${id}/review`,
    method: 'post',
    params: { approve, reason }
  });
  return unwrap<void>(res);
}

// ---------------- 财务查询 ----------------

export async function fetchFinancePool(): Promise<any[]> {
  const res = await request<any[]>({ url: '/api/v1/admin/finance/pool', method: 'get' });
  return unwrap<any[]>(res);
}

export async function fetchFinanceFlows(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/finance/flows', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchFinanceSnapshots(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/finance/snapshots', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchFinanceReconcile(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/finance/reconcile', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchAccountsPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/finance/accounts/page', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function freezeAccount(subjectId: number, amount: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/finance/accounts/${subjectId}/freeze`,
    method: 'post',
    params: { amount }
  });
  return unwrap<void>(res);
}

export async function unfreezeAccount(subjectId: number, amount: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/finance/accounts/${subjectId}/unfreeze`,
    method: 'post',
    params: { amount }
  });
  return unwrap<void>(res);
}

// ---------------- 交易查询 ----------------

export async function fetchPayments(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/trade/payments', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchRefunds(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/trade/refunds', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchVerifyRecordsPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/trade/verify-records/page', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchVerifyPoolPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/trade/verify-pool/page', method: 'get', params });
  return unwrap<CrudPage>(res);
}

/** 执行核销（ORDER / EXCHANGE） */
export async function executeVerifyApi(payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/trade/verify', method: 'post', data: payload });
  return unwrap<any>(res);
}

/** 整单退款 */
export async function refundOrderApi(orderNo: string, reason?: string): Promise<any> {
  const res = await request<any>({
    url: '/api/v1/admin/trade/refund',
    method: 'post',
    params: { orderNo },
    data: { reason }
  });
  return unwrap<any>(res);
}

// ---------------- 授权 / 审计 ----------------

export async function fetchRolesPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/auth/roles', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchGrantsPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/auth/grants', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchWechatBindingsPage(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/auth/wechat', method: 'get', params });
  return unwrap<CrudPage>(res);
}

export async function fetchAuditLogs(params?: Record<string, any>): Promise<CrudPage> {
  const res = await request<CrudPage>({ url: '/api/v1/admin/auth/audit', method: 'get', params });
  return unwrap<CrudPage>(res);
}

