import { request } from '../request';

/**
 * 茶机设备（慧养智萃茶机队列对接）后台接口。
 *
 * 设备列表 / 绑定 / 解绑走专用接口，不走通用 CRUD：
 *   · 列表要 join 门店名（设备表只存 store_subject_id，门店改名后名字要实时生效）
 *     并支持「未绑定」这类 IS NULL 条件，通用 CRUD 的单表 like/eq 表达不了；
 *   · 绑定/换绑要在同一事务内写「绑定历史 + 当前归属」，通用 CRUD 只写单表。
 * 冲泡记录 / 状态流水是设备上报的事实数据，后台只读，走通用 CRUD 即可。
 */

function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) throw result.error;
    return result.data as T;
  }
  return result as T;
}

/** 新增/编辑设备入参（在线状态由队列维护，不在表单内） */
export interface DeviceUpsertPayload {
  deviceSn: string;
  deviceName: string;
  alias?: string | null;
  remark?: string | null;
  enabled?: number | null;
}

export interface DeviceImportResult {
  accepted: boolean;
  total: number;
  imported: number;
  errors: string[];
}

export interface DevicePageParams {
  current: number;
  size: number;
  /** 设备编码 / 设备ID / 别名 模糊匹配 */
  keyword?: string;
  storeSubjectId?: number;
  /** bound=已绑定 / unbound=未绑定 / 不传=全部 */
  bindStatus?: string;
  onlineStatus?: string;
  enabled?: number;
}

/**
 * MNS 队列自检结果。
 *
 * 用途：区分「对方没推」与「我们没收到」——两者在数据上都表现为「页面空」。
 * ok=false 时 error 会带原因（未启用 / 队列不存在 / 权限 / 网络超时）。
 */
export interface DeviceQueueStatus {
  enabled: boolean;
  running: boolean;
  connected: boolean;
  queueName?: string | null;
  endpoint?: string | null;
  ok: boolean;
  cached: boolean;
  error?: string | null;
  lastCheckTime?: string | null;
  activeMessages?: number | null;
  inactiveMessages?: number | null;
  delayMessages?: number | null;
  lastPollTime?: string | null;
  lastMessageTime?: string | null;
  forwardedCount?: number;
  failedCount?: number;
  lastError?: string | null;
}

/** MNS 队列自检（refresh=true 时强制实时查询，否则走后端 10 分钟缓存） */
export async function fetchDeviceQueueStatus(refresh = false): Promise<DeviceQueueStatus> {
  const res = await request<DeviceQueueStatus>({
    url: '/api/v1/admin/device/queue-status',
    method: 'get',
    params: refresh ? { refresh: 1 } : {}
  });
  return unwrap<DeviceQueueStatus>(res);
}

/** 设备台账分页（含门店名、绑定状态筛选） */
export async function fetchAdminDevices(params: DevicePageParams) {
  const res = await request<Api.Common.PaginatingQueryRecord<Api.Admin.Device>>({
    url: '/api/v1/admin/device/page',
    method: 'get',
    params
  });
  return unwrap<Api.Common.PaginatingQueryRecord<Api.Admin.Device>>(res);
}

/** 新增设备（手工建档，设备后续上报会自动落到该台账） */
export async function createAdminDevice(payload: DeviceUpsertPayload) {
  const res = await request<Api.Admin.Device>({
    url: '/api/v1/admin/device',
    method: 'post',
    data: payload
  });
  return unwrap<Api.Admin.Device>(res);
}

/** 批量导入设备：后端整批校验通过才写入；errors 为逐行错误。 */
export async function importAdminDevices(rows: DeviceUpsertPayload[]): Promise<DeviceImportResult> {
  const res = await request<DeviceImportResult>({
    url: '/api/v1/admin/device/import',
    method: 'post',
    data: rows
  });
  return unwrap<DeviceImportResult>(res);
}

/** 编辑设备（设备编码可改，但必须保持唯一） */
export async function updateAdminDevice(id: number, payload: DeviceUpsertPayload) {
  const res = await request<Api.Admin.Device>({
    url: `/api/v1/admin/device/${id}`,
    method: 'put',
    data: payload
  });
  return unwrap<Api.Admin.Device>(res);
}

/** 绑定/换绑门店：换绑时后端会先补齐旧门店的解绑历史 */
export async function bindDeviceStore(id: number, storeSubjectId: number, remark?: string): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/device/${id}/bind`,
    method: 'post',
    data: { storeSubjectId, remark }
  });
  return unwrap<void>(res);
}

/** 解绑门店：设备回到「未绑定」，之后的上报不再归属门店 */
export async function unbindDeviceStore(id: number, reason?: string): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/device/${id}/unbind`,
    method: 'post',
    data: { reason }
  });
  return unwrap<void>(res);
}

/** 删除设备台账（软删；需先解绑门店。删除后同 SN 的上报不会再自动重建台账） */
export async function deleteAdminDevice(id: number, reason?: string): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/device/${id}`,
    method: 'delete',
    params: { reason }
  });
  return unwrap<void>(res);
}
/** 绑定历史（设备行操作弹层，新→旧） */
export async function fetchDeviceBindings(id: number): Promise<Api.Admin.DeviceBinding[]> {
  const res = await request<Api.Admin.DeviceBinding[]>({
    url: `/api/v1/admin/device/${id}/bindings`,
    method: 'get'
  });
  return unwrap<Api.Admin.DeviceBinding[]>(res);
}
