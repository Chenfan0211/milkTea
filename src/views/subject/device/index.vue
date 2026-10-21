<script setup lang="ts">
defineOptions({
  name: 'SubjectDevice'
});

import { computed, h, onMounted, ref } from 'vue';
import { NButton, NInput, NModal, NTag } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import DeviceBrewRecordDialog from './DeviceBrewRecordDialog.vue';
import { parseDeviceImportRows } from './import';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import { assertNormalizedRowsSize } from '@/views/_shared/xlsx';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import {
  bindDeviceStore,
  createAdminDevice,
  deleteAdminDevice,
  fetchAdminDevices,
  fetchDeviceBindings,
  fetchDeviceQueueStatus,
  importAdminDevices,
  unbindDeviceStore,
  updateAdminDevice
} from '@/service/api/device';
import type { DeviceQueueStatus, DeviceUpsertPayload } from '@/service/api/device';

/**
 * 设备管理（慧养智萃茶机队列对接）。
 *
 * 口径说明：
 *   · 在线状态与最近上报时间来自设备队列上报，运营不可手工维护；
 *   · 一台设备同一时刻只归属一家门店，门店可绑定多台设备；
 *     换绑会同时补齐旧门店的解绑历史，历史可完整回溯；
 *   · 「未绑定」筛选用于处理「设备已自动建档但还没绑门店」的存量。
 */

const store = useAdminStore();
const listRef = ref<any>(null);

const brewVisible = ref(false);
const brewDevice = ref<any>(null);

const deleteReasonVisible = ref(false);
const deleteConfirmVisible = ref(false);
const deleteTarget = ref<any>(null);
const deleteReason = ref('');
const deleteSubmitting = ref(false);

function openBrewRecords(row: any) {
  brewDevice.value = row;
  brewVisible.value = true;
}

function openDeleteReason(row: any) {
  deleteTarget.value = row;
  deleteReason.value = '';
  deleteReasonVisible.value = true;
}

function openDeleteConfirm() {
  if (!deleteReason.value.trim()) {
    window.$message?.warning('请填写删除备注');
    return;
  }
  deleteReasonVisible.value = false;
  deleteConfirmVisible.value = true;
}

async function confirmDeleteDevice() {
  if (!deleteTarget.value) return;
  deleteSubmitting.value = true;
  try {
    await deleteAdminDevice(deleteTarget.value.id, deleteReason.value.trim());
    deleteConfirmVisible.value = false;
    window.$message?.success('设备已删除');
    listRef.value?.reload();
  } catch {
    // 请求层已展示后端业务错误，保留二次确认弹窗便于用户核对。
  } finally {
    deleteSubmitting.value = false;
  }
}

/**
 * MNS 队列自检横幅。
 *
 * 为什么放在这个页面：设备数据唯一的来源是对方推送到阿里云 MNS 队列的报文，
 * 「列表空」既可能是对方没推，也可能是我们没收到 —— 只靠页面看不出来。
 * 这里展示队列可见消息数与消费端心跳，点「自检」强制实时查询（否则走后端 10 分钟缓存）。
 */
const queueStatus = ref<DeviceQueueStatus | null>(null);
const queueLoading = ref(false);

async function loadQueueStatus(refresh = false) {
  queueLoading.value = true;
  try {
    queueStatus.value = await fetchDeviceQueueStatus(refresh);
  } catch (error: any) {
    queueStatus.value = {
      enabled: true,
      running: false,
      connected: false,
      ok: false,
      cached: false,
      error: error?.message || '队列自检请求失败'
    };
  } finally {
    queueLoading.value = false;
  }
}

const queueBannerText = computed(() => {
  const status = queueStatus.value;
  if (!status) return '正在检查 MNS 设备队列…';
  if (!status.ok) return `MNS 队列异常：${status.error || '未知原因'}`;
  const received = status.lastMessageTime ? status.lastMessageTime.slice(11, 16) : '无';
  const checkedAt = status.lastCheckTime ? status.lastCheckTime.slice(11, 16) : '—';
  return (
    `MNS 队列 ${status.queueName || '未配置'} · 可见消息 ${status.activeMessages ?? 0}` +
    ` · 最近收到：${received} · 自检 ${checkedAt}`
  );
});

onMounted(() => loadQueueStatus(false));

/** 门店下拉：subjects 镜像里主体类型已归一为小写 type */
const storeOptions = () =>
  store.subjects.filter((s: any) => s.type === 'store').map((s: any) => ({ label: s.name, value: String(s.id) }));

