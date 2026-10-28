<script setup lang="ts">
defineOptions({
  name: 'MallProduct'
});

import { computed, h, onMounted, reactive, ref } from 'vue';
import {
  NButton,
  NDrawer,
  NDrawerContent,
  NForm,
  NFormItem,
  NImage,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpin,
  NTag
} from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import { fetchAdminSplitRules } from '@/service/api/product';
import { fetchSubjectsByType } from '@/service/api/crud';
import { formatFen, formatDateTime, renderTag, statusMap } from '@/views/_shared/render';
import {
  adjustMallSkuStock,
  deleteMallProduct,
  createMallProduct,
  fetchMallCategories,
  fetchMallProduct,
  fetchMallProducts,
  saveMallProductSkus,
  updateMallProduct,
  updateMallProductStatus
} from '@/service/api/mall';
import type {
  MallCategory,
  MallProductAdmin,
  MallProductPayload,
  MallProductStatus,
  MallSkuAdmin,
  MallSkuPayload
} from '@/service/api/mall';

interface ProductFormState {
  productCode: string;
  categoryId: number | null;
  name: string;
  subtitle: string;
  specName: string;
  coverImage: string;
  description: string;
  attributionStoreSubjectId: number | null;
  attributionChannelSubjectId: number | null;
  splitRuleId: number | null;
  status: MallProductStatus;
  sort: number;
}

interface SkuRow {
  id?: number;
  skuCode: string;
  specValue: string;
  image: string;
  salePrice: number;
  originalPrice: number;
  costPrice: number;
  platformCommission: number;
  distributionCommission: number;
  supplierSubjectId: number | null;
  stock: number;
  lockedStock: number;
  availableStock?: number;
  sort: number;
}

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const categories = ref<MallCategory[]>([]);
const drawerVisible = ref(false);
const drawerLoading = ref(false);
const saving = ref(false);
const editingProduct = ref<MallProductAdmin | null>(null);
const skuRows = ref<SkuRow[]>([]);

const stockVisible = ref(false);
const stockSaving = ref(false);
const stockProductId = ref<number | null>(null);
const stockSkuId = ref<number | null>(null);
const stockSkus = ref<MallSkuAdmin[]>([]);
const stockValue = ref<number | null>(0);
const stockRemark = ref('');

const statusOptions = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已上架', value: 'ON_SALE' },
  { label: '已下架', value: 'OFF_SALE' }
];

interface SubjectOption {
  id?: number | string;
  name?: string;
  code?: string;
}

const subjects = ref<SubjectOption[]>([]);
const splitRules = ref<any[]>([]);

function subjectOptions() {
  return subjects.value
    .map(subject => ({
      label: subject.name || subject.code || `主体 ${subject.id ?? subject.code}`,
      value: Number(subject.id)
    }))
    .filter(option => Number.isFinite(option.value));
}

const categoryOptions = computed(() =>
  categories.value.map(category => ({ label: category.name, value: category.id }))
);
const searchCategoryOptions = computed(() =>
  categories.value.map(category => ({ label: category.name, value: String(category.id) }))
);
const drawerTitle = computed(() => (editingProduct.value ? '编辑商城商品' : '新增商城商品'));
const stockSkuOptions = computed(() =>
  stockSkus.value
    .filter(sku => sku.id != null)
    .map(sku => ({
      label: `${sku.specValue || sku.skuCode || `SKU ${sku.id}`}（总库存 ${sku.stock ?? 0} / 可用 ${sku.availableStock ?? 0}）`,
      value: sku.id as number
    }))
);
const selectedStockSku = computed(() => stockSkus.value.find(sku => sku.id === stockSkuId.value) ?? null);
const stockMin = computed(() => Math.max(0, Number(selectedStockSku.value?.lockedStock) || 0));
const stockAvailableAfter = computed(() =>
  Math.max(0, (Number(stockValue.value) || 0) - (Number(selectedStockSku.value?.lockedStock) || 0))
);

