<script setup lang="ts">

defineOptions({
  name: 'auth_wechat'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

/**
 * 微信账号绑定。
 *
 * 字段口径对齐 app_user 表（真实列名）：open_id=微信openId、nick_name=昵称。
 *
 * 历史问题：
 *  页原用 openId / userId —— 库中是 open_id，且**没有 userId 列**（用户主键就是 id），
 *  故 openId 改名为 open_id，userId 表单项移除（用户 id 由系统分配，不应手填）。
 */

const columns: DataTableColumns<any> = [
  { title: '用户ID', key: 'id', width: 90 },
  { title: 'openId', key: 'openId', minWidth: 180 },
  { title: '微信昵称', key: 'nickName', width: 140 },
  {
    title: '经营角色',
    key: 'businessRole',
    width: 120,
    render: (row: any) =>
      row.businessRole
        ? ({ store: '门店', investor: '投资人', resource: '资源方' } as Record<string, string>)[row.businessRole]
        : '未绑定'
  },
  { title: '绑定状态', key: 'businessRole', width: 110, render: (row: any) => (row.businessRole ? '已绑定' : '未绑定') }
];
const searchFields: SearchField[] = [
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'openId', label: 'openId', placeholder: 'openId' }
];
const toolbar: RowAction[] = [{ label: '绑定微信账号', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '查看角色',
    type: 'info',
    handler: row => window.$message?.info(row.businessRole ? `角色：${row.businessRole}` : '未绑定角色')
  },
  {
    label: '解绑',
    type: 'error',
    reasonPrompt: '解绑后旧角色会话立即失效，确认解绑？（请填写备注）',
    handler: (row, reason) => store.unbindUserRole(row.id, reason)
  }
];
const formFields: FormField[] = [
  { key: 'nickName', label: '昵称' },
  { key: 'openId', label: 'openId', rules: [{ required: true, message: '请输入 openId', trigger: ['input', 'blur'] }] }
];
const config: AdminListConfig = {
  title: '微信账号绑定',
  remoteKey: 'users',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('users', search, page, pageSize),
  form: {
    title: '微信账号',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (editing) await store.update('users', editing.id, data, '用户管理', 'nickName');
      else await store.add('users', data, '用户管理', 'nickName');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
