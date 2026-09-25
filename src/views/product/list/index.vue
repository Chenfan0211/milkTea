<script setup lang="ts">
import { h, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { NImage } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { formatFen, renderTag, statusMap } from '@/views/_shared/render';
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

/** 分 -> 元（保留两位小数）；空值返回 undefined，避免表单出现 NaN */
function fenToYuan(fen: any): number | undefined {
  if (fen === null || fen === undefined || fen === '') return undefined;
  const n = Number(fen);
  if (Number.isNaN(n)) return undefined;
  return Number((n / 100).toFixed(2));
}

async function openSpecEditor(row: any) {
  specRow.value = row;
  specModel.value = [];
  try {
    // 规格存储于 product_spec（行式），由后端组装为「组 -> 选项」
    const groups = await store.loadSpecGroups(row.id);
    specModel.value = JSON.parse(JSON.stringify(groups || []));
  } catch (error: any) {
    window.$message?.error(error?.message || '规格加载失败');
  }
  specVisible.value = true;
}

async function saveSpec() {
  if (!specRow.value) return;
  const cleaned = specModel.value.filter(group => (group.label || '').trim() !== '温馨提示' && group.id !== 'tip');
  const removed = specModel.value.length - cleaned.length;
  if (removed > 0) {
    window.$message?.warning('已自动移除 ' + removed + ' 个「温馨提示」规格组，请改在「商品详情-饮用提示」中维护');
  }
  await store.saveProductSpecGroups(specRow.value.id, cleaned);
  specVisible.value = false;
  window.$message?.success('规格已保存');
  listRef.value?.reload();
}

/**
 * 分类下拉选项。
 *
 * 1. type 做 trim + 大写归一：数据库里若存在大小写/空格差异（如 'category'、'CATEGORY '），
 *    原先的严格 === 'CATEGORY' 会把所有选项过滤成空数组，表现为「编辑弹窗分类下拉没有数据」。
 * 2. value 统一 Number：后端 category_id 为 BIGINT，序列化后是数字；
 *    若 value 是字符串而表单值是数字，naive-ui 因类型不匹配不回显。
 */
function categoryOptions(): { label: string; value: any }[] {
  return store.productCategories
    .filter((c: any) => String(c?.type ?? '').trim().toUpperCase() === 'CATEGORY')
    .map((c: any) => ({ label: c.name, value: Number(c.id) }));
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
  { title: '标签', key: 'tags', width: 125, render: (row: any) => (Array.isArray(row.tags) ? row.tags.join('、') : '') || '—' },
  {
    title: '规格数',
    key: 'specCount',
    width: 80,
    align: 'right',
    render: (row: any) => (row.specGroups?.length ?? row.specCount ?? 0)
  },
  { title: '原价(元)', key: 'originalPrice', width: 90, align: 'right', render: (row: any) => (row.originalPrice != null ? `¥${formatFen(row.originalPrice)}` : '—') },
  { title: '成本价(元)', key: 'costPrice', width: 88, align: 'right', render: (row: any) => (row.costPrice != null ? `¥${formatFen(row.costPrice)}` : '—') },
  { title: '平台分佣(元)', key: 'platformCommission', width: 95, align: 'right', render: (row: any) => (row.platformCommission != null ? `¥${formatFen(row.platformCommission)}` : '—') },
  { title: '储值立减(元)', key: 'storedValuePrice', width: 88, align: 'right', render: (row: any) => (row.storedValuePrice != null ? '-' + formatFen(row.storedValuePrice) : '—') },
  {
    title: '门店',
    key: 'stores',
    minWidth: 220,
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

const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  { key: 'image', label: '商品图片', type: 'image', rules: [requiredRule] },
  { key: 'code', label: '编码', rules: [requiredRule] },
  { key: 'name', label: '名称', rules: [requiredRule] },
  {
    key: 'categoryId',
    label: '分类',
    type: 'select',
    // 后端按 category_id 落库；仅列出 CATEGORY 层（商品实际挂载层）
    // type 做 trim + 大写归一：库中存在大小写/空格差异时，严格 === 'CATEGORY' 会把选项全过滤掉
    options: () => categoryOptions(),
    rules: [{ required: true, message: '请选择商品分类', trigger: ['change', 'blur'] }]
  },
  { key: 'originalPrice', label: '原价(元)', type: 'number', rules: [requiredRule] },
  { key: 'costPrice', label: '成本价(元)', type: 'number' },
  { key: 'platformCommission', label: '平台分佣(元)', type: 'number' },
  { key: 'storedValuePrice', label: '储值立减(元)', type: 'number', placeholder: '用储值支付每件少多少元，0 表示不优惠', rules: [requiredRule] },
  { key: 'tagsText', label: '标签(逗号分隔)', placeholder: '多个标签用逗号分隔，如：年度热销，五窨茉莉花茶' },
  {
    key: 'stores',
    label: '门店',
    type: 'multiple',
    multiple: true,
    // 用 id 作为值：后端 product_store 按 store_subject_id 落库
    options: () => store.subjects.filter(s => s.type === 'store').map(s => ({ label: s.name, value: s.id })),
    rules: [requiredRule]
  },
  { key: 'galleryImage', label: '详情大图', type: 'image', rules: [requiredRule] },
  { key: 'promotionText', label: '促销文案', rules: [requiredRule] },
  { key: 'imageDisclaimer', label: '图片免责声明', rules: [requiredRule] },
  { key: 'ingredients', label: '配料' },
  { key: 'allergens', label: '过敏原' },
  { key: 'cupCapacity', label: '杯容量' },
  { key: 'tipsText', label: '饮用提示', type: 'textarea', placeholder: '每行一条提示' },
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
  {
    label: '下架',
    type: 'warning',
    reasonPrompt: '确认下架该商品？（请填写备注）',
    handler: async (row: any) => {
      await store.setProductOnSale(row.id, 'off');
      window.$message?.success('商品已下架');
      listRef.value?.reload();
    },
    visible: row => row.onSale === 'on'
  },
  {
    label: '上架',
    type: 'success',
    reasonPrompt: '确认上架该商品？（请填写备注）',
    handler: async (row: any) => {
      await store.setProductOnSale(row.id, 'on');
      window.$message?.success('商品已上架');
      listRef.value?.reload();
    },
    visible: row => row.onSale === 'off'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该商品？（请填写备注）',
    handler: async (row: any) => {
      await store.removeProduct(row.id);
      window.$message?.success('商品已删除');
      listRef.value?.reload();
    }
  }
];

const config: AdminListConfig = {
  title: '商品管理',
  remoteKey: 'productCategories',
  remoteDeps: ['subjects'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    // 走 product-service 专用接口（非通用 CRUD）：/api/v1/admin/product/list
    const keyword = String((search && (search.name || search.code)) || '').trim();
    const res = await store.loadProducts({ current: page, size: pageSize, search: keyword || undefined });
    if (!res) return { data: [], total: 0 };
    return { data: res.records || [], total: res.total || 0 };
  },
  form: {
    title: '商品',
    fields: formFields,
    /**
     * 编辑回填时把「分」换算为「元」，避免输入框显示 640 而非 6.40。
     * 提交时后端负责「元 -> 分」换算（AdminProductWriteService#yuanToFen）。
     */
    toFormData: (row: any) => ({
      ...row,
      // 分类：后端 Long 序列化为数字；老数据若缺 categoryId，按分类名反查兜底
      categoryId:
        row.categoryId != null
          ? Number(row.categoryId)
          : (categoryOptions().find((o: any) => o.label === row.category)?.value ?? null),
      // 后端返回的是门店名称数组，表单需要 id 列表
      stores: (row.stores || [])
        .map((name: string) => store.subjects.find((s: any) => s.type === 'store' && s.name === name)?.id)
        .filter((id: any) => id != null),
      price: fenToYuan(row.price),
      originalPrice: fenToYuan(row.originalPrice),
      costPrice: fenToYuan(row.costPrice),
      platformCommission: fenToYuan(row.platformCommission),
      storedValuePrice: fenToYuan(row.storedValuePrice),
      galleryImage: row.galleryImage || row.image || '',
      promotionText: row.promotionText || '',
      imageDisclaimer: row.imageDisclaimer || '',
      ingredients: row.ingredients || '',
      allergens: row.allergens || '',
      cupCapacity: row.cupCapacity || '',
      // 标签：后端下发字符串数组，表单用逗号文本编辑（直接绑定数组会导致输入即被覆写）
      tagsText: Array.isArray(row.tags) ? row.tags.join('，') : '',
      tipsText: (row.tips || []).join('\n'),
    }),
    onSubmit: async (data, editing) => {
      const tags = String(data.tagsText || '')
        .split(/[,，、]/)
        .map((s: string) => s.trim())
        .filter((s: string) => s.length > 0);
      const tips = String(data.tipsText || '')
        .split('\n')
        .map((s: string) => s.trim())
        .filter((s: string) => s.length > 0);
      // stores 是门店关联，不属于 product 表字段，单独通过 /stores 接口落库
      const { tagsText: _tagsIgnored, stores: storeIds, tipsText: _tipsIgnored, ...rest } = data;
      const payload = { ...rest, tags, tips };
      try {
        let productId = editing?.id;
        if (editing) await store.editProduct(editing.id, payload);
        else {
          const created: any = await store.addProduct(payload);
          productId = created?.id;
        }
        if (productId && Array.isArray(storeIds)) {
          await store.saveProductStoreIds(productId, storeIds as number[]);
        }
        window.$message?.success(editing ? '商品已更新' : '商品已新增');
        listRef.value?.reload();
      } catch (error: any) {
        window.$dialog?.error({
          title: '保存失败',
          content: error?.message || '保存失败，请稍后重试',
          positiveText: '我知道了'
        });
      }
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


