import { request } from '../request';
import { crudPage } from './crud';

export function fetchAdminProducts(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Product>>({ url: '/api/v1/admin/product/list', params });
}

export function fetchAdminSpecs(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Spec>>({ url: '/api/v1/admin/product/specs', params });
}

export function fetchAdminSplitRules(params?: any) {
  return crudPage<Api.Admin.SplitRule>('splitRules', params);
}