const load = async (p: any) => {
  // 门店下拉与门店筛选依赖 subjects 镜像，首次进入时加载一次
  if (!store.subjects.length) await store.loadRemote('subjects');
  const page = await fetchAdminDevices({
    current: p.page,
    size: p.pageSize,
    keyword: String(p.search?.keyword || '').trim() || undefined,
    storeSubjectId: p.search?.storeSubjectId ? Number(p.search.storeSubjectId) : undefined,
    bindStatus: String(p.search?.bindStatus || '').trim() || undefined,
    onlineStatus: String(p.search?.onlineStatus || '').trim() || undefined,
    enabled:
      p.search?.enabled === undefined || p.search?.enabled === null || p.search?.enabled === ''
        ? undefined
        : Number(p.search.enabled)
  });
  return { data: page?.records || [], total: page?.total || 0 };
};

const columns: DataTableColumns<any> = [
  { title: '设备编码', key: 'deviceSn', width: 185 },
  { title: '设备ID', key: 'deviceName', width: 150, render: (row: any) => row.deviceName || '—' },
  { title: '别名', key: 'alias', width: 120, render: (row: any) => row.alias || '—' },
  {
    title: '归属门店',
    key: 'storeName',
    width: 150,
    render: (row: any) =>
      row.storeName
        ? h(NTag, { size: 'small', bordered: false, type: 'info' }, { default: () => row.storeName })
        : h(NTag, { size: 'small', bordered: false }, { default: () => '未绑定' })
  },
  {
    title: '在线状态',
    key: 'onlineStatus',
    width: 100,
    render: renderTag('onlineStatus', statusMap({ ONLINE: ['在线', 'success'], OFFLINE: ['离线', 'error'] }))
  },
  { title: '最近上报', key: 'lastReportTime', width: 170, render: renderDateTime('lastReportTime') },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: renderTag('enabled', statusMap({ '1': ['启用', 'success'], '0': ['停用', 'default'] }))
  },
  { title: '绑定时间', key: 'bindTime', width: 170, render: renderDateTime('bindTime') },
  { title: '备注', key: 'remark', minWidth: 140, render: (row: any) => row.remark || '—' },
  { title: '创建时间', key: 'createTime', width: 170, render: renderDateTime('createTime') }
];

const searchFields: SearchField[] = [
  { key: 'keyword', label: '设备编码/ID', placeholder: '设备编码、设备ID 或别名' },
  { key: 'storeSubjectId', label: '归属门店', type: 'select', options: storeOptions },
  {
    key: 'bindStatus',
    label: '绑定状态',
    type: 'select',
    options: [
      { label: '已绑定门店', value: 'bound' },
      { label: '未绑定', value: 'unbound' }
    ]
  },
  {
    key: 'onlineStatus',
    label: '在线状态',
    type: 'select',
    options: [
      { label: '在线', value: 'ONLINE' },
      { label: '离线', value: 'OFFLINE' }
    ]
  },
  {
    key: 'enabled',
    label: '启用状态',
    type: 'select',
    options: [
      { label: '启用', value: '1' },
      { label: '停用', value: '0' }
    ]
  }
];

/** 绑定历史弹层：一次加载后整表渲染（来源 device_store_binding，新→旧） */
const bindingHistoryGroups: DetailGroup[] = [
  { title: '基本信息', fields: [{ label: '设备编码', key: 'deviceSn' }] },
  {
    title: '绑定记录',
    fields: [
      {
        label: '记录（新 → 旧）',
        render: (data: any) => {
          const list: any[] = Array.isArray(data?.bindings) ? data.bindings : [];
          if (!list.length) return '暂无绑定记录';
          return h(
            'div',
            { style: 'display:flex;flex-direction:column;gap:8px;font-size:13px;line-height:1.6' },
            list.map(item =>
              h('div', { style: 'display:flex;align-items:baseline;gap:6px;flex-wrap:wrap' }, [
                h(
                  NTag,
                  {
                    size: 'small',
                    bordered: false,
                    type: item.action === 'BIND' ? 'success' : 'warning'
                  },
                  { default: () => item.actionLabel || item.action }
                ),
                h('span', null, item.storeName || '—'),
                h(
                  'span',
                  { style: 'color:#9b9b96' },
                  item.action === 'BIND'
                    ? `绑定于 ${item.bindTime || '—'}`
                    : `解绑于 ${item.unbindTime || item.createTime || '—'}`
                ),
                h('span', { style: 'color:#9b9b96' }, `操作人：${item.operator || '—'}`),
                item.remark ? h('span', { style: 'color:#9b9b96' }, `备注：${item.remark}`) : null
              ])
            )
          );
        }
      }
    ]
  }
];

