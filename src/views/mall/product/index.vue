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
import { fetchSubjectsByType } from '@/service/api/crud';
import { formatFen, formatDateTime, renderTag, statusMap } from '@/views/_shared/render';
import { ADMIN_IMAGE_FALLBACK, useAdminImageUrl } from '@/views/_shared/use-admin-image-url';
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
import ProductImageUpload from './ProductImageUpload.vue';
import ProductDetailImages from './ProductDetailImages.vue';
import ProductGalleryImages from './ProductGalleryImages.vue';
import { fenToYuan, yuanToFen } from './money';

interface ProductFormState {
  productCode: string;
  categoryId: number | null;
  name: string;
  subtitle: string;
  specName: string;
  sort: number;
  galleryImages: string[];
  detailImages: string[];
  description: string;
  supplierSubjectId: number | null;
  status: MallProductStatus;
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
  stock: number;
  lockedStock: number;
  availableStock?: number;
}

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const adminImageUrl = useAdminImageUrl();
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
    sort: 0,
    galleryImages: [],
    detailImages: [],
    description: '',
    supplierSubjectId: null,
    status: 'DRAFT'
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
    stock: 0,
    lockedStock: 0
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
    salePrice: fenToYuan(sku.salePrice),
    originalPrice: fenToYuan(sku.originalPrice),
    costPrice: fenToYuan(sku.costPrice),
    platformCommission: fenToYuan(sku.platformCommission),
    distributionCommission: fenToYuan(sku.distributionCommission),
    stock: Number(sku.stock) || 0,
    lockedStock: Number(sku.lockedStock) || 0,
    availableStock: Number(sku.availableStock) || 0
  };
}

async function loadFormOptions() {
  const suppliers = await fetchSubjectsByType('SUPPLIER');
  subjects.value = Array.isArray(suppliers) ? suppliers : [];
}
const supplierOptions = computed(() => subjectOptions());

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
      sort: Number(detail.sort) || 0,
      galleryImages:
        Array.isArray(detail.galleryImages) && detail.galleryImages.length
          ? detail.galleryImages.slice()
          : detail.coverImage
            ? [detail.coverImage]
            : [],
      detailImages: Array.isArray(detail.detailImages) ? detail.detailImages.slice() : [],
      description: detail.description ?? '',
      supplierSubjectId: detail.supplierSubjectId ?? null,
      status: detail.status ?? 'DRAFT'
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
    sort: Math.max(0, Math.min(9999, Number(form.sort) || 0)),
    coverImage: form.galleryImages[0] || undefined,
    galleryImages: form.galleryImages.map(image => image.trim()).filter(Boolean),
    detailImages: form.detailImages.map(image => image.trim()).filter(Boolean),
    description: form.description.trim() || undefined,
    supplierSubjectId: Number(form.supplierSubjectId),
    status
  };
}

function skuPayloads(): MallSkuPayload[] {
  return skuRows.value.map(sku => ({
    id: sku.id,
    skuCode: sku.skuCode.trim() || undefined,
    specValue: sku.specValue.trim(),
    image: sku.image.trim() || undefined,
    salePrice: Math.max(0, yuanToFen(sku.salePrice)),
    originalPrice: Math.max(0, yuanToFen(sku.originalPrice)),
    costPrice: Math.max(0, yuanToFen(sku.costPrice)),
    platformCommission: Math.max(0, yuanToFen(sku.platformCommission)),
    distributionCommission: Math.max(0, yuanToFen(sku.distributionCommission)),
    stock: Math.max(0, Number(sku.stock) || 0)
  }));
}

