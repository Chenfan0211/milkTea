<script setup lang="ts">
defineOptions({
  name: 'SubjectOnboard'
});

import { h, onMounted, ref } from 'vue';
import type { DataTableColumns } from 'naive-ui';
import { NTag } from 'naive-ui';
import { fetchOnboards, fetchOnboardMaterial } from '@/service/api/crud';

/** 易宝入网状态 → 展示文案与颜色 */
const STATUS_MAP: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  REVIEWING: { label: '审核中', type: 'info' },
  REVIEW_BACK: { label: '已驳回', type: 'error' },
  BUSINESS_OPENING: { label: '开通中', type: 'warning' },
  COMPLETED: { label: '已完成', type: 'success' }
};

/** 主体类型 → 展示文案 */
const SIGN_TYPE_MAP: Record<string, string> = {
  ENTERPRISE: '企业',
  INDIVIDUAL: '个体工商户',
  MICRO: '自然人（小微）'
};

const loading = ref(false);
const rows = ref<any[]>([]);
const page = ref(1);
const pageSize = ref(20);
const total = ref(0);
const status = ref<string | null>(null);

const materialVisible = ref(false);
const material = ref<any>(null);

const statusOptions = Object.entries(STATUS_MAP).map(([value, meta]) => ({ label: meta.label, value }));

const columns: DataTableColumns<any> = [
  { title: '主体ID', key: 'subjectId', width: 100 },
  { title: '主体类型', key: 'signType', width: 130, render: row => SIGN_TYPE_MAP[row.signType] || row.signType || '-' },
  { title: '易宝子商户号', key: 'yeepayMerchantNo', width: 160, render: row => row.yeepayMerchantNo || '-' },
  { title: '申请单号', key: 'applicationNo', width: 180, render: row => row.applicationNo || '-' },
  {
    title: '状态',
    key: 'applicationStatus',
    width: 110,
    render: row => {
      const meta = STATUS_MAP[row.applicationStatus];
      return meta ? h(NTag, { type: meta.type, size: 'small' }, { default: () => meta.label }) : '-';
    }
  },
  {
    title: '签署',
    key: 'signedTime',
    width: 90,
    render: row => (row.signedTime ? '已签' : row.signUrl ? '待签' : '-')
  },
  {
    title: '驳回原因',
    key: 'rejectReason',
    ellipsis: { tooltip: true },
    render: row => row.rejectReason || '-'
  },
  { title: '入网请求号', key: 'requestNo', width: 180 },
  {
    title: '操作',
    key: 'actions',
    width: 110,
    fixed: 'right',
    render: row =>
      h(
        'a',
        {
          style: 'color: var(--primary-color); cursor: pointer;',
          onClick: () => viewMaterial(row.subjectId)
        },
        '查看资料'
      )
  }
];

/** 打开资料抽屉（证件号与手机号均为掩码） */
async function viewMaterial(subjectId: number) {
  try {
    material.value = await fetchOnboardMaterial(subjectId);
    materialVisible.value = true;
  } catch (error: any) {
    window.$message?.error(error?.message || '查询入驻资料失败');
  }
}

async function load() {
  loading.value = true;
  try {
    const res = await fetchOnboards({
      current: page.value,
      size: pageSize.value,
      status: status.value || undefined
    });
    rows.value = res?.records || [];
    total.value = res?.total || 0;
  } catch (error: any) {
    window.$message?.error(error?.message || '查询入驻列表失败');
  } finally {
    loading.value = false;
  }
}

function handlePageChange(next: number) {
  page.value = next;
  load();
}

function handleStatusChange(value: string | null) {
  status.value = value;
  page.value = 1;
  load();
}

onMounted(load);
</script>

<template>
  <div class="p-16px">
    <NCard title="经营者入驻与签约（易宝）" :bordered="false">
      <template #header-extra>
        <NSpace>
          <NSelect
            :value="status"
            :options="statusOptions"
            placeholder="全部状态"
            clearable
            style="width: 140px"
            @update:value="handleStatusChange"
          />
          <NButton size="small" @click="load">刷新</NButton>
        </NSpace>
      </template>

      <NAlert type="info" :bordered="false" class="mb-12px">
        经营者入驻通过并完成签署后才能提现。状态由易宝入网结果通知驱动，
        <b>审核中/开通中</b> 属正常中间态；<b>已驳回</b>需经营者修改资料后重新提交。
      </NAlert>

      <NDataTable
        :columns="columns"
        :data="rows"
        :loading="loading"
        :scroll-x="1200"
        :pagination="{
          page: page,
          pageSize: pageSize,
          itemCount: total,
          showSizePicker: false,
          onChange: handlePageChange
        }"
        remote
      />
    </NCard>

    <NDrawer v-model:show="materialVisible" :width="420">
      <NDrawerContent title="入驻资料" closable>
        <NDescriptions v-if="material" :column="1" label-placement="left" bordered>
          <NDescriptionsItem label="法人/经营者">{{ material.legalName || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="证件号">
            {{ material.legalLicenceMask || '-' }}
          </NDescriptionsItem>
          <NDescriptionsItem label="签约名">{{ material.signName || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="商户简称">{{ material.shortName || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="统一社会信用代码">{{ material.licenceNo || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="联系人">{{ material.contactName || '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="联系电话">
            {{ material.contactMobileMask || '-' }}
          </NDescriptionsItem>
          <NDescriptionsItem label="经营地址">
            {{ [material.province, material.city, material.district, material.address].filter(Boolean).join(' ') || '-' }}
          </NDescriptionsItem>
        </NDescriptions>
        <NEmpty v-else description="暂无资料" />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped></style>
