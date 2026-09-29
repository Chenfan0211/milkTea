<script setup lang="ts">

defineOptions({
  name: 'SystemCity'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 城市管理。
 *
 * 省-市关系（用户决策）：**沿用 region.parent_id 关联**，不新增 province_code 列。
 *   region.level=1 为省，level=2 为市，市的 parent_id 指向省的 id。
 *
 * 因此本页：
 *   - 表单「省份」选择的是省份的 **id**（写入 parent_id），数据来自 region level=1；
 *   - 城市名称/编码手工填写，level 固定为 2；
 *   - 经纬度不再对外维护（隐藏），由系统自行维护。
 *   - 支持启用/停用状态，仅停用状态可删除（逻辑删除）。
 */

/** 按 parent_id 反查省份名称 */
function provinceName(parentId: any): string {
  const p = store.provinces.find((x: any) => String(x.id) === String(parentId));
  return p ? p.name : (parentId ? String(parentId) : '—');
}

/** 省份下拉：value 用 id（对应 parent_id），数据来自 region level=1（省） */
const provinceOptions = () =>
  store.provinces.filter((p: any) => Number(p.level) === 1).map((p: any) => ({ label: p.name, value: String(p.id) }));

const columns: DataTableColumns<any> = [
  { title: '省份', key: 'parentId', width: 120, render: (row: any) => provinceName(row.parentId) },
  { title: '城市', key: 'name', width: 140 },
  { title: '城市编码', key: 'code', width: 140 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
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
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增城市', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该城市？（请填写备注）',
    handler: async (row, _reason) => await store.update('cities', row.id, { status: 'disabled' }, '数据字典', 'name'),
    visible: row => row.status !== 'disabled'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该城市？（请填写备注）',
    handler: async (row, _reason) => await store.update('cities', row.id, { status: 'enabled' }, '数据字典', 'name'),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该城市？（停用状态才能删除，请填写备注）',
    handler: async (row, reason) => await store.remove('cities', row.id, '数据字典', 'name', reason),
    visible: row => row.status === 'disabled'
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
  loadData: async ({ page, pageSize, search }) => {
    // region 表含省(level=1)/市(level=2)/区县(level=3)，城市管理只维护市级数据。
    // 通过 eq_level=2 走后端等值过滤（cities 资源的 filterable 白名单含 level），
    // 避免前端过滤导致分页 total 错乱、省/区县混入列表。
    return store.queryRemote('cities', { ...search, eq_level: '2' }, page, pageSize);
  },
  form: {
    title: '城市',
    fields: formFields,
    /**
     * 编辑回填：只回填表单声明字段；parentId 可能为数字，统一转字符串以匹配下拉 value。
     * 不再整行 {...row} 回填，避免 id/updateTime 等服务端字段进入 payload。
     */
    toFormData: (row: any) => ({
      parentId: row.parentId == null ? null : String(row.parentId),
      name: row.name,
      code: row.code,
      sort: row.sort == null ? 0 : Number(row.sort)
    }),
    onSubmit: async (data, editing) => {
      const payload: Record<string, any> = {
        ...data,
        // 城市固定为 level=2（省为 1），与既有数据口径一致
        level: 2,
        parentId: data.parentId == null ? null : Number(data.parentId),
        sort: Number(data.sort) || 0
      };
      // 新增默认启用；编辑不提交 status（避免误改状态，状态由「停用/启用」按钮单独切换）
      if (!editing) payload.status = 'enabled';
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
