import { request } from '../request';

export function fetchAdminRoles(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.Role>>({ url: '/api/v1/admin/auth/roles', params });
}

export function fetchAdminWechatBindings(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.WechatBinding>>({
    url: '/api/v1/admin/auth/wechat',
    params
  });
}

export function fetchAdminRoleGrants(params?: any) {
  return request<Api.Common.PaginatingQueryRecord<Api.Admin.RoleGrant>>({ url: '/api/v1/admin/auth/grants', params });
}
