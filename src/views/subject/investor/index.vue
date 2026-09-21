<script setup lang="ts">

defineOptions({
  name: 'subject_investor'
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
    store.subjects.filter(s => s.type === 'investor'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '绑定用户', key: 'boundUserName', width: 120, render: (row: any) => row.boundUserName || '未绑定' },
    { title: '可提现余额(元)', key: 'balance', width: 130, align: 'right', render: (row: any) => { const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id); return acc ? (acc.availableBalance ?? 0).toFixed(2) : '—'; } },
  { title: '可投门店数', key: 'investableStoreCount', width: 110, align: 'right' },
  { title: '关联门店', key: 'relatedStore', minWidth: 180 },
  {
    title: '签约状态',
    key: 'signStatus',
    width: 110,
    render: renderTag('signStatus', statusMap({ signed: ['已签约', 'success'], pending: ['审核中', 'warning'], disabled: ['停用', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'signStatus',
    label: '签约状态',
    type: 'select',
    options: [
      { label: '已签约', value: 'signed' },
      { label: '审核中', value: 'pending' },
      { label: '停用', value: 'disabled' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增投资人', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
    { label: '余额明细', type: 'info', handler: (row: any) => router.push({ path: '/finance/flow', query: { subjectId: row.id } }) },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '签约审批',
    type: 'info',
    reasonPrompt: '确认通过签约审批？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { signStatus: 'signed' }, '主体管理', '签约审批', 'name', reason),
    visible: row => row.signStatus === 'pending'
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该投资人？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { signStatus: 'disabled' }, '主体管理', '停用', 'name', reason),
    visible: row => row.signStatus === 'signed'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该投资人？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { signStatus: 'signed' }, '主体管理', '启用', 'name', reason),
    visible: row => row.signStatus === 'disabled'
  },
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
    reasonPrompt: '确认解绑该投资人绑定的用户？（请填写备注）',
    handler: (row, reason) => store.unbindSubjectUser(row.id, reason),
    visible: row => row.boundUserId && row.signStatus === 'disabled'
  },
  {
    label: '绑定门店',
    type: 'info',
    picker: {
      title: '选择门店',
      options: (row) => {
        const boundIds = Array.isArray(row?.relatedStoreIds) ? row.relatedStoreIds : [];
        return store.subjects
          .filter(s => s.type === 'store' && !boundIds.includes(s.code))
          .map(s => ({ label: s.name, value: s.code }));
      }
    },
    handler: (row, picked) => {
      if (!picked) return;
      const st = store.subjects.find(s => s.code === picked);
      if (st) store.bindInvestorToStore(st.id, row.code, `绑定门店：${st.name}`);
    }
  },
  {
    label: '解绑门店',
    type: 'warning',
    picker: {
      title: '选择要解绑的门店',
      options: (row) => {
        const ids = Array.isArray(row?.relatedStoreIds) ? row.relatedStoreIds : [];
        return store.subjects.filter(s => s.type === 'store' && ids.includes(s.code)).map(s => ({ label: s.name, value: s.code }));
      }
    },
    handler: (row, picked) => {
      if (!picked) return;
      const st = store.subjects.find(s => s.code === picked);
      if (st) store.unbindInvestorFromStore(st.id, `解绑门店：${st.name}`);
    }
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该投资人？删除后列表不再展示（逻辑删除），请填写备注',
    handler: (row, reason) => store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '姓名' }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  title: '投资人管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '投资人',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'investor' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'investor' }, '主体管理', 'name');
    }
  },
  importConfig: {
    title: '投资人',
    fields: [
      { key: 'code', label: '编码', required: true },
      { key: 'name', label: '姓名', required: true },
      { key: 'signStatus', label: '签约状态' },
      { key: 'relatedStore', label: '关联门店' }
    ],
    template: () => '编码,姓名,签约状态,关联门店(逗号分隔)\nIV-1004,投资人丁,已签约,五一广场店,岳麓店\n',
    parse: (rows) => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        if (!r.code || !r.name) { errors.push('第 ' + (i + 2) + ' 行：编码和姓名必填'); return; }
        const storeNames = String(r.relatedStore || '').split(/[,，、]/).map(s => s.trim()).filter(Boolean);
        ok.push({ code: r.code, name: r.name, signStatus: r.signStatus === '审核中' ? 'pending' : 'signed', relatedStore: storeNames.join('、') || '未绑定' });
      });
      return { ok, errors };
    },
    commit: (rows) => {
      let added = 0, skipped = 0;
      rows.forEach(r => {
        const exists = store.subjects.some(s => s.type === 'investor' && s.code === r.code);
        if (exists) { skipped++; return; }
        const { relatedStore, ...rest } = r;
        store.add('subjects', { ...rest, type: 'investor', investableStoreCount: 0, relatedStoreIds: [] }, '主体管理', 'name');
        if (relatedStore && relatedStore !== '未绑定') {
          relatedStore.split('、').filter(Boolean).forEach((n: string) => {
            const st = store.subjects.find(s => s.type === 'store' && s.name === n);
            if (st) store.bindInvestorToStore(st.id, r.code, '导入绑定：' + n);
          });
        }
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

