<script setup lang="ts">
import { ref, shallowRef } from 'vue';
import { NButton, NForm, NFormItem, NInput, NInputNumber, NModal } from 'naive-ui';
import {
  assignUserLeader,
  createLeader,
  fetchLeaderDeactivationPreview,
  fetchLeaders,
  fetchUserLeaders,
  removeLeader
} from '@/service/api/crud';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import { formatFen, renderMoney } from '@/views/_shared/render';
import type { AdminListConfig } from '@/views/_shared/types';

defineOptions({ name: 'SubjectLeader' });

interface LeaderRow {
  id: number;
  name: string;
  status: string;
  boundUserId: number | null;
}

const list = ref<InstanceType<typeof AdminListPage> | null>(null);
const formVisible = ref(false);
const formTarget = shallowRef<LeaderRow | null>(null);
const formUserId = ref<number | null>(null);
const formName = ref('');
const saving = ref(false);
const membersVisible = ref(false);
const membersTarget = shallowRef<LeaderRow | null>(null);
const previewing = ref(false);

function openForm(target: LeaderRow | null) {
  formTarget.value = target;
  formUserId.value = null;
  formName.value = '';
  formVisible.value = true;
}

async function submitForm() {
  if (saving.value) return;
  const userId = formUserId.value;
  const leaderUserId = formTarget.value?.boundUserId;
  if (userId == null || !Number.isSafeInteger(userId) || userId < 1) {
    window.$message?.warning('请输入有效的用户 ID');
    return;
  }
  if (userId === leaderUserId) {
    window.$message?.warning('用户不能归属自己');
    return;
  }
  saving.value = true;
  try {
    if (leaderUserId != null) {
      await assignUserLeader(userId, leaderUserId);
      window.$message?.success('已更新用户归属');
    } else {
      await createLeader(userId, formName.value.trim() || undefined);
      window.$message?.success('已设为团长');
    }
    formVisible.value = false;
    await list.value?.reload();
  } catch {
    // 请求层展示错误，保留输入以便调整或重试。
  } finally {
    saving.value = false;
  }
}

async function confirmDeactivation(row: LeaderRow) {
  const dialog = window.$dialog;
  if (previewing.value || !dialog) return;
  previewing.value = true;
  try {
    const impact = await fetchLeaderDeactivationPreview(row.id);
    const confirmation = dialog.warning({
      title: `停用团长「${row.name}」`,
      content:
        `当前归属用户 ${impact.boundUserCount} 人，未完成订单 ${impact.pendingOrderCount} 笔，` +
        `待结算金额 ¥${formatFen(impact.pendingSettlementAmount)}。停用后保留团长主体、资金账户和历史订单流水。`,
      positiveText: '确认停用',
      negativeText: '取消',
      onPositiveClick: async () => {
        confirmation.loading = true;
        try {
          await removeLeader(row.id);
          window.$message?.success('团长已停用');
          await list.value?.reload();
          return true;
        } catch {
          return false;
        } finally {
          confirmation.loading = false;
        }
      },
      onAfterLeave: () => {
        previewing.value = false;
      }
    });
  } catch {
    // 预览读取失败时无法执行停用。
    previewing.value = false;
  }
}

const config: AdminListConfig = {
  title: '团长管理',
  columns: [
    { title: '团长主体 ID', key: 'id', width: 120 },
    { title: '团长用户 ID', key: 'boundUserId', width: 120 },
    { title: '团长名称', key: 'name', minWidth: 140 },
    { title: '用户昵称', key: 'boundUserNick', minWidth: 140 },
    { title: '状态', key: 'status', width: 100, render: row => (row.status === 'active' ? '启用' : '停用') },
    {
      title: '可提现余额（元）',
      key: 'accountBalance',
      width: 135,
      align: 'right',
      render: renderMoney('accountBalance')
    }
  ],
  searchFields: [
    { key: 'keyword', label: '团长', placeholder: '名称、编号或团长用户 ID' },
    {
      key: 'status',
      label: '状态',
      type: 'select',
      options: [
        { label: '启用', value: 'active' },
        { label: '停用', value: 'inactive' }
      ]
    }
  ],
  toolbar: [{ label: '设为团长', type: 'primary', handler: () => openForm(null) }],
  rowActions: [
    {
      label: '归属用户',
      visible: row => row.boundUserId != null,
      handler: row => {
        membersTarget.value = row;
        membersVisible.value = true;
      }
    },
    {
      label: '归属或改绑',
      visible: row => row.status === 'active' && row.boundUserId != null,
      handler: row => openForm(row)
    },
    {
      label: '停用',
      type: 'warning',
      visible: row => row.status === 'active',
      handler: confirmDeactivation
    }
  ],
  async loadData({ page, pageSize, search }) {
    const result = await fetchLeaders({
      current: page,
      size: pageSize,
      search: search.keyword?.trim() || undefined,
      status: search.status || 'all'
    });
    return { data: result.records, total: result.total };
  }
};

const membersConfig: AdminListConfig = {
  title: '归属用户',
  columns: [
    { title: '用户 ID', key: 'userId', width: 120 },
    { title: '用户昵称', key: 'userNick', minWidth: 130 },
    { title: '手机号', key: 'userPhone', minWidth: 130 }
  ],
  searchFields: [{ key: 'keyword', label: '用户', placeholder: '用户 ID、昵称或手机号' }],
  async loadData({ page, pageSize, search }) {
    const leaderUserId = membersTarget.value?.boundUserId;
    if (leaderUserId == null) return { data: [], total: 0 };
    const result = await fetchUserLeaders({
      leaderUserId,
      current: page,
      size: pageSize,
      search: search.keyword?.trim() || undefined
    });
    return { data: result.records.map(row => ({ ...row, id: row.userId })), total: result.total };
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="list" :config="config" />
    <NModal
      v-model:show="formVisible"
      preset="card"
      :title="formTarget ? `归属至「${formTarget.name}」` : '设为团长'"
      :closable="!saving"
      :mask-closable="false"
      :close-on-esc="!saving"
      class="w-480px"
    >
      <NForm @submit.prevent="submitForm">
        <NFormItem label="用户 ID" required>
          <NInputNumber
            v-model:value="formUserId"
            :min="1"
            :precision="0"
            class="w-full"
            placeholder="输入现有用户 ID"
          />
        </NFormItem>
        <NFormItem v-if="!formTarget" label="团长名称">
          <NInput v-model:value="formName" placeholder="留空使用用户昵称" />
        </NFormItem>
        <p v-if="formTarget">已有团长归属的用户会改绑至当前团长，历史订单归属保持原记录。</p>
        <div class="flex justify-end gap-12px">
          <NButton :disabled="saving" @click="formVisible = false">取消</NButton>
          <NButton attr-type="submit" type="primary" :loading="saving" :disabled="saving">确定</NButton>
        </div>
      </NForm>
    </NModal>
    <NModal
      v-model:show="membersVisible"
      preset="card"
      :title="`${membersTarget?.name ?? ''} · 归属用户`"
      class="w-720px"
    >
      <AdminListPage v-if="membersVisible" :key="membersTarget?.id" :config="membersConfig" />
    </NModal>
  </div>
</template>
