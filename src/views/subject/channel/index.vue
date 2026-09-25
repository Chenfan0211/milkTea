<script setup lang="ts">
defineOptions({
  name: 'subject_channel'
});

import { h, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import ChannelStoreDialog from './ChannelStoreDialog.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { createSubjectChannel, fetchSubjectChannelsPage, updateSubjectChannel } from '@/service/api/subject';

const store = useAdminStore();

// 门店绑定弹窗状态：view=查看 / bind=绑定 / unbind=解绑
const dialogVisible = ref(false);
const dialogMode = ref<'view' | 'bind' | 'unbind'>('view');
const dialogChannel = ref<any>(null);
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

function openDialog(mode: 'view' | 'bind' | 'unbind', row: any) {
  dialogMode.value = mode;
  dialogChannel.value = row;
  dialogVisible.value = true;
}

function onDialogChanged() {
  listRef.value?.reload();
}

const load = async (p: any) => {
  const keyword = String(p.search?.name || '').trim();
  const status = String(p.search?.status || '').trim();
  const page = await fetchSubjectChannelsPage({
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
    width: 115,
    align: 'right',
    render: (row: any) => {
      const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id);
      return (acc ? (acc.availableBalance ?? 0) : 0).toFixed(2);
    }
  },
  { title: '所在地', key: 'location', width: 120 },
  { title: '门店类型', key: 'storeType', width: 110 },
  {
    title: '绑定门店数',
    key: 'boundStoreCount',
    width: 110,
    align: 'right',
    render: (row: any) => {
      const count = row.boundStoreCount ?? 0;
      if (!count) return h('span', { style: 'color:#9B9B96' }, '0');
      return h(
        'a',
        {
          style: 'color:#53882C;cursor:pointer;text-decoration:underline',
          onClick: () => openDialog('view', row)
        },
        String(count)
      );
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 95,
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
const toolbar: RowAction[] = [{ label: '新增资源方', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
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
    reasonPrompt: '确认解绑该经营者绑定的用户？（请填写备注）',
    handler: (row, reason) => store.unbindSubjectUser(row.id, reason),
    visible: row => row.boundUserId && row.status === 'disabled'
  },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '绑定门店',
    type: 'info',
    handler: (row: any) => openDialog('bind', row)
  },
  {
    label: '解绑门店',
    type: 'warning',
    handler: (row: any) => openDialog('unbind', row)
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该资源方？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'disabled' }, '主体管理', '停用', 'name', reason),
    visible: row => row.status === 'active'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该资源方？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('subjects', row.id, { status: 'active' }, '主体管理', '启用', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该资源方？删除后列表不再展示（逻辑删除），请填写备注',
    handler: async (row, reason) => await store.remove('subjects', row.id, '主体管理', 'name', reason),
    visible: row => row.status === 'disabled'
  }
];
const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称', rules: [requiredRule] },
  { key: 'location', label: '所在地', rules: [requiredRule] },
  {
    key: 'storeType',
    label: '门店类型',
    type: 'select',
    options: () =>
      store.storeTypes.filter((t: any) => t.enabled !== false).map((t: any) => ({ label: t.name, value: t.name })),
    rules: [requiredRule]
  }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  // 「可提现余额」列按 subjectId 从 subjectAccounts 取数，需预加载该资源，否则恒显示 0.00
  // subjectAccounts：供「可提现余额」列取数；storeTypes：供「门店类型」下拉选项
  remoteDeps: ['subjectAccounts', 'storeTypes'],
  title: '资源方管理',
  refreshOnEnter: true,
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '资源方',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (!String(data.name ?? '').trim()) throw new Error('名称必填');
      if (!String(data.location ?? '').trim()) throw new Error('所在地必填');
      if (!String(data.storeType ?? '').trim()) throw new Error('门店类型必填');
      const payload = {
        code: data.code,
        name: data.name,
        location: data.location,
        storeType: data.storeType,
        status: data.status
      };
      if (editing) await updateSubjectChannel(editing.id, payload);
      else await createSubjectChannel(payload);
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
    parse: rows => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        if (!r.code || !r.name) {
          errors.push('第 ' + (i + 2) + ' 行：编码和名称必填');
          return;
        }
        ok.push({ code: r.code, name: r.name, location: r.location || '—', storeType: r.storeType || '奶茶/饮品' });
      });
      return { ok, errors };
    },
    commit: async rows => {
      let added = 0;
      for (const r of rows) {
        await createSubjectChannel({
          code: r.code,
          name: r.name,
          location: r.location,
          storeType: r.storeType,
          status: 'active'
        });
        added++;
      }
      return { added, skipped: 0 };
    }
  }
};
</script>

<!--
  单根容器（项目约定）：Vue 的 <Transition>（页面切换动画）要求插槽只渲染一个元素根节点，
  否则报「Vue Transition 多根节点错误」。此处列表与弹窗是兄弟节点，
  必须用 page-root 包起来 —— 与 marketing/*、product/list、trade/order 等页面写法一致。

  注意：注释必须写在 <template> 外面。模板内的顶层注释会被 Vue 当作一个额外根节点，
  编译后根节点变成 Fragment，同样会触发该报错（已实测验证）。
-->
<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />
    <ChannelStoreDialog
      v-model:show="dialogVisible"
      :mode="dialogMode"
      :channel="dialogChannel"
      @changed="onDialogChanged"
    />
  </div>
</template>

<style scoped></style>
