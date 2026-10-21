<script setup lang="ts">
defineOptions({
  name: 'DeviceBrewRecordDialog'
});

import { computed, reactive, ref, watch } from 'vue';
import { NDataTable, NModal } from 'naive-ui';
import type { DataTableColumns, PaginationProps } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderDateTime } from '@/views/_shared/render';

interface DeviceBrewTarget {
  id?: number;
  deviceSn: string;
  deviceName?: string | null;
  alias?: string | null;
}

const props = withDefaults(
  defineProps<{
    show?: boolean;
    device?: DeviceBrewTarget | null;
  }>(),
  {
    show: false,
    device: null
  }
);

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
}>();

const store = useAdminStore();
const loading = ref(false);
const rows = ref<any[]>([]);

const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50],
  onUpdatePage: (page: number) => {
    pagination.page = page;
    load();
  },
  onUpdatePageSize: (pageSize: number) => {
    pagination.pageSize = pageSize;
    pagination.page = 1;
    load();
  }
});

const columns: DataTableColumns<any> = [
  { title: '上报时间', key: 'baseCreateTime', width: 175, render: renderDateTime('baseCreateTime') },
  { title: '门店', key: 'storeName', minWidth: 150, render: (row: any) => row.storeName || '未绑定' },
  { title: '设备编码', key: 'deviceSn', width: 185 },
  { title: '设备ID', key: 'deviceName', width: 150, render: (row: any) => row.deviceName || '—' },
  { title: '茶品代码', key: 'teaCode', width: 100 },
  { title: '茶品名称', key: 'teaName', minWidth: 150, render: (row: any) => row.teaName || '—' },
  { title: '客户代码', key: 'customerCode', width: 120, render: (row: any) => row.customerCode || '—' },
  { title: '生产日期', key: 'madeDay', width: 110, render: (row: any) => row.madeDay || '—' },
  { title: '序列号', key: 'serialNumber', width: 100, render: (row: any) => row.serialNumber || '—' }
];

const title = computed(
  () => `冲泡记录 · ${props.device?.alias || props.device?.deviceName || props.device?.deviceSn || ''}`
);

async function load() {
  if (!props.device?.deviceSn) {
    rows.value = [];
    pagination.itemCount = 0;
    return;
  }
  loading.value = true;
  try {
    const result = await store.queryRemote(
      'deviceBrewRecords',
      { eq_deviceSn: props.device.deviceSn },
      pagination.page || 1,
      pagination.pageSize || 10
    );
    rows.value = result.data;
    pagination.itemCount = result.total || 0;
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.show, props.device?.deviceSn] as const,
  ([show]) => {
    if (show) {
      pagination.page = 1;
      load();
    }
  },
  { immediate: true }
);
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="title"
    class="w-1120px"
    :mask-closable="false"
    @update:show="value => emit('update:show', value)"
  >
    <div class="brew-dialog__summary">
      设备编码：{{ device?.deviceSn || '—' }} · 设备ID：{{ device?.deviceName || '—' }} · 共
      {{ pagination.itemCount }} 条
    </div>
    <NDataTable
      remote
      size="small"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :pagination="pagination"
      :scroll-x="1320"
      :row-key="(row: any) => row.id"
    />
  </NModal>
</template>

<style scoped>
.brew-dialog__summary {
  margin-bottom: 12px;
  color: #666762;
  font-size: 13px;
}
</style>
