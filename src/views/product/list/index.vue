<script setup lang="ts">
import { h, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { NImage } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';
import SpecGroupsEditor from './SpecGroupsEditor.vue';

defineOptions({
  name: 'product_list'
});

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

// 规格编辑弹窗
const specVisible = ref(false);
const specRow = ref<any>(null);
const specModel = ref<any[]>([]);

// 商品详情编辑弹窗
const detailVisible = ref(false);
const detailRow = ref<any>(null);
const detailModel = ref<Record<string, any>>({});

function openSpecEditor(row: any) {
  specRow.value = row;
  specModel.value = JSON.parse(JSON.stringify(row.specGroups || []));
  specVisible.value = true;
}

function saveSpec() {
  if (!specRow.value) return;
  const cleaned = specModel.value.filter(group => (group.label || '').trim() !== '温馨提示' && group.id !== 'tip');
  const removed = specModel.value.length - cleaned.length;
  if (removed > 0) {
    window.$message?.warning('已自动移除 ' + removed + ' 个「温馨提示」规格组，请改在「商品详情-饮用提示」中维护');
  }
  store.update('products', specRow.value.id, { specGroups: cleaned, specCount: cleaned.length }, '商品中心', 'name');
  specVisible.value = false;
  window.$message?.success('规格已保存');
  listRef.value?.reload();
}

function openDetailEditor(row: any) {
  detailRow.value = row;
  detailModel.value = {
    galleryImage: row.galleryImage || '',
    imageDisclaimer: row.imageDisclaimer || '',
    promotionText: row.promotionText || '',
    discountRate: row.discountRate ?? 1,
    ingredients: row.ingredients || '',
    allergens: row.allergens || '',
    cupCapacity: row.cupCapacity || '',
    tipsText: (row.tips || []).join('\n')
  };
  detailVisible.value = true;
}

function saveDetail() {
  if (!detailRow.value) return;
  const tips = String(detailModel.value.tipsText || '')
    .split('\n')
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0);
  const { tipsText: _ignored, ...rest } = detailModel.value;
  store.update('products', detailRow.value.id, { ...rest, tips }, '商品中心', 'name');
  detailVisible.value = false;
  window.$message?.success('商品详情已保存');
  listRef.value?.reload();
}