const form = reactive<ProductFormState>(emptyProduct());

function emptyProduct(): ProductFormState {
  return {
    productCode: '',
    categoryId: null,
    name: '',
    subtitle: '',
    specName: '规格',
    coverImage: '',
    description: '',
    attributionStoreSubjectId: null,
    attributionChannelSubjectId: null,
    splitRuleId: null,
    status: 'DRAFT',
    sort: 0
  };
}

function emptySku(): SkuRow {
  return {
    skuCode: '',
    specValue: '',
    image: '',
    salePrice: 0,
    originalPrice: 0,
    costPrice: 0,
    platformCommission: 0,
    distributionCommission: 0,
    supplierSubjectId: null,
    stock: 0,
    lockedStock: 0,
    sort: skuRows.value.length
  };
}

function resetForm() {
  Object.assign(form, emptyProduct());
  skuRows.value = [emptySku()];
}

function toSkuRow(sku: MallSkuAdmin): SkuRow {
  return {
    id: sku.id,
    skuCode: sku.skuCode ?? '',
    specValue: sku.specValue ?? '',
    image: sku.image ?? '',
    salePrice: Number(sku.salePrice) || 0,
    originalPrice: Number(sku.originalPrice) || 0,
    costPrice: Number(sku.costPrice) || 0,
    platformCommission: Number(sku.platformCommission) || 0,
    distributionCommission: Number(sku.distributionCommission) || 0,
    supplierSubjectId: sku.supplierSubjectId ?? null,
    stock: Number(sku.stock) || 0,
    lockedStock: Number(sku.lockedStock) || 0,
    availableStock: Number(sku.availableStock) || 0,
    sort: Number(sku.sort) || 0
  };
}

async function loadFormOptions() {
  const [stores, channels, suppliers, ruleResponse] = await Promise.all([
    fetchSubjectsByType('STORE'),
    fetchSubjectsByType('CHANNEL'),
    fetchSubjectsByType('SUPPLIER'),
    fetchAdminSplitRules({ current: 1, size: 100 })
  ]);
  subjects.value = [
    ...(Array.isArray(stores) ? stores : []),
    ...(Array.isArray(channels) ? channels : []),
    ...(Array.isArray(suppliers) ? suppliers : [])
  ];
  const ruleResult = ruleResponse as any;
  splitRules.value = ruleResult?.records ?? [];
}
const storeOptions = computed(() => subjectOptions());
const channelOptions = computed(() => subjectOptions());
const supplierOptions = computed(() => subjectOptions());
const splitRuleOptions = computed(() =>
  splitRules.value.map(rule => ({
    label: `${rule.name || rule.code}（${rule.scope === 'GLOBAL' ? '全局' : rule.scope === 'PRODUCT' ? '商品' : rule.scope}）`,
    value: Number(rule.id)
  }))
);

async function loadCategories() {
  try {
    categories.value = await fetchMallCategories();
  } catch (error: any) {
    window.$message?.error(error?.message || '商城分类加载失败');
  }
}

onMounted(() => {
  loadCategories();
  loadFormOptions().catch(error => {
    window.$message?.error(error?.message || '商品表单选项加载失败');
  });
});

function openCreate() {
  editingProduct.value = null;
  resetForm();
  drawerVisible.value = true;
}

async function openEdit(row: MallProductAdmin) {
  resetForm();
  drawerVisible.value = true;
  drawerLoading.value = true;
  try {
    const detail = await fetchMallProduct(row.id);
    editingProduct.value = detail;
    Object.assign(form, {
      productCode: detail.productCode ?? '',
      categoryId: detail.categoryId ?? null,
      name: detail.name ?? '',
      subtitle: detail.subtitle ?? '',
      specName: detail.specName ?? '规格',
      coverImage: detail.coverImage ?? '',
      description: detail.description ?? '',
      attributionStoreSubjectId: detail.attributionStoreSubjectId ?? null,
      attributionChannelSubjectId: detail.attributionChannelSubjectId ?? null,
      splitRuleId: detail.splitRuleId ?? null,
      status: detail.status ?? 'DRAFT',
      sort: Number(detail.sort) || 0
    });
    skuRows.value = (detail.skus ?? []).map(toSkuRow);
    if (!skuRows.value.length) skuRows.value = [emptySku()];
  } catch (error: any) {
    drawerVisible.value = false;
    window.$message?.error(error?.message || '商城商品详情加载失败');
  } finally {
    drawerLoading.value = false;
  }
}

