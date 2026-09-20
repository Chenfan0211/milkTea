import { request } from '../request';

export function fetchSubjectPlatforms(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Platform>>({
    url: '/api/v1/admin/subject/platforms',
    params
  });
}

export function fetchSubjectStores(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Store>>({ url: '/api/v1/admin/subject/stores', params });
}

export function fetchSubjectChannels(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Channel>>({
    url: '/api/v1/admin/subject/channels',
    params
  });
}

export function fetchSubjectInvestors(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Investor>>({
    url: '/api/v1/admin/subject/investors',
    params
  });
}

export function fetchSubjectSuppliers(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Supplier>>({
    url: '/api/v1/admin/subject/suppliers',
    params
  });
}
