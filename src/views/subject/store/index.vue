<script setup lang="ts">

defineOptions({
  name: 'subject_store'
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
    store.subjects.filter(s => s.type === 'store'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 120 },
  { title: '绑定用户', key: 'boundUserName', width: 120, render: (row: any) => row.boundUserName || '未绑定' },
    { title: '可提现余额(元)', key: 'balance', width: 115, align: 'right', render: (row: any) => { const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id); return acc ? (acc.availableBalance ?? 0).toFixed(2) : '—'; } },
  { title: '门店类型', key: 'storeType', width: 110 },
  { title: '城市', key: 'city', width: 100 },
  {
    title: '营业状态',
    key: 'status',
    width: 95,
    render: renderTag('status', statusMap({ open: ['营业中', 'success'], closed: ['停业', 'default'] }))
  },
  { title: '负责人', key: 'manager', width: 100 },
  { title: '地址', key: 'address', minWidth: 120, ellipsis: { tooltip: true } },
  { title: '电话', key: 'phone', width: 130 },
  { title: '关联投资人', key: 'investorName', width: 120 },
  { title: '创建时间', key: 'createTime', width: 145 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '营业状态',
    type: 'select',
    options: [
      { label: '营业中', value: 'open' },
      { label: '停业', value: 'closed' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增门店', type: 'primary', modal: 'add' }];
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
    visible: row => row.boundUserId && row.status === 'closed'
  },
    { label: '余额明细', type: 'info', handler: (row: any) => router.push({ path: '/finance/flow', query: { subjectId: row.id } }) },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停业该门店？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { status: 'closed' }, '主体管理', '停业', 'name', reason),
    visible: row => row.status === 'open'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认营业该门店？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { status: 'open' }, '主体管理', '营业', 'name', reason),
    visible: row => row.status === 'closed'
  },
  {
    label: '绑定投资人',
    type: 'info',
    picker: {
      title: '选择投资人',
      options: () => store.subjects.filter(s => s.type === 'investor').map(s => ({ label: s.name, value: s.code }))
    },
    handler: (row, picked) => {
      if (picked) store.bindInvestorToStore(row.id, picked, `绑定投资人：${picked}`);
    },
    visible: row => !row.investorId
  },
  {
    label: '解绑投资人',
    type: 'warning',
    reasonPrompt: '确认解绑该门店的投资人？（请填写备注）',
    handler: (row, reason) => store.unbindInvestorFromStore(row.id, reason),
    visible: row => Boolean(row.investorId)
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该门店？删除后列表不再展示（逻辑删除），请填写备注',
    handler: (row, reason) => store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  {
    key: 'storeType',
    label: '门店类型',
    type: 'select',
    options: () => store.storeTypes.filter((t: any) => t.enabled !== false).map((t: any) => ({ label: t.name, value: t.name }))
  },
  { key: 'province', label: '省份', type: 'select', options: () => store.provinces.map((p: any) => ({ label: p.name, value: p.name })) },
  { key: 'city', label: '城市', type: 'select', options: () => store.cities.map((c: any) => ({ label: c.name, value: c.name })) },
  { key: 'manager', label: '负责人' },
  { key: 'address', label: '详细地址', type: 'textarea' },
  { key: 'phone', label: '联系人电话' },
  {
    key: 'geocode',
    label: '坐标',
    type: 'geocode',
    geocodeSourceKey: 'address',
    geocodeLatKey: 'latitude',
    geocodeLngKey: 'longitude'
  },
  {
    key: 'status',
    label: '营业状态',
    type: 'select',
    options: [
      { label: '营业中', value: 'open' },
      { label: '停业', value: 'closed' }
    ]
  }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  title: '门店管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '门店',
    fields: formFields,
    onSubmit: (data, editing) => {
      const payload = { ...data, type: 'store' };
      if (editing) store.update('subjects', editing.id, payload, '主体管理', 'name');
      else store.add('subjects', { ...payload, investorId: null, investorName: '未绑定' }, '主体管理', 'name');
    }
  },
  importConfig: {
    title: '门店',
    fields: [
      { key: 'code', label: '编码', required: true },
      { key: 'name', label: '名称', required: true },
      { key: 'city', label: '城市' },
      { key: 'manager', label: '负责人' },
      { key: 'address', label: '地址' },
      { key: 'phone', label: '电话' },
      { key: 'storeType', label: '门店类型' },
      { key: 'status', label: '营业状态' }
    ],
    template: () => '编码,名称,城市,负责人,地址,电话,门店类型,营业状态\nST-1004,新门店,长沙,店长5,某地址,0731-0000,奶茶/饮品,营业中\n',
    parse: (rows) => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        if (!r.code || !r.name) { errors.push('第 ' + (i + 2) + ' 行：编码和名称必填'); return; }
        ok.push({ code: r.code, name: r.name, city: r.city || '长沙', manager: r.manager || '', address: r.address || '', phone: r.phone || '', storeType: r.storeType || '奶茶/饮品', status: (r.status === '停业' ? 'closed' : 'open') });
      });
      return { ok, errors };
    },
    commit: (rows) => {
      let added = 0, skipped = 0;
      rows.forEach(r => {
        const exists = store.subjects.some(s => s.type === 'store' && s.code === r.code);
        if (exists) { skipped++; return; }
        store.add('subjects', { ...r, type: 'store', investorId: null, investorName: '未绑定' }, '主体管理', 'name');
        added++;
      });
      return { added, skipped };
    }
  },
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

