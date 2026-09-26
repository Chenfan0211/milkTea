<script setup lang="ts">
defineOptions({
  name: 'marketing_points-category'
});

import { h } from 'vue';
import { NTag } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

function isEnabled(value: any): boolean {
  return value === true || Number(value) === 1 || value === 'enabled';
}

function isSystemLocked(row: any): boolean {
  return Number(row?.systemLocked) === 1 || String(row?.code || '') === 'coupon';
}

const columns: DataTableColumns<any> = [
  { title: '分类名称', key: 'name', minWidth: 180 },
  { title: '分类编码', key: 'code', minWidth: 180 },
  { title: '排序', key: 'sort', width: 90, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    render: (row: any) =>
      h(NTag, { type: isEnabled(row.enabled) ? 'success' : 'default', bordered: false }, { default: () => (isEnabled(row.enabled) ? '启用' : '停用') })
  },
  {
    title: '类型',
    key: 'systemLocked',
    width: 110,
    render: (row: any) =>
      isSystemLocked(row)
        ? h(NTag, { type: 'warning', bordered: false }, { default: () => '系统分类' })
        : h('span', { style: 'color:#9B9B96' }, '自定义')
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '分类名称', placeholder: '分类名称' },
  { key: 'code', label: '分类编码', placeholder: '分类编码' },
  {
    key: 'eq_enabled',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: '1' },
      { label: '停用', value: '0' }
    ]
  }
];

const formFields: FormField[] = [
  {
    key: 'name',
    label: '分类名称',
    rules: [{ required: true, message: '请输入分类名称', trigger: ['input', 'blur'] }],
  },
  {
    key: 'code',
    label: '分类编码',
    rules: [{ required: true, message: '请输入分类编码', trigger: ['input', 'blur'] }],
    placeholder: '商品关联键，例如 pet',
    disabled: form => Boolean(form.isEdit),
  },
  { key: 'sort', label: '排序', type: 'number', placeholder: '数字越小越靠前' },
  {
    key: 'enabled',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: '1' },
      { label: '停用', value: '0' }
    ]
  }
];

const toolbar: RowAction[] = [{ label: '新增分类', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该积分商城分类？（请填写备注）',
    visible: row => !isSystemLocked(row),
    handler: async (row, reason) => await store.remove('pointsCategories', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '积分商城分类',
  remoteKey: 'pointsCategories',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('pointsCategories', search, page, pageSize),
  form: {
    title: '积分商城分类',
    fields: formFields,
    toFormData: (row: any) => ({
      name: row.name ?? '',
      code: row.code ?? '',
      sort: row.sort == null ? 0 : Number(row.sort),
      enabled: isEnabled(row.enabled) ? '1' : '0',
      isEdit: true
    }),
    onSubmit: async (data, editing) => {
      const name = String(data.name ?? '').trim();
      const code = String(data.code ?? '').trim();
      const sort = Number(data.sort) || 0;
      const enabled = Number(data.enabled) === 1 ? 1 : 0;
      if (!name) throw new Error('请输入分类名称');
      if (editing) {
        const payload = { name, sort, enabled };
        await store.update('pointsCategories', editing.id, payload, '营销中心', 'name');
        return;
      }

      if (!code) throw new Error('请输入分类编码');
      if (code === 'all') throw new Error('分类编码 all 为保留值，不能使用');
      await store.add('pointsCategories', { name, code, sort, enabled }, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>