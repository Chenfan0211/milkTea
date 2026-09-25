<script setup lang="ts">
defineOptions({
  name: 'subject_supplier'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { createSubjectSupplier, fetchSubjectSuppliersPage, updateSubjectSupplier } from '@/service/api/subject';

const store = useAdminStore();

const load = async (p: any) => {
  const keyword = String(p.search?.name || '').trim();
  const status = String(p.search?.status || '').trim();
  const page = await fetchSubjectSuppliersPage({
    current: p.page,
    size: p.pageSize,
    search: keyword || undefined,
    status: status || undefined
  });
  return { data: page?.records || [], total: page?.total || 0 };
};

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  {
    title: '可提现余额(元)',
    key: 'balance',
    width: 130,
    align: 'right',
    render: (row: any) => {
      const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id);
      return (acc ? (acc.availableBalance ?? 0) : 0).toFixed(2);
    }
  },
  { title: '关联商品数', key: 'productCount', width: 120, align: 'right' },
  { title: '联系人', key: 'contactName', width: 110, render: (row: any) => row.contactName || '—' },
  { title: '联系电话', key: 'phone', width: 140, render: (row: any) => row.phone || '—' },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'active' },
      { label: '停用', value: 'disabled' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增供应商', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该供应商？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'disabled' }, '主体管理', '停用', 'name', reason),
    visible: row => row.status === 'active'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该供应商？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'active' }, '主体管理', '启用', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该供应商？（请填写备注）',
    handler: async (row, reason) => await store.remove('subjects', row.id, '主体管理', 'name', reason),
    visible: row => row.status === 'disabled'
  }
];
const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称', rules: [requiredRule] },
  // 联系方式组（V37 新增列）：与 store_profile 档案风格保持一致
  { key: 'contactName', label: '联系人' },
  {
    key: 'phone',
    label: '联系电话',
    rules: [requiredRule]
  },
  { key: 'address', label: '地址' },
  { key: 'email', label: '邮箱' },
  { key: 'remark', label: '备注', type: 'textarea' }
];

const config: AdminListConfig = {
  title: '供应商管理',
  remoteKey: 'subjects',
  // 「可提现余额」列按 subjectId 从 subjectAccounts 取数，需预加载该资源，否则恒显示 0.00
  remoteDeps: ['subjectAccounts'],
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '供应商',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (!String(data.name ?? '').trim()) throw new Error('名称必填');
      // 联系电话为业务必填（后端 upsertSupplierProfile 同样会校验）
      if (!String(data.phone ?? '').trim()) throw new Error('联系电话必填');
      const payload = {
        code: data.code,
        name: data.name,
        status: data.status,
        contactName: data.contactName,
        phone: data.phone,
        address: data.address,
        email: data.email,
        remark: data.remark
      };
      if (editing) await updateSubjectSupplier(editing.id, payload);
      else await createSubjectSupplier(payload);
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