function addSku() {
  skuRows.value.push(emptySku());
}

function removeSku(index: number) {
  skuRows.value.splice(index, 1);
  if (!skuRows.value.length) skuRows.value.push(emptySku());
}

function productPayload(status: MallProductStatus): MallProductPayload {
  return {
    productCode: form.productCode.trim(),
    categoryId: Number(form.categoryId),
    name: form.name.trim(),
    subtitle: form.subtitle.trim() || undefined,
    specName: form.specName.trim() || '规格',
    coverImage: form.coverImage.trim() || undefined,
    galleryImages: editingProduct.value?.galleryImages ?? [],
    description: form.description.trim() || undefined,
    attributionStoreSubjectId: Number(form.attributionStoreSubjectId),
    attributionChannelSubjectId: form.attributionChannelSubjectId || undefined,
    splitRuleId: form.splitRuleId || undefined,
    status,
    sort: Number(form.sort) || 0
  };
}

function skuPayloads(): MallSkuPayload[] {
  return skuRows.value.map(sku => ({
    id: sku.id,
    skuCode: sku.skuCode.trim(),
    specValue: sku.specValue.trim(),
    image: sku.image.trim() || undefined,
    salePrice: Math.max(0, Number(sku.salePrice) || 0),
    originalPrice: Math.max(0, Number(sku.originalPrice) || 0),
    costPrice: Math.max(0, Number(sku.costPrice) || 0),
    platformCommission: Math.max(0, Number(sku.platformCommission) || 0),
    distributionCommission: Math.max(0, Number(sku.distributionCommission) || 0),
    supplierSubjectId: Number(sku.supplierSubjectId),
    stock: Math.max(0, Number(sku.stock) || 0),
    sort: Number(sku.sort) || 0
  }));
}

function validateProduct(): string | null {
  if (!form.productCode.trim()) return '请填写商品编码';
  if (!form.categoryId) return '请选择商城分类';
  if (!form.name.trim()) return '请填写商品名称';
  if (!form.attributionStoreSubjectId) return '请填写归属门店ID';
  if (!skuRows.value.length) return '至少需要维护一个 SKU';

  const codes = new Set<string>();
  const specs = new Set<string>();
  for (const [index, sku] of skuRows.value.entries()) {
    const rowLabel = `第 ${index + 1} 个 SKU`;
    if (!sku.skuCode.trim()) return `${rowLabel}缺少 SKU 编码`;
    if (!sku.specValue.trim()) return `${rowLabel}缺少规格值`;
    if (!sku.supplierSubjectId) return `${rowLabel}缺少供应商ID`;
    if (Number(sku.stock) < Number(sku.lockedStock)) return `${rowLabel}库存不能低于已锁定库存`;
    if (codes.has(sku.skuCode.trim().toLowerCase())) return `${rowLabel}的 SKU 编码重复`;
    if (specs.has(sku.specValue.trim())) return `${rowLabel}的规格值重复`;
    codes.add(sku.skuCode.trim().toLowerCase());
    specs.add(sku.specValue.trim());
  }
  return null;
}

