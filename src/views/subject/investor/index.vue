<script setup lang="ts">
defineOptions({
  name: 'subject_investor'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { createSubjectInvestor, fetchSubjectInvestorsPage, updateSubjectInvestor } from '@/service/api/subject';

const store = useAdminStore();

const load = async (p: any) => {
  const keyword = String(p.search?.name || '').trim();
  const status = String(p.search?.status || '').trim();
  const page = await fetchSubjectInvestorsPage({
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
  { title: '绑定用户', key: 'boundUserName', width: 120, render: (row: any) => row.boundUserName || '未绑定' },
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
  { title: '可投门店数', key: 'investableStoreCount', width: 110, align: 'right' },
  { title: '关联门店', key: 'relatedStore', minWidth: 180 },
  {
    title: '签约状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({ signed: ['已签约', 'success'], active: ['启用', 'success'], inactive: ['停用', 'default'] })
    )
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '签约状态',
    type: 'select',
    options: [
      { label: '已签约', value: 'signed' },
      { label: '启用', value: 'active' },
      { label: '停用', value: 'inactive' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增投资人', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '签约审批',
    type: 'info',
    reasonPrompt: '确认通过签约审批？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'signed' }, '主体管理', '签约审批', 'name', reason),
    visible: _row => false
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该投资人？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'inactive' }, '主体管理', '停用', 'name', reason),
    visible: row => row.status === 'signed'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该投资人？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'signed' }, '主体管理', '启用', 'name', reason),
    visible: row => row.status === 'inactive'
  },
  {
    label: '绑定用户',
    type: 'info',
    picker: {
      title: '选择用户',
      options: () =>
        store.users
          .filter((u: any) => !u.boundSubjectId && !u.deleted)
          .map((u: any) => ({ label: u.nickName, value: String(u.id) }))
    },
    handler: async (row, picked) => {
      if (picked) await store.bindSubjectUser(row.id, Number(picked));
    },
    visible: row => !row.boundUserId
  },
  {
    label: '解绑用户',
    type: 'warning',
    reasonPrompt: '确认解绑该投资人绑定的用户？（请填写备注）',
    handler: (row, reason) => store.unbindSubjectUser(row.id, reason),
    visible: row => row.boundUserId && row.status === 'inactive'
  },
  {
    label: '绑定门店',
    type: 'info',
    picker: {
      title: '选择门店',
      options: row => {
        const boundIds = Array.isArray(row?.relatedStoreIds) ? row.relatedStoreIds : [];
        return store.subjects
          .filter((s: any) => s.type === 'store' && !boundIds.includes(Number(s.id)))
          .map((s: any) => ({ label: s.name, value: String(s.id) }));
      }
    },
    handler: async (row, picked) => {
      if (picked) await store.bindStoreInvestor(Number(picked), row.id);
    }
  },
  {
    label: '解绑门店',
    type: 'warning',
    picker: {
      title: '选择要解绑的门店',
      options: row => {
        const ids = Array.isArray(row?.relatedStoreIds) ? row.relatedStoreIds : [];
        return store.subjects
          .filter((s: any) => s.type === 'store' && ids.includes(Number(s.id)))
          .map((s: any) => ({ label: s.name, value: String(s.id) }));
      }
    },
    handler: async (_row, picked) => {
      if (picked) await store.unbindStoreInvestor(Number(picked));
    }
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该投资人？删除后列表不再展示（逻辑删除），请填写备注',
    handler: async (row, reason) => await store.remove('subjects', row.id, '主体管理', 'name', reason),
    visible: row => row.status === 'inactive'
  }
];
const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '姓名', rules: [requiredRule] }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  // 「可提现余额」列按 subjectId 从 subjectAccounts 取数，需预加载该资源，否则恒显示 0.00
  remoteDeps: ['subjectAccounts'],
  title: '投资人管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '投资人',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (!String(data.name ?? '').trim()) throw new Error('姓名必填');
      const payload = { code: data.code, name: data.name, status: data.status };
      if (editing) await updateSubjectInvestor(editing.id, payload);
      else await createSubjectInvestor(payload);
    }
  },
  importConfig: {
    title: '投资人',
    fields: [
      { key: 'code', label: '编码', required: true },
      { key: 'name', label: '姓名', required: true },
      { key: 'status', label: '签约状态' },
      { key: 'relatedStore', label: '关联门店' }
    ],
    template: () => '编码,姓名,签约状态,关联门店(逗号分隔)\nIV-1004,投资人丁,已签约,五一广场店,岳麓店\n',
    parse: rows => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        if (!r.code || !r.name) {
          errors.push('第 ' + (i + 2) + ' 行：编码和姓名必填');
          return;
        }
        const storeNames = String(r.relatedStore || '')
          .split(/[,，、]/)
          .map(s => s.trim())
          .filter(Boolean);
        const rawStatus = String(r.status || '').trim();
        const status =
          rawStatus === '停用' || rawStatus === 'inactive'
            ? 'inactive'
            : rawStatus === '启用' || rawStatus === 'active'
              ? 'active'
              : 'signed';
        ok.push({
          code: r.code,
          name: r.name,
          status,
          signStatus: status,
          relatedStore: storeNames.join('、') || '未绑定'
        });
      });
      return { ok, errors };
    },
    commit: async rows => {
      let added = 0;
      for (const r of rows) {
        const created = await createSubjectInvestor({
          code: r.code,
          name: r.name,
          status: r.status || 'signed',
          signStatus: r.signStatus || r.status || 'signed',
          investableStoreCount: 0
        });
        if (r.relatedStore && r.relatedStore !== '未绑定') {
          for (const n of String(r.relatedStore).split('、').filter(Boolean)) {
            const st = store.subjects.find((s: any) => s.type === 'store' && s.name === n);
            if (st && created?.id) await store.bindStoreInvestor(st.id, created.id);
          }
        }
        added++;
      }
      return { added, skipped: 0 };
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
