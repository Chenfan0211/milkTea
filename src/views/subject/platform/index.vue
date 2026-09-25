<script setup lang="ts">
defineOptions({
  name: 'subject_platform'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { ref } from 'vue';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { fetchPlatformProfile, savePlatformProfile } from '@/service/api/crud';

const store = useAdminStore();

// 平台配置（AppID/商户号来自专用接口，AppSecret 不回显）
const platformProfile = ref<{ appId?: string | null; mchId?: string | null; secretConfigured?: boolean }>({});
const loadProfile = async () => {
  try {
    platformProfile.value = await fetchPlatformProfile(1);
  } catch {
    platformProfile.value = {};
  }
};
void loadProfile();

const load = async (p: any) =>
  store.queryRemote('subjects', { ...p.search, eq_subjectType: 'PLATFORM' }, p.page, p.pageSize);

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  {
    title: '小程序AppID',
    key: 'appId',
    width: 170,
    render: (row: any) => row.appid || platformProfile.value?.appId || '—'
  },
  { title: '名称', key: 'name', width: 140, ellipsis: { tooltip: true } },
  {
    title: '可提现余额(元)',
    key: 'balance',
    width: 130,
    align: 'right',
    render: (row: any) => {
      const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id);
      return (acc ? (acc.availableBalance ?? 0) : 0).toFixed(2);
    }
  },
  {
    title: '账号状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['正常', 'success'], pending: ['待签约', 'warning'] }))
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 150 }
];

const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '名称' }];
const toolbar: RowAction[] = [{ label: '新增平台主体', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该主体？（请填写备注）',
    handler: async (row, reason) => await store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
/**
 * 表单字段按「归属表」分两组，提交时分别写入，避免字段走错表被丢弃：
 *
 *  A. biz_subject（基础主体信息，走通用 CRUD / subjects）
 *     code / name / status / withdrawFreeAuditThreshold
 *
 *  B. platform_profile（微信配置，走专用接口 savePlatformProfile）
 *     appid / appSecret / mchId
 *
 * 历史问题：本页曾把 A、B 两组字段**一并**交给通用 CRUD 写 biz_subject，
 * 而 biz_subject 没有 appid/withdrawFreeAuditThreshold 等列，导致字段被静默丢弃。
 * 其中 appid/appSecret/mchId 靠专用接口侥幸存住，withdrawFreeAuditThreshold 则彻底丢失。
 */
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'appid', label: '小程序AppID', placeholder: '如 wx7d78d69cd175812e' },
  { key: 'appSecret', label: 'AppSecret', type: 'password', placeholder: '已配置则留空表示不修改' },
  { key: 'mchId', label: '微信支付商户号', placeholder: '微信支付商户号 mchId' },
  {
    key: 'withdrawFreeAuditThreshold',
    label: '提现免审阈值(元)',
    type: 'number',
    placeholder: '按元填写，保存时换算为分；0=无免审'
  },
  {
    key: 'status',
    label: '账号状态',
    type: 'select',
    options: [
      { label: '正常', value: 'active' },
      { label: '待签约', value: 'pending' }
    ]
  }
];

const config: AdminListConfig = {
  title: '平台主体',
  remoteKey: 'subjects',
  // 「可提现余额」列按 subjectId 从 subjectAccounts 取数，需预加载该资源，否则恒显示 0.00
  remoteDeps: ['subjectAccounts'],
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '平台主体',
    toFormData: (row: any) => ({
      ...row,
      // 编辑时把已配置的 AppID / 商户号带进表单（AppSecret 永不回显）
      appid: row.appid || platformProfile.value?.appId || '',
      mchId: platformProfile.value?.mchId || '',
      // 阈值：分 -> 元（表单按元编辑）
      withdrawFreeAuditThreshold:
        row.withdrawFreeAuditThreshold == null ? 0 : Number(row.withdrawFreeAuditThreshold) / 100
    }),
    fields: formFields,
    onSubmit: async (data, editing) => {
      // ---- A. 只把 biz_subject 真正拥有的字段交给通用 CRUD ----
      // 关键：绝不能把 appid/appSecret/mchId 一并塞进来，否则会被白名单丢弃，
      // 且会触发「未写入数据库」告警（虽不影响最终结果，但会误导排查）。
      const subjectPayload = {
        code: data.code,
        name: data.name,
        status: data.status,
        subjectType: 'PLATFORM',
        // 元 -> 分（后端列为 bigint，单位分）
        withdrawFreeAuditThreshold: Math.round((Number(data.withdrawFreeAuditThreshold) || 0) * 100)
      };
      if (editing) await store.update('subjects', editing.id, subjectPayload, '主体管理', 'name');
      else await store.add('subjects', subjectPayload, '主体管理', 'name');

      // ---- B. 微信配置走专用接口（appSecret 留空 = 不修改旧值）----
      await savePlatformProfile({
        subjectId: 1,
        appId: data.appid || platformProfile.value.appId || undefined,
        appSecret: data.appSecret || undefined,
        mchId: data.mchId ?? platformProfile.value.mchId ?? undefined
      });
      void loadProfile();
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
