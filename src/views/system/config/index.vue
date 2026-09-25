<script setup lang="ts">

defineOptions({
  name: 'system_config'
});

import { computed } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 小程序运营配置（app_config 表）。
 *
 * 设计要点：
 * - 配置项对小程序**只读**，由后台维护，避免为改文案而发版；
 * - value 为 JSON，各 key 结构不同（对象 / 数组 / 字符串数组），
 *   因此统一用 textarea 编辑并做 JSON 合法性校验，
 *   而不是为每个 key 单独建表单（key 会持续增加，成本不可控）。
 * - 客服热线（service_info.hotline）在下方给出结构化入口，
 *   避免运营直接改 JSON 时写坏格式导致小程序客服页异常。
 */

/** 配置键 -> 中文用途说明，供运营识别「这条配置影响哪里」。 */
const KEY_HINTS: Record<string, string> = {
  home_shortcuts: '小程序首页金刚区入口',
  menu_activity: '点单页活动弹层文案',
  profile_functions: '我的页功能宫格',
  signin_rules: '签到规则页文案',
  signin_rewards: '连续签到奖励档位',
  app_cities: '小程序城市选择列表',
  points_signin: '签到页日历基准数据',
  settlement_notes: '经营角色结算说明',
  service_info: '客服页：热线 / 在线客服说明（改此值即生效）',
  service_faqs: '客服页：常见问题列表',
  withdraw_rule: '提现规则说明',
  tencent_map_key: '腾讯位置服务密钥（敏感，不下发小程序）'
};

/** 敏感配置：接口不会下发给小程序，后台仅可维护不可预览明文。 */
const SENSITIVE_KEYS = ['tencent_map_key'];

function hintOf(row: any): string {
  return KEY_HINTS[row?.configKey] ?? '';
}

function isSensitive(row: any): boolean {
  return SENSITIVE_KEYS.includes(row?.configKey);
}

/** JSON 值预览：压缩空白后截断，避免长 JSON 撑破表格列。 */
function previewOf(row: any): string {
  const raw = row?.value;
  if (raw === null || raw === undefined) return '—';
  const text = typeof raw === 'string' ? raw : JSON.stringify(raw);
  return text.length > 80 ? `${text.slice(0, 80)}…` : text;
}

/** 客服热线快捷展示：从 service_info JSON 里取出 hotline。 */
function hotlineOf(row: any): string {
  if (row?.configKey !== 'service_info') return '—';
  try {
    const parsed = typeof row.value === 'string' ? JSON.parse(row.value) : row.value;
    return parsed?.hotline || '未配置';
  } catch {
    return 'JSON 格式错误';
  }
}

const columns: DataTableColumns<any> = [
  { title: '配置键', key: 'configKey', width: 170 },
  { title: '配置名称', key: 'configName', width: 150 },
  { title: '用途（影响小程序哪里）', key: '_hint', minWidth: 220, render: (row: any) => hintOf(row) || '—' },
  {
    title: '当前客服热线',
    key: '_hotline',
    width: 150,
    render: (row: any) => hotlineOf(row)
  },
  { title: '配置内容（预览）', key: '_preview', minWidth: 240, render: (row: any) => previewOf(row) },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'configKey', label: '配置键', placeholder: '如 service_info' },
  { key: 'configName', label: '配置名称', placeholder: '如 客服信息' }
];

const formFields: FormField[] = [
  { key: 'configKey', label: '配置键（唯一，勿随意修改）', placeholder: '如 service_info' },
  { key: 'configName', label: '配置名称', placeholder: '如 客服信息' },
  {
    key: 'value',
    label: '配置内容（JSON 格式）',
    type: 'textarea',
    placeholder: '例如：{"hotline":"400-000-0000","serviceHours":"每日 9:00 - 21:00"}'
  },
  { key: 'sort', label: '排序', type: 'number' },
  { key: 'remark', label: '备注' }
];

/** 保存前校验 JSON 合法性：写坏 JSON 会让小程序整页拿不到配置。 */
function assertJson(value: unknown, key: string) {
  if (value === null || value === undefined || String(value).trim() === '') {
    throw new Error(`「${key}」的配置内容不能为空`);
  }
  try {
    JSON.parse(String(value));
  } catch {
    throw new Error(`「${key}」的配置内容不是合法 JSON，请检查括号与引号`);
  }
}

/** 一键把客服热线改成新号码（只改 hotline 字段，保留其余配置）。 */
async function updateHotline(row: any, phone: string) {
  const next = String(phone || '').trim();
  if (!next) {
    window.$message?.warning('请输入新的客服热线');
    return;
  }
  let parsed: Record<string, any>;
  try {
    parsed = typeof row.value === 'string' ? JSON.parse(row.value) : { ...row.value };
  } catch {
    window.$message?.error('service_info 当前不是合法 JSON，请先用「编辑」修正格式');
    return;
  }
  // 号码本身不是密钥，但写错会导致小程序客服页拨号失败，这里做基础格式校验
  if (!/^[\d-]{5,20}$/.test(next)) {
    window.$message?.warning('客服热线格式不正确（仅允许数字与连字符）');
    return;
  }
  parsed.hotline = next;
  // 号码一旦替换为真实值，占位提示即失效，同步清空避免误导用户
  if (next !== '400-000-0000' && typeof parsed.hotlineNote === 'string' && parsed.hotlineNote.includes('占位')) {
    parsed.hotlineNote = '';
  }
  await store.update('appConfig', row.id, { value: JSON.stringify(parsed) }, '运营配置', 'configName');
  window.$message?.success(`客服热线已更新为 ${next}`);
}

const toolbar: RowAction[] = [{ label: '新增配置', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '修改客服热线',
    type: 'info',
    reasonPrompt: '请输入新的客服热线号码（仅数字与连字符）',
    handler: async (row, reason) => await updateHotline(row, reason ?? ''),
    visible: row => row.configKey === 'service_info'
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该配置？（请填写备注）',
    handler: async row => await store.update('appConfig', row.id, { status: 'disabled' }, '运营配置', 'configName'),
    visible: row => row.status !== 'disabled'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该配置？（请填写备注）',
    handler: async row => await store.update('appConfig', row.id, { status: 'enabled' }, '运营配置', 'configName'),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该配置？（小程序将取不到该配置，请谨慎操作并填写备注）',
    handler: async (row, reason) => await store.remove('appConfig', row.id, '运营配置', 'configName', reason),
    // 敏感配置与客服配置是系统依赖项，禁止直接删除
    visible: row => !isSensitive(row) && row.configKey !== 'service_info'
  }
];

const config = computed<AdminListConfig>(() => ({
  title: '运营配置',
  remoteKey: 'appConfig',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('appConfig', search, page, pageSize),
  form: {
    title: '运营配置',
    fields: formFields,
    onSubmit: async (data, editing) => {
      const key = String(data.configKey ?? '').trim();
      if (!key) throw new Error('配置键不能为空');
      assertJson(data.value, key);
      const payload = { ...data, configKey: key };
      if (editing) await store.update('appConfig', editing.id, payload, '运营配置', 'configName');
      else await store.add('appConfig', payload, '运营配置', 'configName');
    }
  }
}));
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>