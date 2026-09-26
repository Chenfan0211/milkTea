<script setup lang="ts">

defineOptions({
  name: 'system_dict'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 数据字典。
 *
 * 字段口径对齐 sys_dict_item 表（真实列名）：
 *   dict_type=分组、item_code=编码、item_name=名称、sort=排序、enabled=状态
 *
 * 历史问题：本页原用 code / name / groupName，与库中列名完全不符，
 * 提交时被 CRUD 白名单整批丢弃，接口直接返回「没有可写入的字段」——新增完全不可用。
 * 故此处统一改为库中列名，不再依赖任何别名映射。
 */

const groupOptions = () =>
  Array.from(new Set(store.dictEntries.map((e: any) => e.dictType).filter(Boolean))).map(name => ({
    label: name,
    value: name
  }));

const columns: DataTableColumns<any> = [
  { title: '分组', key: 'dictType', width: 130 },
  { title: '编码', key: 'itemCode', width: 140 },
  { title: '名称', key: 'itemName', width: 140 },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    render: (row: any) => renderTag('enabled', statusMap({ '1': ['启用', 'success'], '0': ['停用', 'default'] }))({ enabled: String(row.enabled) })
  }
];

const searchFields: SearchField[] = [
  { key: 'itemName', label: '名称', placeholder: '名称' },
  { key: 'dictType', label: '分组', type: 'select', options: groupOptions }
];

const formFields: FormField[] = [
  { key: 'dictType', label: '分组', type: 'select', options: groupOptions, placeholder: '请选择或输入分组' },
  { key: 'itemCode', label: '编码', rules: [{ required: true, message: '请输入编码', trigger: ['input', 'blur'] }] },
  { key: 'itemName', label: '名称', rules: [{ required: true, message: '请输入名称', trigger: ['input', 'blur'] }] },
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增字典项', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该字典项？（请填写备注）',
    handler: async row => await store.update('dictEntries', row.id, { enabled: 0 }, '数据字典', 'itemName'),
    visible: row => Number(row.enabled) === 1
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该字典项？（请填写备注）',
    handler: async row => await store.update('dictEntries', row.id, { enabled: 1 }, '数据字典', 'itemName'),
    visible: row => Number(row.enabled) === 0
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该字典项？（请填写备注）',
    handler: async (row, reason) => await store.remove('dictEntries', row.id, '数据字典', 'itemName', reason)
  }
];

const config: AdminListConfig = {
  title: '数据字典',
  remoteKey: 'dictEntries',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('dictEntries', search, page, pageSize),
  form: {
    title: '字典项',
    fields: formFields,
    /**
     * 编辑回填：只回填表单声明字段 + enabled（enabled 不在表单中展示，
     * 但 onSubmit 组装 payload 时依赖它，缺失会把停用项误改回启用）。
     * 不再整行 {...row} 回填，避免 id/updateTime 等服务端字段进入 payload。
     */
    toFormData: (row: any) => ({
      dictType: row.dictType,
      itemCode: row.itemCode,
      itemName: row.itemName,
      sort: row.sort == null ? 0 : Number(row.sort),
      enabled: row.enabled == null ? 1 : Number(row.enabled)
    }),
    onSubmit: async (data, editing) => {
      const payload = {
        ...data,
        enabled: Number(data.enabled) === 0 ? 0 : 1,
        sort: Number(data.sort) || 0
      };
      if (editing) await store.update('dictEntries', editing.id, payload, '数据字典', 'itemName');
      else await store.add('dictEntries', payload, '数据字典', 'itemName');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
