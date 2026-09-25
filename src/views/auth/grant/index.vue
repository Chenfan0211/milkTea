<script setup lang="ts">
defineOptions({
  name: 'auth_grant'
});

import { h } from 'vue';
import { NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import { renderDateTime } from '@/views/_shared/render';
import { fetchAdminRoleGrants } from '@/service/api/auth_admin';

/**
 * 角色授权记录（运营后台「授权中心 / 角色授权记录」）。
 *
 * 需求口径（2026-09-25）：角色绑定的是**后台登录账号**，
 * 因此本页展示「哪个后台账号被授予了哪个后台角色」，只读。
 *
 * 历史口径：本页原先读 user_role_grant —— 那是**小程序用户**的经营角色授权
 * （门店/投资人/资源方），与「可登录运营后台的账号」是两套体系，
 * 混在同名菜单下造成语义错位。后端 /admin/auth/grants 已同步切换为 sys_user_role。
 *
 * 为什么是只读：授权的增删改入口在「账号管理」（分配角色）与
 * 「角色与权限」（分配菜单）两处，本页只做留痕查阅，
 * 避免同一份关系有多个写入口导致互相覆盖。
 */

const columns: DataTableColumns<any> = [
  { title: '账号', key: 'username', width: 140 },
  { title: '昵称', key: 'nickName', width: 140, render: (row: any) => row.nickName || '—' },
  {
    title: '角色',
    key: 'roleName',
    minWidth: 160,
    render: (row: any) =>
      h(
        'div',
        { style: 'display:flex;align-items:center;gap:6px' },
        [
          h('span', null, row.roleName || row.roleCode || '—'),
          row.isBuiltin
            ? h(NTag, { size: 'small', type: 'warning' }, { default: () => '内置' })
            : null
        ].filter(Boolean) as any
      )
  },
  { title: '角色编码', key: 'roleCode', width: 130, render: (row: any) => row.roleCode || '—' },
  {
    title: '账号状态',
    key: 'status',
    width: 100,
    render: (row: any) =>
      row.status === 0
        ? h(NTag, { size: 'small', type: 'error' }, { default: () => '已停用' })
        : h(NTag, { size: 'small', type: 'success' }, { default: () => '正常' })
  },
  { title: '授权时间', key: 'createTime', render: renderDateTime('createTime'), width: 160 }
];

const searchFields: SearchField[] = [
  { key: 'username', label: '账号', placeholder: '登录用户名' },
  { key: 'roleCode', label: '角色编码', placeholder: '如 R_OPERATION' }
];

const config: AdminListConfig = {
  title: '角色授权记录',
  columns,
  searchFields,
  loadData: async ({ page, pageSize, search }) => {
    const res: any = await fetchAdminRoleGrants({ current: page, size: pageSize, ...search });
    const records = res?.records ?? res?.data?.records ?? [];
    const total = res?.total ?? res?.data?.total ?? 0;
    return { data: records, total };
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
