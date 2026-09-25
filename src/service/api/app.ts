import { request } from '../request';

/**
 * 小程序端拉取门店类型字典（仅返回启用项，按 sort 升序）。
 * 后端就绪后，该接口由后端提供；字段契约见 docs/data-schema.md。
 */
export function fetchAppStoreTypes() {
  return request<Api.App.StoreType[]>({ url: '/api/v1/app/store-types', method: 'GET' });
}
