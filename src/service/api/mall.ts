import { request } from '../request';

/** 解包 flatRequest 结果（成功返回 data / 失败抛异常）。 */
function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) throw result.error;
    return result.data as T;
  }
  return result as T;
}

export interface MallPageResult<T> {
  records: T[];
  current: number;
  size: number;
  total: number;
}

export type MallCategoryStatus = 'ENABLED' | 'DISABLED';

export interface MallCategory {
  id: number;
  code: string;
  name: string;
  iconImage?: string;
  sort: number;
  status: MallCategoryStatus;
  createTime?: string;
  updateTime?: string;
}

export interface MallCategoryPayload {
  code: string;
  name: string;
  iconImage?: string;
  sort: number;
  status: MallCategoryStatus;
}

export type MallProductStatus = 'DRAFT' | 'ON_SALE' | 'OFF_SALE';

export interface MallSkuAdmin {
  id?: number;
  skuCode?: string;
  specValue?: string;
  image?: string;
  salePrice?: number;
  originalPrice?: number;
  costPrice?: number;
  platformCommission?: number;
  distributionCommission?: number;
  supplierSubjectId?: number;
  supplierName?: string;
  stock?: number;
  lockedStock?: number;
  availableStock?: number;
  sort?: number;
  version?: number;
}

export interface MallProductAdmin {
  id: number;
  productCode: string;
  categoryId: number;
  categoryName?: string;
  name: string;
  subtitle?: string;
  specName?: string;
  coverImage?: string;
  galleryImages?: string[];
  description?: string;
  attributionStoreSubjectId?: number;
  attributionStoreName?: string;
  attributionChannelSubjectId?: number;
  attributionChannelName?: string;
  splitRuleId?: number;
  status: MallProductStatus;
  sort?: number;
  salesCount?: number;
  minPrice?: number;
  maxPrice?: number;
  availableStock?: number;
  deletable?: boolean;
  deleteBlockedReason?: string;
  createTime?: string;
  updateTime?: string;
  skus?: MallSkuAdmin[];
}

export interface MallProductPayload {
  productCode: string;
  categoryId: number;
  name: string;
  subtitle?: string;
  specName?: string;
  coverImage?: string;
  galleryImages?: string[];
  description?: string;
  attributionStoreSubjectId: number;
  attributionChannelSubjectId?: number;
  splitRuleId?: number;
  status: MallProductStatus;
  sort: number;
}

export interface MallSkuPayload {
  id?: number;
  skuCode: string;
  specValue: string;
  image?: string;
  salePrice: number;
  originalPrice: number;
  costPrice: number;
  platformCommission: number;
  distributionCommission: number;
  supplierSubjectId: number;
  stock: number;
  sort: number;
}

export interface MallProductQuery {
  page: number;
  size: number;
  categoryId?: number;
  status?: MallProductStatus;
  keyword?: string;
}

export interface MallStockAdjustPayload {
  stock: number;
  remark: string;
}

export type MallOrderStatus = 'CREATED' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELED' | 'REFUNDED';

export interface MallOrderShipping {
  recipientName?: string;
  phone?: string;
  provinceName?: string;
  cityName?: string;
  districtName?: string;
  detailAddress?: string;
  courierCompany?: string;
  trackingNo?: string;
}

export interface MallOrderItem {
  id?: number;
  productId?: string;
  name?: string;
  spec?: string;
  image?: string;
  unitPrice?: number;
  originalPrice?: number;
  quantity?: number;
  subTotal?: number;
  costPrice?: number;
  platformCommission?: number;
  distributionCommission?: number;
}

export interface MallOrder {
  id: number;
  orderNo: string;
  orderType: string;
  userId?: number;
  user?: string;
  store?: string;
  summary?: string;
  status: MallOrderStatus;
  payStatus?: string;
  payChannel?: string;
  totalAmount?: number;
  originalAmount?: number;
  discountAmount?: number;
  paidAmount?: number;
  couponDiscount?: number;
  storedValueDiscount?: number;
  refundStatus?: string;
  activeAfterSale?: boolean;
  afterSaleStatus?: MallAfterSaleStatus;
  afterSaleNo?: string;
  createTime?: string;
  payTime?: string;
  shippedTime?: string;
  autoCompleteTime?: string;
  completeTime?: string;
  items?: MallOrderItem[];
  shipping?: MallOrderShipping;
}

export interface MallOrderQuery {
  current: number;
  size: number;
  orderType: 'MALL';
  status?: MallOrderStatus;
  search?: string;
  startTime?: string;
  endTime?: string;
}

export interface MallShipPayload {
  courierCompany: string;
  trackingNo: string;
}

/** 商城分类：管理端返回完整的启用/停用分类列表。 */
export async function fetchMallCategories(): Promise<MallCategory[]> {
  const res = await request<MallCategory[]>({
    url: '/api/v1/admin/mall/categories',
    method: 'get'
  });
  return unwrap<MallCategory[]>(res);
}

export async function createMallCategory(payload: MallCategoryPayload): Promise<MallCategory> {
  const res = await request<MallCategory>({
    url: '/api/v1/admin/mall/categories',
    method: 'post',
    data: payload
  });
  return unwrap<MallCategory>(res);
}

export async function updateMallCategory(id: number, payload: MallCategoryPayload): Promise<MallCategory> {
  const res = await request<MallCategory>({
    url: `/api/v1/admin/mall/categories/${id}`,
    method: 'put',
    data: payload
  });
  return unwrap<MallCategory>(res);
}

