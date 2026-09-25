import { request } from '../request';

/** 解包 flatRequest 结果（成功返回 data / 失败抛异常） */
function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) {
      throw result.error;
    }
    return result.data as T;
  }
  return result as T;
}

/** 后台订单列表（含商品明细 items，走 trade-service 专用接口） */
export async function fetchAdminOrders(params?: any) {
  const res = await request<any>({ url: '/api/v1/admin/trade/orders', params });
  return unwrap<Api.Common.PaginatingQueryRecord<Api.Admin.Order>>(res);
}

/** 后台订单详情（含商品明细 items） */
export async function fetchAdminOrderDetail(orderNo: string) {
  const res = await request<any>({ url: `/api/v1/admin/trade/orders/${orderNo}` });
  return unwrap<Api.Admin.Order>(res);
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

/**
 * 核销记录详情（单条，按 id）。
 *
 * 走 server 的 /admin/detail/verify/{id}：直接查库并 join 出门店名，
 * 避免详情页只依赖前端 store 内存（刷新/直接打开 URL 时会读到本地假数据）。
 */
export async function fetchAdminVerifyDetail(id: number) {
  const res = await request<any>({ url: `/api/v1/admin/detail/verify/${id}` });
  return unwrap<any>(res);
}
