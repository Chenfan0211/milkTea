import { request } from '../request';

/**
 * Login
 *
 * @param userName User name
 * @param password Password
 */
export function fetchLogin(userName: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/login',
    method: 'post',
    data: {
      userName,
      password
    }
  });
}

/**
 * 快捷登录（一键免密）。
 *
 * 口令由服务端持有：前端只提交账号标识（super / operation / finance / audit），
 * 避免像旧版那样把弱口令硬编码进构建产物。
 * key 的白名单在服务端 QuickLoginController，传非白名单值会被拒绝。
 *
 * @param key 快捷账号标识
 */
export function fetchQuickLogin(key: string) {
  return request<Api.Auth.LoginToken>({
    url: `/auth/quick-login/${key}`,
    method: 'post'
  });
}

/** 快捷登录开关与可用账号（用于决定登录页是否渲染快捷入口） */
export function fetchQuickLoginConfig() {
  return request<{ enabled: boolean; accounts: string[] }>({
    url: '/auth/quick-login/enabled',
    method: 'get'
  });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/auth/getUserInfo' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
