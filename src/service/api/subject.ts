import { request } from '../request';

function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) throw result.error;
    return result.data as T;
  }
  return result as T;
}

export function fetchSubjectPlatforms(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Platform>>({
    url: '/api/v1/admin/subject/platforms',
    params
  });
}

export async function fetchSubjectStores(params?: any) {
  const res = await request<Api.Common.PaginatingQueryRecord<Api.Admin.Store>>({ url: '/api/v1/admin/subject/stores', params });
  return unwrap<Api.Common.PaginatingQueryRecord<Api.Admin.Store>>(res);
}

export async function createSubjectStore(payload: Record<string, any>) {
  const res = await request<Api.Admin.Store>({ url: '/api/v1/admin/subject/stores', method: 'post', data: payload });
  return unwrap<Api.Admin.Store>(res);
}

export async function updateSubjectStore(id: number, payload: Record<string, any>) {
  const res = await request<Api.Admin.Store>({ url: `/api/v1/admin/subject/stores/${id}`, method: 'put', data: payload });
  return unwrap<Api.Admin.Store>(res);
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

export async function bindStoreInvestor(storeSubjectId: number, investorSubjectId: number): Promise<void> {
  const res = await request<void>({ url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/investor/${investorSubjectId}`, method: 'post' });
  return unwrap<void>(res);
}

export async function unbindStoreInvestor(storeSubjectId: number): Promise<void> {
  const res = await request<void>({ url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/investor`, method: 'delete' });
  return unwrap<void>(res);
}

// ---------------- 主体档案聚合接口（渠道/供应商/投资人） ----------------

export async function fetchSubjectChannelsPage(params?: any): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/channels', method: 'get', params });
  return (res as any)?.data ?? res;
}

export async function createSubjectChannel(payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/channels', method: 'post', data: payload });
  return (res as any)?.data ?? res;
}

export async function updateSubjectChannel(id: number, payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: `/api/v1/admin/subject/channels/${id}`, method: 'put', data: payload });
  return (res as any)?.data ?? res;
}

export async function fetchSubjectSuppliersPage(params?: any): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/suppliers', method: 'get', params });
  return (res as any)?.data ?? res;
}

export async function createSubjectSupplier(payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/suppliers', method: 'post', data: payload });
  return (res as any)?.data ?? res;
}

export async function updateSubjectSupplier(id: number, payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: `/api/v1/admin/subject/suppliers/${id}`, method: 'put', data: payload });
  return (res as any)?.data ?? res;
}

export async function fetchSubjectInvestorsPage(params?: any): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/investors', method: 'get', params });
  return (res as any)?.data ?? res;
}

export async function createSubjectInvestor(payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: '/api/v1/admin/subject/investors', method: 'post', data: payload });
  return (res as any)?.data ?? res;
}

export async function updateSubjectInvestor(id: number, payload: Record<string, any>): Promise<any> {
  const res = await request<any>({ url: `/api/v1/admin/subject/investors/${id}`, method: 'put', data: payload });
  return (res as any)?.data ?? res;
}
