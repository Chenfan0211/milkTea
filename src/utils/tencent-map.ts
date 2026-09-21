import { request } from '@/service/request';

/**
 * 地址解析（经纬度）。
 *
 * 实现说明：
 *   腾讯位置服务的 Key / SecretKey 已移至服务端 app_config.tencent_map_key，
 *   由后端代理调用（POST /api/v1/admin/geo/geocode），前端只提交地址、
 *   只接收经纬度。密钥不再随构建产物下发，避免被冒用配额。
 *
 *   原先在前端实现的 SN 签名（md5 + SK 拼接）已随密钥一并迁移到后端，
 *   前端不再需要也不应持有密钥。
 */

export interface GeoResult {
  latitude: number;
  longitude: number;
}

/**
 * 将地址解析为经纬度。
 *
 * @param address 结构化地址
 * @returns 解析结果；地址为空返回 null；接口失败抛出异常由调用方提示
 */
export async function geocodeAddress(address: string): Promise<GeoResult | null> {
  const trimmed = String(address || '').trim();
  if (!trimmed) return null;

  const result = await request<GeoResult>({
    url: '/api/v1/admin/geo/geocode',
    method: 'POST',
    data: { address: trimmed }
  });

  // flatRequest 失败时 error 非空，与项目其他调用保持一致地抛出，交由调用方提示
  if (result.error) {
    throw result.error;
  }

  const data = result.data as GeoResult | null | undefined;
  if (!data) return null;

  const latitude = Number(data.latitude);
  const longitude = Number(data.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;

  return { latitude, longitude };
}