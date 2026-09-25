import { request } from '../request';

/**
 * 管理端商品接口（走 product-service，非通用 CRUD）。
 *
 * 路径与通用 CRUD 不同（/api/v1/admin/product/**），
 * 且返回字段由后端组装：category（分类名）、stores（门店数组）、
 * specCount、costPrice / platformCommission（单位：分）等。
 *
 * 底层 request 为 flatRequest，成功返回 { data, error: null }、失败 { data: null, error }，
 * 这里统一解包为「成功返回数据 / 失败抛异常」。
 */

export interface AdminProductRow {
  id: number;
  productId?: string;
  code?: string;
  name?: string;
  category?: string;
  categoryId?: number;
  specCount?: number;
  price?: number;
  originalPrice?: number;
  costPrice?: number;
  platformCommission?: number;
  description?: string;
  store?: string;
  stores?: string[];
  onSale?: string;
  splitReady?: string;
  createTime?: string;
}

function unwrap<T>(result: any): T {
  // flatRequest 返回 { data, error, response }（成功时 error 为 null）
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) {
      throw result.error;
    }
    return result.data as T;
  }
  return result as T;
}

/** 商品分页列表 */
export async function fetchProductsPage(params?: Record<string, any>): Promise<{
  records: AdminProductRow[];
  current: number;
  size: number;
  total: number;
}> {
  const res = await request<any>({ url: '/api/v1/admin/product/list', method: 'get', params });
  return unwrap(res);
}

/** 商品分类（含 TAB / GROUP / CATEGORY 层级） */
export async function fetchProductCategories(): Promise<any[]> {
  const res = await request<any[]>({ url: '/api/v1/admin/product/categories', method: 'get' });
  return unwrap(res);
}

/** 新增商品 */
export async function createProduct(payload: Record<string, any>): Promise<AdminProductRow> {
  const res = await request<AdminProductRow>({ url: '/api/v1/admin/product/create', method: 'post', data: payload });
  return unwrap(res);
}

/** 编辑商品 */
export async function updateProduct(id: number, payload: Record<string, any>): Promise<AdminProductRow> {
  const res = await request<AdminProductRow>({ url: `/api/v1/admin/product/${id}`, method: 'put', data: payload });
  return unwrap(res);
}

/** 上下架：onSale = on / off */
export async function updateProductOnSale(id: number, onSale: string): Promise<AdminProductRow> {
  const res = await request<AdminProductRow>({
    url: `/api/v1/admin/product/${id}/on-sale`,
    method: 'patch',
    params: { onSale }
  });
  return unwrap(res);
}

/** 读取商品规格组 */
export async function fetchSpecGroups(id: number): Promise<{ groups: any[] }> {
  const res = await request<{ groups: any[] }>({
    url: `/api/v1/admin/product/${id}/spec-groups`,
    method: 'get'
  });
  return unwrap(res);
}

/** 保存商品规格组（整体替换） */
export async function saveSpecGroups(id: number, groups: any[]): Promise<{ groups: any[] }> {
  const res = await request<{ groups: any[] }>({
    url: `/api/v1/admin/product/${id}/spec-groups`,
    method: 'put',
    data: { groups }
  });
  return unwrap(res);
}

/** 读取商品已关联的门店主体 id 列表 */
export async function fetchProductStores(id: number): Promise<number[]> {
  const res = await request<number[]>({ url: `/api/v1/admin/product/${id}/stores`, method: 'get' });
  return unwrap(res);
}

/** 保存商品的门店关联（整体替换） */
export async function saveProductStores(id: number, storeSubjectIds: number[]): Promise<number[]> {
  const res = await request<number[]>({
    url: `/api/v1/admin/product/${id}/stores`,
    method: 'put',
    data: storeSubjectIds
  });
  return unwrap(res);
}

/** 逻辑删除 */
export async function deleteProduct(id: number): Promise<void> {
  const res = await request<void>({ url: `/api/v1/admin/product/${id}`, method: 'delete' });
  return unwrap(res);
}
