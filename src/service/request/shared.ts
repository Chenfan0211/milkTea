import { useAuthStore } from '@/store/modules/auth';
import { localStg } from '@/utils/storage';
import { fetchRefreshToken } from '../api';
import type { RequestInstanceState } from './type';

export function getAuthorization() {
  const token = localStg.get('token');
  const Authorization = token ? `Bearer ${token}` : null;

  return Authorization;
}

/**
 * 读取后端错误信息。
 *
 * 后端 `Result` 的字段名是 `message`，而模板早期按 `msg` 读取，
 * 导致错误信息取到 undefined、中文提示无法展示。这里做兼容：优先 `message`，回退 `msg`。
 */
export function getBackendMessage(data: any): string {
  return data?.message || data?.msg || '';
}

export function toChineseError(message?: string | null): string {
  const raw = String(message || '').trim();
  if (!raw) return '请求失败，请稍后重试';
  if (/[\u4e00-\u9fa5]/.test(raw)) return raw;

  const lower = raw.toLowerCase();
  if (lower.includes('network error')) return '网络连接失败，请检查网络';
  if (lower.includes('timeout') || lower.includes('timed out')) return '请求超时，请稍后重试';
  if (lower.includes('canceled') || lower.includes('cancelled')) return '请求已取消';
  if (lower.includes('status code 500')) return '服务异常，请稍后重试';
  if (lower.includes('status code 502') || lower.includes('status code 503') || lower.includes('status code 504')) return '服务暂时不可用，请稍后重试';
  if (lower.includes('status code 404')) return '请求的资源不存在';
  if (lower.includes('status code 401')) return '登录已过期，请重新登录';
  if (lower.includes('status code 403')) return '没有权限执行该操作';
  if (lower.includes('status code 400')) return '请求参数有误';
  return raw;
}

/** refresh token */
async function handleRefreshToken() {
  const { resetStore } = useAuthStore();

  const rToken = localStg.get('refreshToken') || '';
  const { error, data } = await fetchRefreshToken(rToken);
  if (!error) {
    localStg.set('token', data.token);
    localStg.set('refreshToken', data.refreshToken);
    return true;
  }

  resetStore();

  return false;
}

export async function handleExpiredRequest(state: RequestInstanceState) {
  if (!state.refreshTokenPromise) {
    state.refreshTokenPromise = handleRefreshToken();
  }

  const success = await state.refreshTokenPromise;

  setTimeout(() => {
    state.refreshTokenPromise = null;
  }, 1000);

  return success;
}

/**
 * 展示后端错误信息（水平居中弹窗）。
 *
 * 需求：登录失败等错误要以「水平居中弹窗」形式提示，并展示中文错误信息。
 *
 * 实现要点：
 * - 用 `window.$dialog.error` 而非 `$message`：`$message` 是右上角轻提示，位置不居中；
 * - `closable: false` + 无取消按钮，避免用户忽略后误以为已处理；
 * - 相同文案在短时间内去重，防止并发请求弹出多个重复弹窗。
 */
export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const text = message || '请求失败，请稍后重试';

  const isExist = state.errMsgStack.includes(text);

  if (!isExist) {
    state.errMsgStack.push(text);

    window.$dialog?.error({
      title: '操作失败',
      content: text,
      // 弹窗水平居中 + 垂直居中
      class: 'error-dialog-center',
      style: { textAlign: 'center' },
      maskClosable: false,
      closable: false,
      positiveText: '我知道了',
      onPositiveClick() {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== text);
      },
      onClose() {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== text);
      }
    });
  }
}