async function saveProduct() {
  const validationError = validateProduct();
  if (validationError) {
    window.$message?.warning(validationError);
    return;
  }

  saving.value = true;
  try {
    let productId = editingProduct.value?.id;
    let needStatusPatch = false;

    if (!productId) {
      // 先创建草稿，待 SKU 保存后再切到运营选择的状态，避免上架校验早于 SKU 落库。
      const created = await createMallProduct(productPayload('DRAFT'));
      productId = created.id;
      needStatusPatch = form.status !== 'DRAFT';
    } else {
      const currentStatus = editingProduct.value?.status ?? 'DRAFT';
      const safeStatus: MallProductStatus =
        form.status === 'ON_SALE' && currentStatus !== 'ON_SALE' ? currentStatus : form.status;
      await updateMallProduct(productId, productPayload(safeStatus));
      needStatusPatch = form.status !== safeStatus;
    }

    await saveMallProductSkus(productId, skuPayloads());
    if (needStatusPatch) await updateMallProductStatus(productId, form.status);

    window.$message?.success(editingProduct.value ? '商城商品已更新' : '商城商品已创建');
    drawerVisible.value = false;
    listRef.value?.reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '商城商品保存失败');
  } finally {
    saving.value = false;
  }
}

function openStockModal(product: MallProductAdmin, sku?: MallSkuAdmin | SkuRow) {
  const available = (product.skus ?? []).filter(item => item.id != null);
  stockSkus.value = available;
  stockProductId.value = product.id;
  const matched =
    sku?.id != null ? available.find(item => item.id === sku.id) : available.length === 1 ? available[0] : undefined;
  stockSkuId.value = matched?.id ?? null;
  stockValue.value = Number(matched?.stock) || 0;
  stockRemark.value = '';
  stockVisible.value = true;
}

function onStockSkuChange(value: number | null) {
  stockSkuId.value = value;
  const sku = stockSkus.value.find(item => item.id === value);
  stockValue.value = Number(sku?.stock) || 0;
}

function openDrawerSkuStock(sku: SkuRow) {
  if (!editingProduct.value || !sku.id) {
    window.$message?.warning('请先保存 SKU 后再调整库存');
    return;
  }
  openStockModal(editingProduct.value, sku);
}

async function saveStock() {
  if (!stockProductId.value || !stockSkuId.value) {
    window.$message?.warning('请选择 SKU');
    return;
  }
  const remark = stockRemark.value.trim();
  if (!remark) {
    window.$message?.warning('库存调整必须填写备注');
    return;
  }
  if (Number(stockValue.value) < stockMin.value) {
    window.$message?.warning(`库存不能低于已锁定的 ${stockMin.value} 件`);
    return;
  }

  stockSaving.value = true;
  try {
    const updated = await adjustMallSkuStock(stockProductId.value, stockSkuId.value, {
      stock: Math.max(0, Number(stockValue.value) || 0),
      remark
    });
    const drawerSku = skuRows.value.find(sku => sku.id === stockSkuId.value);
    if (drawerSku) {
      drawerSku.stock = Number(updated.stock) || 0;
      drawerSku.lockedStock = Number(updated.lockedStock) || 0;
      drawerSku.availableStock = Number(updated.availableStock) || 0;
    }
    window.$message?.success('库存已调整');
    stockVisible.value = false;
    listRef.value?.reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '库存调整失败');
  } finally {
    stockSaving.value = false;
  }
}

async function deleteProduct(row: MallProductAdmin) {
  if (row.deletable === false) {
    window.$message?.warning(row.deleteBlockedReason || '商品有历史订单或库存流水，只能下架，不能删除');
    return;
  }
  await deleteMallProduct(row.id);
  window.$message?.success('商品已删除');
  listRef.value?.reload();
}

async function changeStatus(row: MallProductAdmin, status: MallProductStatus) {
  await updateMallProductStatus(row.id, status);
  window.$message?.success(status === 'ON_SALE' ? '商品已上架' : '商品已下架');
}