const columns: DataTableColumns<any> = [
  {
    title: '图片',
    key: 'image',
    width: 70,
    render: (row: any) =>
      row.image
        ? h(NImage, { src: row.image, width: 44, height: 44, objectFit: 'cover', style: 'border-radius:6px' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '商品编码', key: 'code', width: 110 },
  { title: '名称', key: 'name', minWidth: 110 },
  { title: '分类', key: 'category', width: 90 },
  { title: '标签', key: 'tags', width: 125, render: (row: any) => (row.tags || []).join('、') || '—' },
  {
    title: '规格数',
    key: 'specCount',
    width: 80,
    align: 'right',
    render: (row: any) => (row.specGroups?.length ?? row.specCount ?? 0)
  },
  { title: '原价(元)', key: 'originalPrice', width: 90, align: 'right', render: (row: any) => (row.originalPrice != null ? `¥${row.originalPrice}` : '—') },
  { title: '成本价(元)', key: 'costPrice', width: 88, align: 'right', render: (row: any) => (row.costPrice != null ? `¥${row.costPrice}` : '—') },
  { title: '平台分佣(元)', key: 'platformCommission', width: 95, align: 'right', render: (row: any) => (row.platformCommission != null ? `¥${row.platformCommission}` : '—') },
  { title: '储值价(元)', key: 'storedValuePrice', width: 88, align: 'right', render: (row: any) => (row.storedValuePrice != null ? `¥${row.storedValuePrice}` : '—') },
  {
    title: '门店',
    key: 'stores',
    width: 120,
    render: (row: any) => (row.stores || []).join('、') || row.store || '—'
  },
  {
    title: '上架状态',
    key: 'onSale',
    width: 90,
    render: renderTag('onSale', statusMap({ on: ['已上架', 'success'], off: ['已下架', 'default'] }))
  },
  {
    title: '分账规则',
    key: 'splitReady',
    width: 90,
    render: renderTag('splitReady', statusMap({ ready: ['完整', 'success'], incomplete: ['未完整', 'warning'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '商品', placeholder: '商品名称' },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: () => store.productCategories.filter((c: any) => c.enabled !== false).map((c: any) => ({ label: c.name, value: c.name }))
  },
  {
    key: 'onSale',
    label: '上架状态',
    type: 'select',
    options: [
      { label: '已上架', value: 'on' },
      { label: '已下架', value: 'off' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'image', label: '商品图片', type: 'image' },
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'category', label: '分类', type: 'select', options: () => store.productCategories.filter((c: any) => c.enabled !== false).map((c: any) => ({ label: c.name, value: c.name })) },
  { key: 'originalPrice', label: '原价(元)', type: 'number' },
  { key: 'costPrice', label: '成本价(元)', type: 'number' },
  { key: 'platformCommission', label: '平台分佣(元)', type: 'number' },
  { key: 'storedValuePrice', label: '储值价(元)', type: 'number' },
  { key: 'tags', label: '标签(逗号分隔)' },
  {
    key: 'stores',
    label: '门店',
    type: 'multiple',
    multiple: true,
    options: () => store.subjects.filter(s => s.type === 'store').map(s => ({ label: s.name, value: s.name }))
  },
  {
    key: 'onSale',
    label: '上架状态',
    type: 'select',
    options: [
      { label: '已上架', value: 'on' },
      { label: '已下架', value: 'off' }
    ]
  }
];

const toolbar: RowAction[] = [{ label: '新增商品', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '规格', type: 'info', handler: (row: any) => openSpecEditor(row) },
  { label: '详情', type: 'info', handler: (row: any) => openDetailEditor(row) },
  {
    label: '下架',
    type: 'warning',
    reasonPrompt: '确认下架该商品？（请填写备注）',
    handler: (row, reason) =>
      store.patch('products', row.id, { onSale: 'off', offSaleReason: reason, offSaleTime: new Date().toISOString().slice(0, 16) }, '商品中心', '下架', 'name', reason),
    visible: row => row.onSale === 'on'
  },
  {
    label: '上架',
    type: 'success',
    reasonPrompt: '确认上架该商品？（请填写备注）',
    handler: (row, reason) =>
      store.patch('products', row.id, { onSale: 'on', offSaleReason: null, offSaleTime: null }, '商品中心', '上架', 'name', reason),
    visible: row => row.onSale === 'off'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该商品？（请填写备注）',
    handler: (row, reason) => store.remove('products', row.id, '商品中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '商品管理',
  remoteKey: 'productCategories',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.products, search, page, pageSize),
  form: {
    title: '商品',
    fields: formFields,
    onSubmit: (data, editing) => {
      const tags = String(data.tags || '')
        .split(/[,，、]/)
        .map((s: string) => s.trim())
        .filter((s: string) => s.length > 0);
      const { tags: _ignored, ...rest } = data;
      const payload = { ...rest, tags };
      if (editing)
        store.update('products', editing.id, { ...payload, store: (data.stores || [])[0] || editing.store || '', specCount: editing.specGroups?.length ?? 0 }, '商品中心', 'name');
      else
        store.add('products', { ...payload, specCount: 0, specGroups: [], splitReady: 'ready', store: (data.stores || [])[0] || '' }, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <div class="page-root">

  <AdminListPage ref="listRef" :config="config" />

  <NModal v-model:show="specVisible" preset="card" title="商品规格编辑" class="w-680px">
    <div v-if="specRow" class="modal-body">
      <div class="modal-name">商品：{{ specRow.name }}</div>
      <SpecGroupsEditor v-model="specModel" />
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="specVisible = false">取消</NButton>
        <NButton type="primary" @click="saveSpec">保存</NButton>
      </div>
    </div>
  </NModal>

  <NModal v-model:show="detailVisible" preset="card" title="商品详情编辑" class="w-680px">
    <div v-if="detailRow" class="modal-body">
      <div class="modal-name">商品：{{ detailRow.name }}</div>
      <NForm label-placement="left" :label-width="100">
        <NFormItem label="详情大图"><NInput v-model:value="detailModel.galleryImage" /></NFormItem>
        <NFormItem label="促销文案"><NInput v-model:value="detailModel.promotionText" /></NFormItem>
        <NFormItem label="折扣率"><NInputNumber v-model:value="detailModel.discountRate" :min="0" :max="1" :step="0.01" /></NFormItem>
        <NFormItem label="配料"><NInput v-model:value="detailModel.ingredients" /></NFormItem>
        <NFormItem label="过敏原"><NInput v-model:value="detailModel.allergens" /></NFormItem>
        <NFormItem label="杯容量"><NInput v-model:value="detailModel.cupCapacity" /></NFormItem>
        <NFormItem label="图片免责"><NInput v-model:value="detailModel.imageDisclaimer" /></NFormItem>
        <NFormItem label="饮用提示">
          <NInput v-model:value="detailModel.tipsText" type="textarea" :rows="4" placeholder="每行一条提示" />
        </NFormItem>
      </NForm>
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="detailVisible = false">取消</NButton>
        <NButton type="primary" @click="saveDetail">保存</NButton>
      </div>
    </div>
  </NModal>
  </div>
</template>

<style scoped>
.modal-body {
  max-height: 60vh;
  overflow-y: auto;
}
.modal-name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}
</style>

