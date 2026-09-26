<script setup lang="ts">
defineOptions({
  name: 'marketing_stored'
});

import { ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchStoredValuePackages, saveStoredValueCoupons, saveStoredValueUsage } from '@/service/api/crud';
import { formatFen } from '@/views/_shared/render';
import GiftCouponsEditor from './GiftCouponsEditor.vue';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

// 赠送券编辑弹窗状态
const giftVisible = ref(false);
const giftRow = ref<any>(null);
const giftModel = ref<any[]>([]);

// 使用说明编辑弹窗状态
const usageVisible = ref(false);
const usageRow = ref<any>(null);
const usageText = ref('');

function openGiftEditor(row: any) {
  giftRow.value = row;
  giftModel.value = JSON.parse(JSON.stringify(row.coupons || []));
  giftVisible.value = true;
}

async function saveGift() {
  if (!giftRow.value) return;
  // 赠券存关联表 stored_value_package_coupon，走 marketing-service 专用接口，
  // 通用 CRUD 单表写不了关联表（原实现写 coupons 字段被白名单静默丢弃）
  await saveStoredValueCoupons(giftRow.value.id, giftModel.value);
  giftVisible.value = false;
  window.$message?.success('赠送券已保存');
  listRef.value?.reload();
}

function openUsageEditor(row: any) {
  usageRow.value = row;
  usageText.value = (row.usageParagraphs || []).join('\n');
  usageVisible.value = true;
}

async function saveUsage() {
  if (!usageRow.value) return;
  const paragraphs = usageText.value
    .split('\n')
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0);
  // 使用说明存 usage_paragraphs JSON 列，走专用接口（通用 CRUD 用 setObject(List) 会类型不匹配）
  await saveStoredValueUsage(usageRow.value.id, paragraphs);
  usageVisible.value = false;
  window.$message?.success('使用说明已保存');
  listRef.value?.reload();
}

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
  }
];

const searchFields: SearchField[] = [{ key: 'amount', label: '金额', placeholder: '金额' }];
const formFields: FormField[] = [{ key: 'amount', label: '套餐金额(元)', type: 'number' }];
const toolbar: RowAction[] = [{ label: '新增套餐', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '赠送券', type: 'info', handler: (row: any) => openGiftEditor(row) },
  { label: '使用说明', type: 'info', handler: (row: any) => openUsageEditor(row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该套餐？（请填写备注）',
    handler: async (row, reason) => await store.remove('storedValuePackages', row.id, '营销中心', 'amount', reason)
  }
];

const config: AdminListConfig = {
  title: '储值套餐',
  remoteKey: 'storedValuePackages',
  // 赠送券弹窗的券池来自 coupons 镜像，直接进入本页时也必须先加载。
  remoteDeps: ['coupons'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize }) => {
    // 走 marketing-service 聚合接口，才能拿到赠券明细与使用说明；
    // 通用 CRUD 单表查询这两列恒为空。
    // 该接口已改为服务端分页（返回 PageResult），total 来自后端 count(*)，
    // 不再用「本地数组长度」当总数（后者会让分页器永远停在当前页）。
    const res = await fetchStoredValuePackages({ current: page, size: pageSize });
    return { data: res?.records ?? [], total: res?.total ?? 0 };
  },
  form: {
    title: '储值套餐',
    fields: formFields,
    /**
     * 编辑回填：amount 存的是「分」，表单按「元」展示与输入。
     * 只回填表单声明的 amount：不再整行 {...row} 回填，
     * 避免 id/updateTime 等服务端字段进入 payload（update 为部分更新，其余字段保持原值）。
     */
    toFormData: (row: any) => ({ amount: row.amount == null ? 0 : Number(row.amount) / 100 }),
    onSubmit: async (data, editing) => {
      // 元 -> 分（储值金额统一以分落库）
      const yuan = Number(data.amount) || 0;
      const payload = { ...data, amount: Math.round(yuan * 100) };
      if (editing) {
        await store.update('storedValuePackages', editing.id, payload, '营销中心', 'amount');
      } else {
        // stored_value_package.name 为 NOT NULL 且表单未收集，按业务语义自动生成（充{元}送优惠券）
        await store.add(
          'storedValuePackages',
          { ...payload, name: `充${yuan}送优惠券`, coupons: [], usageParagraphs: [] },
          '营销中心',
          'amount'
        );
      }
    }
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <NModal v-model:show="giftVisible" preset="card" title="赠送券配置" class="w-680px">
      <div v-if="giftRow" class="modal-body">
        <div class="modal-name">套餐金额：¥{{ (Number(giftRow.amount) / 100).toFixed(2) }}</div>
        <GiftCouponsEditor v-model="giftModel" />
        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="giftVisible = false">取消</NButton>
          <NButton type="primary" @click="saveGift">保存</NButton>
        </div>
      </div>
    </NModal>

    <NModal v-model:show="usageVisible" preset="card" title="使用说明配置" class="w-680px">
      <div v-if="usageRow" class="modal-body">
        <div class="modal-name">套餐金额：¥{{ (Number(usageRow.amount) / 100).toFixed(2) }}</div>
        <NInput v-model:value="usageText" type="textarea" :rows="8" placeholder="每行一条使用说明" />
        <div class="mt-8px text-12px color-#9B9B96">每行一条，保存时自动按换行拆分</div>
        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="usageVisible = false">取消</NButton>
          <NButton type="primary" @click="saveUsage">保存</NButton>
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
