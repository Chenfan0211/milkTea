import { request } from '../request';

export function fetchAdminAuditLogs(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.AuditLog>>({ url: '/api/v1/admin/system/audit', params });
}

export function fetchAdminFeatureFlags(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.FeatureFlag>>({
    url: '/api/v1/admin/system/features',
    params
  });
}
