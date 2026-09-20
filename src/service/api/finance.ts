import { request } from '../request';

export function fetchAdminSnapshots(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.SplitSnapshot>>({
    url: '/api/v1/admin/finance/snapshots',
    params
  });
}

export function fetchAdminSplitExecutions(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.SplitExecute>>({
    url: '/api/v1/admin/finance/executions',
    params
  });
}

export function fetchAdminLedgers(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Ledger>>({ url: '/api/v1/admin/finance/ledgers', params });
}

export function fetchAdminReconcileIssues(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.ReconcileIssue>>({
    url: '/api/v1/admin/finance/reconcile',
    params
  });
}
