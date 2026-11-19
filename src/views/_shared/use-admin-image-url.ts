import { getServiceBaseURL } from '@/utils/service';
import { resolveAdminImageUrl } from './image-url';

export const ADMIN_IMAGE_FALLBACK = '/assets/images/3x/mall-category-placeholder.jpg';

/** 后台运行时图片地址解析：接口文件和后台静态素材分别使用各自来源。 */
export function useAdminImageUrl() {
  const isProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isProxy);
  return (value: string | null | undefined) => resolveAdminImageUrl(value, baseURL);
}
