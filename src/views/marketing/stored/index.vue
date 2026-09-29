<script setup lang="ts">
defineOptions({
  name: 'MarketingStored'
});

import { onMounted, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import {
  fetchStoredValuePackages,
  saveStoredValueCoupons,
  fetchStoredValueUsage,
  saveStoredValueUsageGlobal,
  setStoredValuePackageStatus,
  deleteStoredValuePackage
} from '@/service/api/crud';
import { formatFen, renderTag, statusMap } from '@/views/_shared/render';
import GiftCouponsEditor from './GiftCouponsEditor.vue';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

// ---------- 全局使用说明（所有套餐共用一份，存 app_config.stored_value_usage） ----------
const usageVisible = ref(false);
const usageText = ref('');
const usageLoading = ref(false);

async function openUsageEditor() {
  usageVisible.value = true;
  usageLoading.value = true;
  try {
    const paragraphs = await fetchStoredValueUsage();
    usageText.value = (paragraphs || []).join('\n');
  } catch {
    usageText.value = '';
  } finally {
    usageLoading.value = false;
  }
}

async function saveUsage() {
  const paragraphs = usageText.value
    .split('\n')
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0);
  await saveStoredValueUsageGlobal(paragraphs);
  usageVisible.value = false;
  window.$message?.success('使用说明已保存（所有套餐共用）');
}

onMounted(() => {
  // 赠送券弹窗的券池来自 coupons 镜像，进入本页时先加载
  store.loadRemote('coupons');
});

// ---------- 新增/编辑弹窗：套餐金额 + 赠送券 ----------
const formVisible = ref(false);
const editingRow = ref<any>(null);
const formAmount = ref<number | null>(null);
const formCoupons = ref<any[]>([]);

function openForm(mode: 'add' | 'edit', row?: any) {
  editingRow.value = mode === 'edit' ? row : null;
  formAmount.value = mode === 'edit' && row?.amount != null ? Number(row.amount) / 100 : null;
  formCoupons.value = mode === 'edit' ? JSON.parse(JSON.stringify(row?.coupons || [])) : [];
  formVisible.value = true;
}

async function submitForm() {
  const yuan = Number(formAmount.value) || 0;
  if (yuan <= 0) {
    window.$message?.warning('请填写有效的套餐金额');
    return;
  }
  const amountFen = Math.round(yuan * 100);
  try {
    let packageId: number;
    if (editingRow.value) {
      // 部分更新：只改 amount（name/coupons 走各自专用接口）
      await store.update('storedValuePackages', editingRow.value.id, { amount: amountFen }, '营销中心', 'amount');
      packageId = editingRow.value.id;
    } else {
      // stored_value_package.name 为 NOT NULL，按业务语义自动生成
      const created: any = await store.add(
        'storedValuePackages',
        { amount: amountFen, name: `充${yuan}送优惠券` },
        '营销中心',
        'amount'
      );
      packageId = created?.id;
    }
    // 赠送券存关联表，走专用接口（通用 CRUD 写不了关联表）
    if (packageId) {
      await saveStoredValueCoupons(packageId, formCoupons.value);
    }
    formVisible.value = false;
    window.$message?.success('套餐已保存');
    listRef.value?.reload();
  } catch (e: any) {
    window.$message?.error(e?.message || '保存失败');
  }
}

// ---------- 列表 ----------
const columns: DataTableColumns<any> = [
  {
    title: '套餐金额(元)',
    key: 'amount',
    width: 140,
    align: 'right',
    render: (row: any) => (Number(row.amount) / 100).toFixed(2)
  },
  {
    title: '赠送券',
    key: 'coupons',
    minWidth: 320,
    render: (row: any) =>
      (row.coupons || [])
        .map((c: any) => `${c.description || '优惠券'} ¥${formatFen(c.amount)} × ${c.quantity}张`)
        .join('、') || '—'
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ enabled: ['已上架', 'success'], disabled: ['已下架', 'default'] }))
  }
];

const searchFields: SearchField[] = [{ key: 'amount', label: '金额', placeholder: '金额' }];
const toolbar: RowAction[] = [
  { label: '新增套餐', type: 'primary', handler: () => openForm('add') },
  { label: '使用说明', type: 'info', handler: () => openUsageEditor() }
];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: (row: any) => openForm('edit', row) },
  {
    label: '上架',
    type: 'success',
    visible: (row: any) => row.status !== 'enabled',
    handler: async (row: any) => {
      await setStoredValuePackageStatus(row.id, true);
      window.$message?.success('已上架');
      listRef.value?.reload();
    }
  },
  {
    label: '下架',
    type: 'warning',
    visible: (row: any) => row.status === 'enabled',
    handler: async (row: any) => {
      await setStoredValuePackageStatus(row.id, false);
      window.$message?.success('已下架');
      listRef.value?.reload();
    }
  },
  {
    label: '删除',
    type: 'error',
    // 仅「已下架」可删（与后端校验一致，避免按钮点了才报错）
    visible: (row: any) => row.status !== 'enabled',
    confirm: '确认删除该套餐？删除后不可恢复',
    handler: async (row: any) => {
      await deleteStoredValuePackage(row.id);
      window.$message?.success('已删除');
      listRef.value?.reload();
    }
  }
];

const config: AdminListConfig = {
  title: '储值套餐',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize }) => {
    const res = await fetchStoredValuePackages({ current: page, size: pageSize });
    return { data: res?.records ?? [], total: res?.total ?? 0 };
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <!-- 新增 / 编辑套餐：金额 + 赠送券 -->
    <NModal v-model:show="formVisible" preset="card" :title="editingRow ? '编辑储值套餐' : '新增储值套餐'" class="w-680px">
      <div class="modal-body">
        <NForm label-placement="left" :label-width="100">
          <NFormItem label="套餐金额(元)">
            <NInputNumber v-model:value="formAmount" :min="0" placeholder="请输入套餐金额" class="w-full" />
          </NFormItem>
          <NFormItem label="赠送券">
            <GiftCouponsEditor v-model="formCoupons" />
          </NFormItem>
        </NForm>
        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="formVisible = false">取消</NButton>
          <NButton type="primary" @click="submitForm">保存</NButton>
        </div>
      </div>
    </NModal>

    <!-- 全局使用说明（所有套餐共用一份） -->
    <NModal v-model:show="usageVisible" preset="card" title="使用说明（所有套餐共用）" class="w-680px">
      <div class="modal-body">
        <NInput v-model:value="usageText" type="textarea" :rows="8" placeholder="每行一条使用说明" :disabled="usageLoading" />
        <div class="mt-8px text-12px color-#9B9B96">每行一条，保存时自动按换行拆分；所有储值套餐共用这份说明</div>
        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="usageVisible = false">取消</NButton>
          <NButton type="primary" :loading="usageLoading" @click="saveUsage">保存</NButton>
        </div>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.modal-body {
  max-height: 65vh;
  overflow-y: auto;
}
</style>
