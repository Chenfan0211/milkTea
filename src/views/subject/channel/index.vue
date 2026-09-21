<script setup lang="ts">

defineOptions({
  name: 'subject_channel'
});

import { useRouter } from 'vue-router';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const router = useRouter();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'resource'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '绑定用户', key: 'boundUserName', width: 120, render: (row: any) => row.boundUserName || '未绑定' },
    { title: '可提现余额(元)', key: 'balance', width: 115, align: 'right', render: (row: any) => { const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id); return acc ? (acc.availableBalance ?? 0).toFixed(2) : '—'; } },
  { title: '所在地', key: 'location', width: 120 },
  { title: '门店类型', key: 'storeType', width: 110 },
  { title: '绑定门店数', key: 'boundStoreCount', width: 95, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 95,
    render: renderTag('status', statusMap({ active: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 150 }
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
const toolbar: RowAction[] = [{ label: '新增资源方', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '绑定用户',
    type: 'info',
    picker: {
      title: '选择用户',
      options: () => store.users.filter((u: any) => !u.boundSubjectId && !u.deleted).map((u: any) => ({ label: u.nickName, value: String(u.id) }))
    },
    handler: (row, picked) => {
      if (picked) store.bindSubjectUser(row.id, Number(picked));
    },
    visible: row => !row.boundUserId
  },
  {
    label: '解绑用户',
    type: 'warning',
    reasonPrompt: '确认解绑该经营者绑定的用户？（请填写备注）',
    handler: (row, reason) => store.unbindSubjectUser(row.id, reason),
    visible: row => row.boundUserId && row.status === 'disabled'
  },
    { label: '余额明细', type: 'info', handler: (row: any) => router.push({ path: '/finance/flow', query: { subjectId: row.id } }) },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '绑定门店',
    type: 'info',
    picker: {
      title: '选择门店',
      options: () => store.subjects.filter(s => s.type === 'store').map(s => ({ label: s.name, value: s.code }))
    },
    handler: (row, picked) => {
      if (picked) store.bindResourceToStore(row.id, picked, `绑定门店：${picked}`);
    }
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该资源方？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { status: 'disabled' }, '主体管理', '停用', 'name', reason),
    visible: row => row.status === 'active'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该资源方？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { status: 'active' }, '主体管理', '启用', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该资源方？删除后列表不再展示（逻辑删除），请填写备注',
    handler: (row, reason) => store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'location', label: '所在地' },
  {
    key: 'storeType',
    label: '门店类型',
    type: 'select',
    options: () => store.storeTypes.filter((t: any) => t.enabled !== false).map((t: any) => ({ label: t.name, value: t.name }))
  }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  title: '资源方管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '资源方',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'resource' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'resource', boundStoreIds: [], boundStoreCount: 0 }, '主体管理', 'name');
    }
  },
  importConfig: {
    title: '资源方',
    fields: [
      { key: 'code', label: '编码', required: true },
      { key: 'name', label: '名称', required: true },
      { key: 'location', label: '所在地' },
      { key: 'storeType', label: '门店类型' }
    ],
    template: () => '编码,名称,所在地,门店类型\nRS-1004,资源方丁,长沙市,奶茶/饮品\n',
    parse: (rows) => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        if (!r.code || !r.name) { errors.push('第 ' + (i + 2) + ' 行：编码和名称必填'); return; }
        ok.push({ code: r.code, name: r.name, location: r.location || '—', storeType: r.storeType || '奶茶/饮品' });
      });
      return { ok, errors };
    },
    commit: (rows) => {
      let added = 0, skipped = 0;
      rows.forEach(r => {
        const exists = store.subjects.some(s => s.type === 'resource' && s.code === r.code);
        if (exists) { skipped++; return; }
        store.add('subjects', { ...r, type: 'resource', status: 'active', boundStoreIds: [], boundStoreCount: 0 }, '主体管理', 'name');
        added++;
      });
      return { added, skipped };
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