const columns: DataTableColumns<any> = [
  {
    title: '主图',
    key: 'coverImage',
    width: 76,
    render: (row: MallProductAdmin) =>
      row.coverImage
        ? h(NImage, {
            src: row.coverImage,
            width: 44,
            height: 44,
            objectFit: 'cover',
            style: 'border-radius:6px'
          })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '商品编码', key: 'productCode', width: 130 },
  { title: '商品名称', key: 'name', minWidth: 160 },
  { title: '分类', key: 'categoryName', width: 110, render: (row: any) => row.categoryName || '—' },
  {
    title: '价格(元)',
    key: 'price',
    width: 125,
    align: 'right',
    render: (row: MallProductAdmin) => {
      if (row.minPrice == null && row.maxPrice == null) return '—';
      if (row.minPrice == null || row.maxPrice == null || row.minPrice === row.maxPrice) {
        return `¥${formatFen(row.minPrice ?? row.maxPrice)}`;
      }
      return `¥${formatFen(row.minPrice)} - ${formatFen(row.maxPrice)}`;
    }
  },
  {
    title: '可用库存',
    key: 'availableStock',
    width: 110,
    align: 'right',
    render: (row: MallProductAdmin) => {
      const stock = Number(row.availableStock) || 0;
      if (stock <= 0) return h(NTag, { type: 'error', size: 'small' }, { default: () => '售罄' });
      if (stock <= 10) return h(NTag, { type: 'warning', size: 'small' }, { default: () => `低库存 ${stock}` });
      return String(stock);
    }
  },
  { title: 'SKU 数', key: 'skus', width: 82, align: 'right', render: (row: any) => row.skus?.length ?? 0 },
  {
    title: '归属门店',
    key: 'attributionStoreName',
    minWidth: 130,
    render: (row: any) => row.attributionStoreName || row.attributionStoreSubjectId || '—'
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag(
      'status',
      statusMap({
        DRAFT: ['草稿', 'default'],
        ON_SALE: ['已上架', 'success'],
        OFF_SALE: ['已下架', 'warning']
      })
    )
  },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  { title: '更新时间', key: 'updateTime', width: 170, render: (row: any) => formatDateTime(row.updateTime) }
];

const searchFields: SearchField[] = [
  { key: 'keyword', label: '商品', placeholder: '商品名称 / 编码' },
  { key: 'categoryId', label: '分类', type: 'select', options: () => searchCategoryOptions.value },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: statusOptions
  }
];

const config: AdminListConfig = {
  title: '商城商品',
  columns,
  searchFields,
  refreshOnEnter: true,
  toolbar: [
    {
      label: '新增商品',
      type: 'primary',
      handler: () => openCreate()
    }
  ],
  rowActions: [
    {
      label: '编辑',
      type: 'primary',
      handler: (row: MallProductAdmin) => openEdit(row)
    },
    {
      label: '上架',
      type: 'success',
      confirm: '确认上架该商城商品？',
      handler: (row: MallProductAdmin) => changeStatus(row, 'ON_SALE'),
      visible: (row: MallProductAdmin) => row.status !== 'ON_SALE'
    },
    {
      label: '下架',
      type: 'warning',
      confirm: '确认下架该商城商品？',
      handler: (row: MallProductAdmin) => changeStatus(row, 'OFF_SALE'),
      visible: (row: MallProductAdmin) => row.status === 'ON_SALE'
    },
    {
      label: '删除',
      type: 'error',
      confirm: '确认删除该商城商品？有历史订单或库存流水的商品只能下架。',
      handler: (row: MallProductAdmin) => deleteProduct(row),
      visible: (row: MallProductAdmin) => row.deletable !== false
    },
    {
      label: '库存调整',
      type: 'info',
      handler: (row: MallProductAdmin) => openStockModal(row),
      visible: (row: MallProductAdmin) => Boolean(row.skus?.some(sku => sku.id != null))
    }
  ],
  loadData: async ({ page, pageSize, search }) => {
    const result = await fetchMallProducts({
      page,
      size: pageSize,
      categoryId: search?.categoryId ? Number(search.categoryId) : undefined,
      status: search?.status || undefined,
      keyword: String(search?.keyword ?? '').trim() || undefined
    });
    return {
      data: result.records ?? [],
      total: Number(result.total) || 0
    };
  }
};
</script>

