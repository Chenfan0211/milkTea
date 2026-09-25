import { request } from '../request';

export function fetchAdminSnapshots(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.SplitSnapshot>>({
    url: '/api/v1/admin/finance/snapshots',
    params
  });
}

export function fetchAdminReconcileIssues(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.ReconcileIssue>>({
    url: '/api/v1/admin/finance/reconcile',
    params
  });
}

/** 分账快照详情（单条，按 id）。直接查库并聚合商品明细摘要，避免详情页读本地假数据。 */
export async function fetchAdminSnapshotDetail(id: number) {
  const res = await request<any>({ url: `/api/v1/admin/detail/snapshot/${id}` });
  return (res as any)?.data ?? res;
}

/** 角色申请详情（单条，按 id）。含申请人昵称与表单明细。 */
export async function fetchAdminRoleApplicationDetail(id: number) {
  const res = await request<any>({ url: `/api/v1/admin/detail/role-application/${id}` });
  return (res as any)?.data ?? res;
}
