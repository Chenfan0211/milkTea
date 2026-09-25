import { request } from '../request';

/**
 * 后台账号与角色菜单权限接口。
 *
 * 对应后端 /api/v1/admin/auth/accounts 与 /api/v1/admin/auth/roles。
 *
 * 背景（2026-09-25 需求）：角色要绑定的是「可登录运营后台的账号」（手动新建），
 * 而不是小程序的 app_user。账号绑角色后，登录即拥有该角色的菜单权限。
 *
 * 说明：本文件所有函数失败时抛异常（与 crud.ts 的 unwrap 口径一致），
 * 由页面 try/catch 展示错误，不做静默降级。
 */

function unwrap<T>(result: any): T {
  if (result && typeof result === 'object' && 'error' in result) {
    if (result.error) {
      throw result.error;
    }
    return result.data as T;
  }
  return result as T;
}

export interface AdminAccount {
  id: number;
  username: string;
  nickName: string;
  status: number;
  isSuper: boolean;
  /** 是否可编辑（超管账号为 false，前端据此禁用按钮；真正的拦截在后端） */
  mutable: boolean;
  roleCodes: string[];
  roleNames: string[];
  createTime: string;
}

export interface AdminAccountPage {
  records: AdminAccount[];
  current: number;
  size: number;
  total: number;
}

export interface RoleOption {
  id: number;
  code: string;
  name: string;
  isBuiltin: boolean;
}

/** 账号分页列表 */
export async function fetchAdminAccounts(params?: Record<string, any>): Promise<AdminAccountPage> {
  const res = await request<AdminAccountPage>({
    url: '/api/v1/admin/auth/accounts',
    method: 'get',
    params
  });
  return unwrap<AdminAccountPage>(res);
}

/** 可绑定的角色选项（供账号表单的角色下拉） */
export async function fetchRoleOptions(): Promise<RoleOption[]> {
  const res = await request<RoleOption[]>({ url: '/api/v1/admin/auth/accounts/role-options', method: 'get' });
  return unwrap<RoleOption[]>(res);
}

/** 新建账号（用户名 + 初始密码 + 角色） */
export async function createAdminAccount(payload: {
  username: string;
  password: string;
  nickName?: string;
  roleCodes: string[];
}): Promise<AdminAccount> {
  const res = await request<AdminAccount>({
    url: '/api/v1/admin/auth/accounts',
    method: 'post',
    data: payload
  });
  return unwrap<AdminAccount>(res);
}

/** 编辑账号（仅昵称 / 状态；用户名不可改） */
export async function updateAdminAccount(
  id: number,
  payload: { nickName?: string; status?: number }
): Promise<AdminAccount> {
  const res = await request<AdminAccount>({
    url: `/api/v1/admin/auth/accounts/${id}`,
    method: 'put',
    data: payload
  });
  return unwrap<AdminAccount>(res);
}

/**
 * 重置账号密码。
 *
 * 这是唯一允许作用于超级管理员账号的写操作
 * （需求：「这个账号只能修改密码，也只能有超级管理员去修改」）。
 */
export async function resetAdminAccountPassword(id: number, password: string): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/auth/accounts/${id}/password`,
    method: 'put',
    data: { password }
  });
  return unwrap<void>(res);
}

/** 给账号分配角色（覆盖式） */
export async function assignAdminAccountRoles(id: number, roleCodes: string[]): Promise<AdminAccount> {
  const res = await request<AdminAccount>({
    url: `/api/v1/admin/auth/accounts/${id}/roles`,
    method: 'put',
    data: { roleCodes }
  });
  return unwrap<AdminAccount>(res);
}

/** 删除账号（逻辑删除，超管不可删） */
export async function deleteAdminAccount(id: number): Promise<void> {
  const res = await request<void>({ url: `/api/v1/admin/auth/accounts/${id}`, method: 'delete' });
  return unwrap<void>(res);
}

// ---------------- 角色 - 菜单权限 ----------------

export interface MenuNode {
  id: number;
  parentId: number;
  code: string;
  name: string;
  path: string;
  icon?: string;
  orderNum: number;
  type: string;
  featureFlag?: string;
  children?: MenuNode[];
}

export interface MenuTreeData {
  tree: MenuNode[];
  flat: MenuNode[];
}

/** 全量菜单树（权限树渲染用） */
export async function fetchMenuTree(): Promise<MenuTreeData> {
  const res = await request<MenuTreeData>({ url: '/api/v1/admin/auth/roles/menu-tree', method: 'get' });
  return unwrap<MenuTreeData>(res);
}

/** 某角色已勾选的菜单 code 列表 */
export async function fetchRoleMenus(roleId: number): Promise<string[]> {
  const res = await request<string[]>({ url: `/api/v1/admin/auth/roles/${roleId}/menus`, method: 'get' });
  return unwrap<string[]>(res);
}

/** 保存角色菜单权限（覆盖式） */
export async function saveRoleMenus(roleId: number, menuCodes: string[]): Promise<string[]> {
  const res = await request<string[]>({
    url: `/api/v1/admin/auth/roles/${roleId}/menus`,
    method: 'put',
    data: { menuCodes }
  });
  return unwrap<string[]>(res);
}