<template>
  <div class="mall-product-page">
    <AdminListPage ref="listRef" :config="config" />

    <NDrawer v-model:show="drawerVisible" :width="980" placement="right">
      <NDrawerContent :title="drawerTitle" closable :native-scrollbar="false">
        <NSpin :show="drawerLoading">
          <NForm label-placement="top">
            <div class="form-section-title">商品基础信息</div>
            <div class="form-grid">
              <NFormItem label="商品编码" required>
                <NInput v-model:value="form.productCode" placeholder="如 MALL-001" />
              </NFormItem>
              <NFormItem label="商城分类" required>
                <NSelect
                  v-model:value="form.categoryId"
                  :options="categoryOptions"
                  filterable
                  placeholder="请选择分类"
                />
              </NFormItem>
              <NFormItem label="商品名称" required>
                <NInput v-model:value="form.name" placeholder="商城端展示名称" />
              </NFormItem>
              <NFormItem label="副标题">
                <NInput v-model:value="form.subtitle" placeholder="商品卖点或简短说明" />
              </NFormItem>
              <NFormItem label="规格名">
                <NInput v-model:value="form.specName" placeholder="如 口味、规格" />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect v-model:value="form.status" :options="statusOptions" />
              </NFormItem>
              <NFormItem label="归属门店" required>
                <NSelect
                  v-model:value="form.attributionStoreSubjectId"
                  :options="storeOptions"
                  filterable
                  placeholder="请选择门店"
                />
              </NFormItem>
              <NFormItem label="归属渠道（可选）">
                <NSelect
                  v-model:value="form.attributionChannelSubjectId"
                  :options="channelOptions"
                  filterable
                  clearable
                  placeholder="请选择渠道"
                />
              </NFormItem>
              <NFormItem label="分账规则（可选）">
                <NSelect
                  v-model:value="form.splitRuleId"
                  :options="splitRuleOptions"
                  filterable
                  clearable
                  placeholder="请选择分账规则"
                />
              </NFormItem>
              <NFormItem label="排序">
                <NInputNumber v-model:value="form.sort" :precision="0" class="w-full" />
              </NFormItem>
            </div>

            <NFormItem label="主图地址">
              <NInput v-model:value="form.coverImage" placeholder="https://..." />
            </NFormItem>
            <NImage
              v-if="form.coverImage"
              :src="form.coverImage"
              width="120"
              height="120"
              object-fit="cover"
              class="cover-preview"
            />
            <NFormItem label="商品详情">
              <NInput
                v-model:value="form.description"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 10 }"
                placeholder="商品详情文案"
              />
            </NFormItem>

            <div class="sku-heading">
              <div>
                <div class="form-section-title">单维 SKU</div>
                <div class="form-hint">金额单位均为分；库存调整必须填写备注。</div>
              </div>
              <NButton type="primary" dashed @click="addSku">新增 SKU</NButton>
            </div>

            <div class="sku-list">
              <div v-for="(sku, index) in skuRows" :key="sku.id ?? `new-${index}`" class="sku-card">
                <div class="sku-card-head">
                  <strong>SKU {{ index + 1 }}</strong>
                  <div class="sku-card-actions">
                    <span v-if="sku.id" class="stock-lock-hint">已锁定 {{ sku.lockedStock }} 件</span>
                    <NButton size="tiny" type="info" secondary :disabled="!sku.id" @click="openDrawerSkuStock(sku)">
                      库存调整
                    </NButton>
                    <NButton size="tiny" type="error" quaternary @click="removeSku(index)">删除</NButton>
                  </div>
                </div>
                <div class="sku-grid">
                  <NFormItem label="SKU 编码" required>
                    <NInput v-model:value="sku.skuCode" placeholder="唯一编码" />
                  </NFormItem>
                  <NFormItem label="规格值" required>
                    <NInput v-model:value="sku.specValue" placeholder="如 原味" />
                  </NFormItem>
                  <NFormItem label="图片地址">
                    <NInput v-model:value="sku.image" placeholder="https://..." />
                  </NFormItem>
                  <NFormItem label="售价(分)" required>
                    <NInputNumber v-model:value="sku.salePrice" :min="0" :precision="0" class="w-full" />
                  </NFormItem>
                  <NFormItem label="划线价(分)">
                    <NInputNumber v-model:value="sku.originalPrice" :min="0" :precision="0" class="w-full" />
                  </NFormItem>
                  <NFormItem label="成本价(分)">
                    <NInputNumber v-model:value="sku.costPrice" :min="0" :precision="0" class="w-full" />
                  </NFormItem>
                  <NFormItem label="平台提成(分)">
                    <NFormItem label="分销佣金(分)">
                      <NInputNumber v-model:value="sku.distributionCommission" :min="0" :precision="0" class="w-full" />
                    </NFormItem>
                    <NInputNumber v-model:value="sku.platformCommission" :min="0" :precision="0" class="w-full" />
                  </NFormItem>
                  <NFormItem label="供应商" required>
                    <NSelect
                      v-model:value="sku.supplierSubjectId"
                      :options="supplierOptions"
                      filterable
                      placeholder="请选择供应商"
                    />
                  </NFormItem>
                  <NFormItem label="实物库存" required>
                    <NInputNumber v-model:value="sku.stock" :min="sku.lockedStock" :precision="0" class="w-full" />
                  </NFormItem>
                  <NFormItem label="排序">
                    <NInputNumber v-model:value="sku.sort" :precision="0" class="w-full" />
                  </NFormItem>
                </div>
              </div>
            </div>
          </NForm>
        </NSpin>

        <template #footer>
          <div class="drawer-footer">
            <NButton :disabled="saving" @click="drawerVisible = false">取消</NButton>
            <NButton type="primary" :loading="saving" @click="saveProduct">保存商品</NButton>
          </div>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="stockVisible" preset="card" title="库存调整" class="w-480px">
      <NForm label-placement="top">
        <NFormItem label="SKU" required>
          <NSelect
            v-model:value="stockSkuId"
            :options="stockSkuOptions"
            placeholder="请选择 SKU"
            @update:value="onStockSkuChange"
          />
        </NFormItem>
        <NFormItem :label="`调整后库存（已锁定 ${stockMin} 件）`" required>
          <NInputNumber v-model:value="stockValue" :min="stockMin" :precision="0" class="w-full" />
        </NFormItem>
        <NFormItem label="调整备注" required>
          <NInput
            v-model:value="stockRemark"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-count
            placeholder="请说明补货、盘点或纠错原因"
          />
        </NFormItem>
      </NForm>
      <div class="stock-availability-hint">调整后可用库存：{{ stockAvailableAfter }}（总库存 - 已锁定库存）</div>
      <template #footer>
        <div class="drawer-footer">
          <NButton :disabled="stockSaving" @click="stockVisible = false">取消</NButton>
          <NButton type="primary" :loading="stockSaving" @click="saveStock">确认调整</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.stock-availability-hint {
  margin-top: 8px;
  color: #9b9b96;
  font-size: 13px;
  line-height: 1.6;
}
.mall-product-page {
  min-width: 0;
}
.form-section-title {
  margin-bottom: 12px;
  color: #333;
  font-size: 15px;
  font-weight: 600;
}
.form-hint {
  color: #9b9b96;
  font-size: 13px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.cover-preview {
  margin: -4px 0 16px;
  border: 1px solid #eee;
  border-radius: 8px;
}
.sku-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 8px 0 12px;
}
.sku-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sku-card {
  padding: 14px 16px 0;
  border: 1px solid #ececea;
  border-radius: 10px;
  background: #fafaf8;
}
.sku-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.sku-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stock-lock-hint {
  color: #9b9b96;
  font-size: 12px;
}
.sku-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 14px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
@media (max-width: 900px) {
  .form-grid,
  .sku-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .form-grid,
  .sku-grid {
    grid-template-columns: 1fr;
  }
  .sku-card-head,
  .sku-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
