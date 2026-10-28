<script setup lang="ts">
defineOptions({
  name: 'MallCategory'
});

import { ref } from 'vue';
import { h } from 'vue';
import { NImage } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, FormField, SearchField } from '@/views/_shared/types';
import { renderDateTime, renderTag, statusMap } from '@/views/_shared/render';
import { createMallCategory, deleteMallCategory, fetchMallCategories, updateMallCategory } from '@/service/api/mall';
import type { MallCategory, MallCategoryPayload, MallCategoryStatus } from '@/service/api/mall';

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

const statusOptions = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' }
];

const columns: DataTableColumns<MallCategory> = [
  { title: '分类编码', key: 'code', width: 150 },
  {
    title: '分类图',
    key: 'iconImage',
    width: 84,
    render: (row: MallCategory) =>
      h(NImage, {
        src: row.iconImage || '',
        width: 40,
        height: 40,
        objectFit: 'cover',
        style: 'border-radius:50%;background:#f4f5f2'
      })
  },
  { title: '分类名称', key: 'name', minWidth: 160 },
  { title: '排序', key: 'sort', width: 90, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag(
      'status',
      statusMap({
        ENABLED: ['启用', 'success'],
        DISABLED: ['停用', 'default']
      })
    )
  },
  { title: '创建时间', key: 'createTime', width: 170, render: renderDateTime('createTime') },
  { title: '更新时间', key: 'updateTime', width: 170, render: renderDateTime('updateTime') }
];

const searchFields: SearchField[] = [
  { key: 'keyword', label: '分类', placeholder: '分类名称 / 编码' },
  { key: 'status', label: '状态', type: 'select', options: statusOptions }
];

const formFields: FormField[] = [
  {
    key: 'code',
    label: '分类编码',
    rules: [{ required: true, message: '请输入分类编码', trigger: ['input', 'blur'] }]
  },
  {
    key: 'name',
    label: '分类名称',
    rules: [{ required: true, message: '请输入分类名称', trigger: ['input', 'blur'] }]
  },
  {
    key: 'iconImage',
    label: '分类图',
    type: 'uploadImage',
    square: true,
    maxBytes: 5 * 1024 * 1024,
    placeholder: '上传 1:1 JPG/PNG'
  },
  { key: 'sort', label: '排序', type: 'number', placeholder: '数字越小越靠前' },
  { key: 'status', label: '状态', type: 'select', options: statusOptions }
];

function toPayload(data: Record<string, any>): MallCategoryPayload {
  return {
    code: String(data.code ?? '').trim(),
    name: String(data.name ?? '').trim(),
    iconImage: String(data.iconImage ?? '').trim() || undefined,
    sort: Number(data.sort) || 0,
    status: data.status === 'DISABLED' ? 'DISABLED' : ('ENABLED' as MallCategoryStatus)
  };
}

const config: AdminListConfig = {
  title: '商城分类',
  columns,
  searchFields,
  toolbar: [{ label: '新增分类', type: 'primary', modal: 'add' }],
  rowActions: [
    { label: '编辑', type: 'primary', modal: 'edit' },
    {
      label: '删除',
      type: 'error',
      confirm: '确认删除该分类？分类下存在商品时后端会拒绝删除。',
      handler: async (row: MallCategory) => {
        await deleteMallCategory(row.id);
        window.$message?.success('分类已删除');
      }
    }
  ],
  loadData: async ({ page, pageSize, search }) => {
    const all = await fetchMallCategories();
    const keyword = String(search?.keyword ?? '')
      .trim()
      .toLowerCase();
    const status = String(search?.status ?? '').trim();
    const filtered = all.filter(item => {
      const matchesKeyword =
        !keyword ||
        String(item.name ?? '')
          .toLowerCase()
          .includes(keyword) ||
        String(item.code ?? '')
          .toLowerCase()
          .includes(keyword);
      const matchesStatus = !status || item.status === status;
      return matchesKeyword && matchesStatus;
    });
    const start = (page - 1) * pageSize;
    return {
      data: filtered.slice(start, start + pageSize),
      total: filtered.length
    };
  },
  form: {
    title: '商城分类',
    fields: formFields,
    toFormData: (row: MallCategory) => ({
      code: row.code,
      name: row.name,
      iconImage: row.iconImage ?? '',
      sort: row.sort ?? 0,
      status: row.status ?? 'ENABLED'
    }),
    onSubmit: async (data, editing) => {
      const payload = toPayload(data);
      if (!payload.code) throw new Error('分类编码必填');
      if (!payload.name) throw new Error('分类名称必填');
      if (editing) await updateMallCategory(editing.id, payload);
      else await createMallCategory(payload);
      window.$message?.success(editing ? '分类已更新' : '分类已创建');
      listRef.value?.reload();
    }
  }
};
</script>

<template>
  <div>
    <AdminListPage ref="listRef" :config="config" />
  </div>
</template>

<style scoped>
:deep(.n-data-table) {
  font-size: 14px;
}
</style>