function validateProduct(): string | null {
  if (!form.productCode.trim()) return '请填写商品编码';
  if (!form.categoryId) return '请选择商城分类';
  if (!form.name.trim()) return '请填写商品名称';
  if (!form.galleryImages.length) return '请至少上传一张商品主图';
  if (!form.supplierSubjectId) return '请选择商品供应商';
  if (!skuRows.value.length) return '至少需要维护一个 SKU';

  const codes = new Set<string>();
  const specs = new Set<string>();
  for (const [index, sku] of skuRows.value.entries()) {
    const rowLabel = `第 ${index + 1} 个 SKU`;
    if (!sku.specValue.trim()) return `${rowLabel}缺少规格值`;
    if (Number(sku.stock) < Number(sku.lockedStock)) return `${rowLabel}库存不能低于已锁定库存`;
    const code = sku.skuCode.trim();
    if (code && codes.has(code.toLowerCase())) return `${rowLabel}的 SKU 编码重复`;
    if (specs.has(sku.specValue.trim())) return `${rowLabel}的规格值重复`;
    if (code) codes.add(code.toLowerCase());
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
            src: adminImageUrl(row.coverImage),
            fallbackSrc: ADMIN_IMAGE_FALLBACK,
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
    title: '排序号',
    key: 'sort',
    width: 96,
    align: 'right',
    sorter: true,
    render: (row: MallProductAdmin) => String(row.sort ?? 0)
  },
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
  {
    title: '销量',
    key: 'salesCount',
    width: 92,
    align: 'right',
    // 仅展示，不开放排序：sales_count 目前不随订单累加，排序结果恒定，会误导运营。
    render: (row: MallProductAdmin) => String(row.salesCount ?? 0)
  },
  {
    title: 'SKU 数',
    key: 'skus',
    width: 82,
    align: 'right',
    render: (row: any) => row.skus?.length ?? 0
  },
  {
    title: '供应商',
    key: 'supplierName',
    minWidth: 130,
    render: (row: MallProductAdmin) => row.supplierName || '待配置'
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
  { title: '更新时间', key: 'updateTime', width: 170, sorter: true, render: (row: any) => formatDateTime(row.updateTime) },
  { title: '创建时间', key: 'createTime', width: 170, sorter: true, render: (row: any) => formatDateTime(row.createTime) }
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
  remoteSort: true,
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
  loadData: async ({ page, pageSize, search, sortBy, sortOrder }) => {
    const result = await fetchMallProducts({
      page,
      size: pageSize,
      categoryId: search?.categoryId ? Number(search.categoryId) : undefined,
      status: search?.status || undefined,
      keyword: String(search?.keyword ?? '').trim() || undefined,
      sortBy,
      sortOrder
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

    <NDrawer v-model:show="drawerVisible" :width="1080" placement="right">
      <NDrawerContent :title="drawerTitle" closable :native-scrollbar="false">
        <NSpin :show="drawerLoading">
          <NForm label-placement="top">
            <div class="form-section-title">商品基础信息</div>
            <div class="form-hint" style="margin-bottom: 12px">
              排序号越小越靠前，影响小程序商城商品列表顺序；同序号按创建先后排列。
            </div>
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
              <NFormItem label="排序号">
                <NInputNumber
                  v-model:value="form.sort"
                  :min="0"
                  :max="9999"
                  :precision="0"
                  :show-button="false"
                  placeholder="数字越小越靠前"
                />
              </NFormItem>
              <NFormItem label="SPU 供应商" required>
                <NSelect
                  v-model:value="form.supplierSubjectId"
                  :options="supplierOptions"
                  filterable
                  placeholder="请选择供应商"
                />
              </NFormItem>
            </div>

            <NFormItem label="商品主图轮播" required>
              <ProductGalleryImages v-model="form.galleryImages" :disabled="saving" />
            </NFormItem>

            <div class="sku-heading">
              <div>
                <div class="form-section-title">SKU 规格</div>
                <div class="form-hint">金额均按元填写；SKU 编码保存时自动生成，图片为空时使用商品主图。</div>
              </div>
              <NButton type="primary" dashed @click="addSku">新增 SKU</NButton>
            </div>

            <div class="sku-table">
              <div class="sku-table-head">
                <span>规格值</span>
                <span>SKU 图片</span>
                <span>售价（元）</span>
                <span>划线价（元）</span>
                <span>成本价（元）</span>
                <span>平台提成（元）</span>
                <span>分销佣金（元）</span>
                <span>库存</span>
                <span>可用库存</span>
                <span>操作</span>
              </div>
              <div v-for="(sku, index) in skuRows" :key="sku.id ?? `new-${index}`" class="sku-row">
                <NInput v-model:value="sku.specValue" placeholder="如 原味 / 500ml" />
                <ProductImageUpload v-model="sku.image" :size="64" label="SKU 图" />
                <NInputNumber v-model:value="sku.salePrice" :min="0" :precision="2" :show-button="false" />
                <NInputNumber v-model:value="sku.originalPrice" :min="0" :precision="2" :show-button="false" />
                <NInputNumber v-model:value="sku.costPrice" :min="0" :precision="2" :show-button="false" />
                <NInputNumber v-model:value="sku.platformCommission" :min="0" :precision="2" :show-button="false" />
                <NInputNumber v-model:value="sku.distributionCommission" :min="0" :precision="2" :show-button="false" />
                <NInputNumber v-model:value="sku.stock" :min="sku.lockedStock" :precision="0" :show-button="false" />
                <div class="available-stock">
                  <strong>{{ Math.max(0, Number(sku.stock) - Number(sku.lockedStock)) }}</strong>
                  <small v-if="sku.lockedStock">锁定 {{ sku.lockedStock }}</small>
                </div>
                <div class="sku-actions">
                  <NButton size="tiny" type="info" secondary :disabled="!sku.id" @click="openDrawerSkuStock(sku)">
                    库存调整
                  </NButton>
                  <NButton size="tiny" type="error" quaternary @click="removeSku(index)">删除</NButton>
                </div>
              </div>
            </div>

            <NFormItem label="商品详情" class="description-field">
              <NInput
                v-model:value="form.description"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 10 }"
                placeholder="商品详情文案"
              />
            </NFormItem>

            <NFormItem label="详情长图" class="detail-images-field">
              <ProductDetailImages v-model="form.detailImages" :disabled="saving" />
            </NFormItem>
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
.sku-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin: 8px 0 12px;
}
.sku-table {
  overflow-x: auto;
  border: 1px solid #ececea;
  border-radius: 10px;
  background: #fafaf8;
}
.sku-table-head,
.sku-row {
  display: grid;
  min-width: 1260px;
  grid-template-columns: 150px 92px 118px 122px 122px 126px 126px 92px 98px 184px;
  align-items: start;
  gap: 10px;
  padding: 12px 14px;
}
.sku-table-head {
  position: sticky;
  top: 0;
  z-index: 1;
  color: #666762;
  font-size: 12px;
  font-weight: 600;
  background: #f3f3ef;
}
.sku-row + .sku-row {
  border-top: 1px solid #ececea;
}
.available-stock {
  display: flex;
  min-height: 34px;
  align-items: flex-start;
  justify-content: center;
  flex-direction: column;
  color: #333;
}
.available-stock small {
  color: #9b9b96;
  font-size: 11px;
}
.sku-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.description-field {
  margin-top: 20px;
}
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
@media (max-width: 900px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
  .sku-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