/** 状态记录弹层：只展示状态变更流水（设备表已保留最新状态） */
const statusLogGroups: DetailGroup[] = [
  { title: '基本信息', fields: [{ label: '设备编码', key: 'deviceSn' }] },
  {
    title: '状态变更记录',
    fields: [
      {
        label: '记录（新 → 旧）',
        render: (data: any) => {
          const list: any[] = Array.isArray(data?.logs) ? data.logs : [];
          if (!list.length) return '暂无状态变更记录';
          return h(
            'div',
            { style: 'display:flex;flex-direction:column;gap:8px;font-size:13px;line-height:1.6' },
            list.map(item => {
              const online = String(item.onlineStatus || '').toUpperCase() === 'ONLINE';
              return h('div', { style: 'display:flex;align-items:baseline;gap:6px;flex-wrap:wrap' }, [
                h(
                  NTag,
                  { size: 'small', bordered: false, type: online ? 'success' : 'error' },
                  {
                    default: () => (online ? '在线' : '离线')
                  }
                ),
                h('span', null, item.baseCreateTime || '—'),
                h('span', { style: 'color:#9b9b96' }, `门店：${item.storeName || '未绑定'}`)
              ]);
            })
          );
        }
      }
    ]
  }
];

const toolbar: RowAction[] = [{ label: '新增设备', type: 'primary', modal: 'add' }];

/** 绑定/换绑门店前的门店选择（换绑时排除当前门店，避免无意义操作） */
const storePickerFor = (exclude?: any) => ({
  title: '选择门店',
  options: () =>
    store.subjects
      .filter((s: any) => s.type === 'store' && Number(s.id) !== Number(exclude))
      .map((s: any) => ({ label: s.name, value: String(s.id) }))
});

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '绑定门店',
    type: 'info',
    picker: storePickerFor(),
    handler: async (row, picked) => {
      if (picked) await bindDeviceStore(row.id, Number(picked));
    },
    visible: row => !row.storeSubjectId
  },
  {
    label: '换绑门店',
    type: 'warning',
    picker: storePickerFor(),
    handler: async (row, picked) => {
      if (picked) await bindDeviceStore(row.id, Number(picked), `由 ${row.storeName || '原门店'} 换绑`);
    },
    visible: row => Boolean(row.storeSubjectId)
  },
  {
    label: '解绑门店',
    type: 'warning',
    reasonPrompt: '确认解绑该设备？解绑后设备上报的数据将不再归属门店（请填写原因）',
    handler: (row, reason) => unbindDeviceStore(row.id, reason),
    visible: row => Boolean(row.storeSubjectId)
  },
  {
    label: '冲泡记录',
    type: 'info',
    handler: (row: any) => openBrewRecords(row)
  },
  {
    label: '删除设备',
    type: 'error',
    handler: (row: any) => openDeleteReason(row),
    visible: row => !row.storeSubjectId
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该设备？（仅影响台账标记，不影响数据接收，请填写备注）',
    handler: (row, _reason) =>
      updateAdminDevice(row.id, {
        deviceSn: row.deviceSn,
        deviceName: row.deviceName,
        alias: row.alias,
        remark: row.remark,
        enabled: 0
      }),
    visible: row => Number(row.enabled) === 1
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该设备？（请填写备注）',
    handler: (row, _reason) =>
      updateAdminDevice(row.id, {
        deviceSn: row.deviceSn,
        deviceName: row.deviceName,
        alias: row.alias,
        remark: row.remark,
        enabled: 1
      }),
    visible: row => Number(row.enabled) !== 1
  },
  {
    label: '绑定历史',
    type: 'info',
    detail: {
      title: '设备绑定历史',
      groups: bindingHistoryGroups,
      load: async (row: any) => ({ deviceSn: row.deviceSn, bindings: await fetchDeviceBindings(row.id) })
    }
  },
  {
    label: '状态记录',
    type: 'info',
    detail: {
      title: '设备状态记录（最近 20 条）',
      groups: statusLogGroups,
      load: async (row: any) => {
        const res: any = await store.queryRemote('deviceStatusLogs', { eq_deviceSn: row.deviceSn }, 1, 20);
        return { deviceSn: row.deviceSn, logs: res?.data || [] };
      }
    }
  }
];

const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  {
    key: 'deviceSn',
    label: '设备编码',
    placeholder: '报文 deviceSN，如 ZPDS2512090086G',
    rules: [requiredRule]
  },
  {
    key: 'deviceName',
    label: '设备ID',
    placeholder: '报文 deviceName，如 869978072404913',
    rules: [requiredRule]
  },
  { key: 'alias', label: '别名', placeholder: '便于门店识别，如 三楼吧台机' },
  {
    key: 'enabled',
    label: '启用状态',
    type: 'select',
    options: [
      { label: '启用', value: '1' },
      { label: '停用', value: '0' }
    ]
  },
  { key: 'remark', label: '备注', type: 'textarea' }
];