export async function deleteMallCategory(id: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/mall/categories/${id}`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function fetchMallProducts(params: MallProductQuery): Promise<MallPageResult<MallProductAdmin>> {
  const res = await request<MallPageResult<MallProductAdmin>>({
    url: '/api/v1/admin/mall/products',
    method: 'get',
    params
  });
  return unwrap<MallPageResult<MallProductAdmin>>(res);
}

export async function fetchMallProduct(id: number): Promise<MallProductAdmin> {
  const res = await request<MallProductAdmin>({
    url: `/api/v1/admin/mall/products/${id}`,
    method: 'get'
  });
  return unwrap<MallProductAdmin>(res);
}

export async function createMallProduct(payload: MallProductPayload): Promise<MallProductAdmin> {
  const res = await request<MallProductAdmin>({
    url: '/api/v1/admin/mall/products',
    method: 'post',
    data: payload
  });
  return unwrap<MallProductAdmin>(res);
}

export async function updateMallProduct(id: number, payload: MallProductPayload): Promise<MallProductAdmin> {
  const res = await request<MallProductAdmin>({
    url: `/api/v1/admin/mall/products/${id}`,
    method: 'put',
    data: payload
  });
  return unwrap<MallProductAdmin>(res);
}

export async function updateMallProductStatus(id: number, status: MallProductStatus): Promise<MallProductAdmin> {
  const res = await request<MallProductAdmin>({
    url: `/api/v1/admin/mall/products/${id}/status`,
    method: 'patch',
    data: { status }
  });
  return unwrap<MallProductAdmin>(res);
}

export async function deleteMallProduct(id: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/mall/products/${id}`,
    method: 'delete'
  });
  return unwrap<void>(res);
}

export async function saveMallProductSkus(id: number, skus: MallSkuPayload[]): Promise<MallSkuAdmin[]> {
  const res = await request<MallSkuAdmin[]>({
    url: `/api/v1/admin/mall/products/${id}/skus`,
    method: 'put',
    data: skus
  });
  return unwrap<MallSkuAdmin[]>(res);
}

export async function adjustMallSkuStock(
  id: number,
  skuId: number,
  payload: MallStockAdjustPayload
): Promise<MallSkuAdmin> {
  const { stock, remark } = payload;
  const res = await request<MallSkuAdmin>({
    url: `/api/v1/admin/mall/products/${id}/skus/${skuId}/stock`,
    method: 'patch',
    data: { stock, remark }
  });
  return unwrap<MallSkuAdmin>(res);
}

export async function fetchMallOrders(params: MallOrderQuery): Promise<MallPageResult<MallOrder>> {
  const res = await request<MallPageResult<MallOrder>>({
    url: '/api/v1/admin/trade/orders',
    method: 'get',
    params
  });
  return unwrap<MallPageResult<MallOrder>>(res);
}

export async function shipMallOrder(orderNo: string, payload: MallShipPayload): Promise<MallOrder> {
  const res = await request<MallOrder>({
    url: `/api/v1/admin/trade/orders/${orderNo}/ship`,
    method: 'post',
    data: payload
  });
  return unwrap<MallOrder>(res);
}
export type MallAfterSaleStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REFUNDING' | 'SUCCESS' | 'FAILED';

export interface MallAfterSaleItem {
  orderItemId?: number;
  skuId?: number;
  skuCode?: string;
  name?: string;
  spec?: string;
  image?: string;
  quantity?: number;
  unitPrice?: number;
  distributionCommission?: number;
  lineAmount?: number;
}

export interface MallAfterSale {
  id: number;
  afterSaleNo: string;
  orderId: number;
  orderNo: string;
  userId?: number;
  userNick?: string;
  status: MallAfterSaleStatus;
  reason?: string;
  refundAmount?: number;
  items?: MallAfterSaleItem[];
  applyTime?: string;
  reviewTime?: string;
  reviewer?: string;
  completeTime?: string;
  refundId?: number;
  refundNo?: string;
  refundStatus?: string;
  refundFailReason?: string;
}

export interface MallAfterSaleQuery {
  current: number;
  size: number;
  status?: MallAfterSaleStatus;
  search?: string;
}

export interface MallAfterSaleReviewPayload {
  remark?: string;
}

export async function fetchMallAfterSales(params: MallAfterSaleQuery): Promise<MallPageResult<MallAfterSale>> {
  const res = await request<MallPageResult<MallAfterSale>>({
    url: '/api/v1/admin/trade/after-sales',
    method: 'get',
    params
  });
  return unwrap<MallPageResult<MallAfterSale>>(res);
}

export async function fetchMallAfterSale(afterSaleNo: string): Promise<MallAfterSale> {
  const res = await request<MallAfterSale>({
    url: `/api/v1/admin/trade/after-sales/${encodeURIComponent(afterSaleNo)}`,
    method: 'get'
  });
  return unwrap<MallAfterSale>(res);
}

export async function approveMallAfterSale(
  afterSaleNo: string,
  payload: MallAfterSaleReviewPayload = {}
): Promise<MallAfterSale> {
  const res = await request<MallAfterSale>({
    url: `/api/v1/admin/trade/after-sales/${encodeURIComponent(afterSaleNo)}/approve`,
    method: 'post',
    data: payload
  });
  return unwrap<MallAfterSale>(res);
}

export async function rejectMallAfterSale(
  afterSaleNo: string,
  payload: MallAfterSaleReviewPayload = {}
): Promise<MallAfterSale> {
  const res = await request<MallAfterSale>({
    url: `/api/v1/admin/trade/after-sales/${encodeURIComponent(afterSaleNo)}/reject`,
    method: 'post',
    data: payload
  });
  return unwrap<MallAfterSale>(res);
}
