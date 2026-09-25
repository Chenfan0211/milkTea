<script setup lang="ts">

defineOptions({
  name: 'product_category'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 分类管理。
 *
 * 字段口径对齐 product_category 表（V30 迁移后）：
 * - 编码 code / 名称 name / 分类标签 tag / 排序 sort / 状态 enabled
 * - type 为 NOT NULL 列（TAB/GROUP/CATEGORY），表单必须提供，否则新增会被数据库拒绝
 *
 * 「状态」用 enabled（数据库为 tinyint，1 启用 / 0 停用）而非布尔：
 * CrudService 写库时会把 Boolean 转成 1/0，但读回来是数字。
 * 若页面混用 true/false 与 1/0，会出现「刚保存就查不到」的筛选不一致，
 * 故此处统一按数字 1/0 处理。
 */

/** 状态下拉：值与数据库一致（1 启用 / 0 停用） */
const ENABLED_OPTIONS = [
  { label: '启用', value: '1' },
  { label: '停用', value: '0' }
];

/** 层级类型：与数据库 type 列取值一致 */
const TYPE_OPTIONS = [
  { label: '菜单页签（TAB）', value: 'TAB' },
  { label: '分组（GROUP）', value: 'GROUP' },
  { label: '分类（CATEGORY）', value: 'CATEGORY' }
];

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 140 },
  { title: '分类标签', key: 'tag', width: 120, render: (row: any) => row.tag || '—' },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    // 数据库返回数字 1/0，renderTag 按字符串查表，故先转字符串
    render: (row: any) => renderTag('enabled', statusMap({ '1': ['启用', 'success'], '0': ['停用', 'default'] }))({ enabled: String(row.enabled) })
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '分类名称', placeholder: '分类名称' },
  {
    // eq_ 前缀 -> 后端按等值过滤（enabled 已在 CrudRegistry 登记为 filterable）
    key: 'eq_enabled',
    label: '状态',
    type: 'select',
    options: ENABLED_OPTIONS
  }
];

const formFields: FormField[] = [
  { key: 'code', label: '编码', rules: [{ required: true, message: '请输入编码', trigger: ['input', 'blur'] }] },
  { key: 'name', label: '名称', rules: [{ required: true, message: '请输入名称', trigger: ['input', 'blur'] }] },
  { key: 'tag', label: '分类标签', placeholder: '如：热销 / 新品（可留空）' },
  {
    key: 'type',
    label: '层级类型',
    type: 'select',
    options: TYPE_OPTIONS,
    placeholder: '请选择层级类型',
    rules: [{ required: true, message: '请选择层级类型', trigger: ['change', 'blur'] }]
  },
  { key: 'sort', label: '排序', type: 'number', placeholder: '数字越小越靠前' },
  {
    key: 'enabled',
    label: '状态',
    type: 'select',
    options: ENABLED_OPTIONS,
    placeholder: '请选择状态'
  }
];

const toolbar: RowAction[] = [{ label: '新增分类', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该分类？（请填写备注）',
    handler: async row => await store.update('productCategories', row.id, { enabled: 0 }, '商品中心', 'name'),
    visible: row => Number(row.enabled) === 1
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该分类？（请填写备注）',
    handler: async row => await store.update('productCategories', row.id, { enabled: 1 }, '商品中心', 'name'),
    visible: row => Number(row.enabled) === 0
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该分类？（请填写备注）',
    handler: async (row, reason) => await store.remove('productCategories', row.id, '商品中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '分类管理',
  remoteKey: 'productCategories',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('productCategories', search, page, pageSize),
  form: {
    title: '分类',
    fields: formFields,
    /** 编辑回填：tag 空值兜底为 ''，enabled/sort 归一为数字，避免 NSelect 值类型不匹配 */
    toFormData: (row: any) => ({
      ...row,
      tag: row.tag ?? '',
      enabled: row.enabled == null ? 1 : Number(row.enabled),
      sort: row.sort == null ? 0 : Number(row.sort)
    }),
    onSubmit: async (data, editing) => {
      // 写库前归一化：tag 去空格，enabled 统一 1/0，sort 缺省 0
      const payload = {
        ...data,
        tag: data.tag ? String(data.tag).trim() : '',
        enabled: Number(data.enabled) === 0 ? 0 : 1,
        sort: Number(data.sort) || 0
      };
      if (editing) await store.update('productCategories', editing.id, payload, '商品中心', 'name');
      else await store.add('productCategories', payload, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
