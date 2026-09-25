<script setup lang="ts">

defineOptions({
  name: 'system_city'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

/**
 * 城市管理。
 *
 * 省-市关系（用户决策）：**沿用 region.parent_id 关联**，不新增 province_code 列。
 *   region.level=1 为省，level=2 为市，市的 parent_id 指向省的 id。
 *
 * 因此本页：
 *   - 表单「省份」选择的是省份的 **id**（写入 parent_id）；
 *   - 列表展示时用 parent_id 反查省份名称；
 *   - 经纬度为 V31 新增列，现可直接写入。
 *
 * 历史问题：页面原用 provinceCode（库中无此列），且经纬度也无对应列，
 * 导致提交后三者全部被静默丢弃。
 */

/** 按 parent_id 反查省份名称 */
function provinceName(parentId: any): string {
  const p = store.provinces.find((x: any) => String(x.id) === String(parentId));
  return p ? p.name : (parentId ? String(parentId) : '—');
}

/** 省份下拉：value 用 id（对应 parent_id） */
const provinceOptions = () =>
  store.provinces.map((p: any) => ({ label: p.name, value: String(p.id) }));

const columns: DataTableColumns<any> = [
  { title: '省份', key: 'parentId', width: 120, render: (row: any) => provinceName(row.parentId) },
  { title: '城市', key: 'name', width: 140 },
  { title: '城市编码', key: 'code', width: 140 },
  { title: '经度', key: 'longitude', width: 110, align: 'right', render: (row: any) => row.longitude ?? '—' },
  { title: '纬度', key: 'latitude', width: 110, align: 'right', render: (row: any) => row.latitude ?? '—' },
  { title: '排序', key: 'sort', width: 80, align: 'right' }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '城市', placeholder: '城市名称' }
];

const formFields: FormField[] = [
  {
    key: 'parentId',
    label: '所属省份',
    type: 'select',
    options: provinceOptions,
    placeholder: '请选择省份',
    rules: [{ required: true, message: '请选择所属省份', trigger: ['change', 'blur'] }]
  },
  { key: 'name', label: '城市名称', rules: [{ required: true, message: '请输入城市名称', trigger: ['input', 'blur'] }] },
  { key: 'code', label: '城市编码', rules: [{ required: true, message: '请输入城市编码', trigger: ['input', 'blur'] }] },
  { key: 'longitude', label: '经度', type: 'number', placeholder: '如 112.9388' },
  { key: 'latitude', label: '纬度', type: 'number', placeholder: '如 28.2282' },
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增城市', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该城市？（请填写备注）',
    handler: async (row, reason) => await store.remove('cities', row.id, '数据字典', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '城市管理',
  remoteKey: 'cities',
  remoteDeps: ['provinces'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('cities', search, page, pageSize),
  form: {
    title: '城市',
    fields: formFields,
    /** 编辑回填：parent_id 可能为数字，统一转字符串以匹配下拉 value */
    toFormData: (row: any) => ({
      ...row,
      parentId: row.parentId == null ? null : String(row.parentId),
      sort: row.sort == null ? 0 : Number(row.sort)
    }),
    onSubmit: async (data, editing) => {
      const payload = {
        ...data,
        // 城市固定为 level=2（省为 1），与既有数据口径一致
        level: 2,
        parentId: data.parentId == null ? null : Number(data.parentId),
        sort: Number(data.sort) || 0
      };
      if (editing) await store.update('cities', editing.id, payload, '数据字典', 'name');
      else await store.add('cities', payload, '数据字典', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
