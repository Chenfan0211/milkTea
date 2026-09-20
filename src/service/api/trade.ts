import { request } from '../request';

export function fetchAdminOrders(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Order>>({ url: '/api/v1/admin/trade/orders', params });
}

export function fetchAdminPayments(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Payment>>({ url: '/api/v1/admin/trade/payments', params });
}

export function fetchAdminRefunds(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Refund>>({ url: '/api/v1/admin/trade/refunds', params });
}

export function fetchAdminVerifyRecords(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.VerifyRecord>>({
    url: '/api/v1/admin/trade/verify-records',
    params
  });
}
