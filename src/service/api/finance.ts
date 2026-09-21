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