const config: AdminListConfig = {
  title: '设备管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '设备',
    fields: formFields,
    // 在线状态/最近上报由队列维护，表单不展示，回填时也只带这几项
    toFormData: (row: any) => ({
      deviceSn: row.deviceSn ?? '',
      deviceName: row.deviceName ?? '',
      alias: row.alias ?? '',
      enabled: String(row.enabled ?? 1),
      remark: row.remark ?? ''
    }),
    onSubmit: async (data, editing) => {
      const deviceSn = String(data.deviceSn ?? '').trim();
      if (!deviceSn) throw new Error('设备编码必填');
      const deviceName = String(data.deviceName ?? '').trim();
      if (!deviceName) throw new Error('设备ID必填');
      const payload = {
        deviceSn,
        deviceName,
        alias: String(data.alias ?? '').trim(),
        remark: String(data.remark ?? '').trim(),
        enabled: Number(data.enabled ?? 1)
      };
      if (editing) await updateAdminDevice(editing.id, payload);
      else await createAdminDevice(payload);
    }
  },
  importConfig: {
    title: '设备',
    sheetName: '设备导入',
    maxRows: 5000,
    maxFileSize: 10 * 1024 * 1024,
    fields: [
      { key: 'deviceSn', label: '设备编码', required: true, width: 24 },
      { key: 'deviceName', label: '设备ID', required: true, width: 24 },
      { key: 'alias', label: '别名', width: 20 },
      { key: 'enabled', label: '启用状态', options: ['启用', '停用'], width: 14 },
      { key: 'remark', label: '备注', width: 40 }
    ],
    template: () => [['设备编码', '设备ID', '别名', '启用状态', '备注']],
    instructions: [
      ['字段', '填写说明'],
      ['设备编码', '必填；仅允许字母、数字、点、下划线、短横线，最多 64 字符'],
      ['设备ID', '必填；仅允许字母、数字、点、下划线、短横线，最多 64 字符'],
      ['别名', '选填；最多 64 字符'],
      ['启用状态', '选填；填写“启用”或“停用”，留空默认启用'],
      ['备注', '选填；最多 255 字符'],
      ['限制', '单个文件不超过 10MB，最多 5000 行；请勿使用公式单元格']
    ],
    parse: parseDeviceImportRows,
    commit: async rows => {
      assertNormalizedRowsSize(rows);
      const result = await importAdminDevices(rows as DeviceUpsertPayload[]);
      return {
        added: result.imported,
        skipped: result.total - result.imported,
        errors: result.errors
      };
    }
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config">
      <template #banner>
        <div class="queue-banner" :class="{ 'is-error': queueStatus && !queueStatus.ok }">
          <span class="queue-banner__text">{{ queueBannerText }}</span>
          <NButton size="tiny" :loading="queueLoading" @click="loadQueueStatus(true)">自检</NButton>
        </div>
      </template>
    </AdminListPage>

    <DeviceBrewRecordDialog v-model:show="brewVisible" :device="brewDevice" />

    <NModal v-model:show="deleteReasonVisible" preset="dialog" title="删除设备" class="w-520px" :mask-closable="false">
      <div class="delete-dialog__device">
        设备：{{ deleteTarget?.deviceSn || '—' }} · {{ deleteTarget?.deviceName || '—' }}
      </div>
      <NInput
        v-model:value="deleteReason"
        type="textarea"
        :rows="4"
        :maxlength="255"
        show-count
        placeholder="请填写删除备注（必填）"
      />
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="deleteReasonVisible = false">取消</NButton>
        <NButton type="error" @click="openDeleteConfirm">下一步</NButton>
      </div>
    </NModal>

    <NModal v-model:show="deleteConfirmVisible" preset="dialog" title="二次确认" class="w-520px" :mask-closable="false">
      <div class="delete-dialog__notice">
        确认逻辑删除设备「{{ deleteTarget?.deviceSn }}」？删除后设备上报只保留事实记录，不再自动建档。
      </div>
      <div class="delete-dialog__reason">删除备注：{{ deleteReason }}</div>
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="deleteConfirmVisible = false">取消</NButton>
        <NButton type="error" :loading="deleteSubmitting" @click="confirmDeleteDevice">确认删除</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.queue-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 12px;
  color: #666762;
  font-size: 12px;
  line-height: 1.6;
  background: #f7f8f5;
  border-left: 3px solid #53882c;
  border-radius: 4px;
}

.queue-banner.is-error {
  color: #c0392b;
  background: #fdf3f2;
  border-left-color: #c0392b;
}

.queue-banner__text {
  min-width: 0;
  word-break: break-all;
}

.delete-dialog__device,
.delete-dialog__notice,
.delete-dialog__reason {
  margin-bottom: 12px;
  color: #666762;
  font-size: 13px;
  line-height: 1.7;
}

.delete-dialog__notice {
  color: #c0392b;
}
</style>
