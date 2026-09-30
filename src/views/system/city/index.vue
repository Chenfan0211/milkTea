<script setup lang="ts">
defineOptions({
  name: 'SystemCity'
});

import { ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 活动城市管理（运营白名单）。
 *
 * region 是行政区划基础库（全国 337 个市，只读候选）；
 * activity_city 是运营开城的白名单，只有 enabled 的活动城市才会在小程序端下发。
 * 本页维护的是 activity_city：
 *   - 列表 = activity_city（region_id/city_code/city_name/province_id/status/sort/remark）；
 *   - 新增：从 region level=2 全量城市中「选一个」作为活动城市，选中后回填冗余字段；
 *   - 启用/停用/删除只作用于 activity_city，不改动 region 基础数据。
 */

/** 候选城市（region level=2 全量），用于新增时下拉选择 */
const cityOptions = ref<{ label: string; value: string }[]>([]);

/** 懒加载候选城市（region 基础库，只读） */
async function ensureCityOptions() {
  if (cityOptions.value.length) return;
  try {
    const res = await store.queryRemote('cities', { eq_level: '2' }, 1, 1000);
    cityOptions.value = (res.data || []).map((c: any) => ({
      label: `${c.name}（${c.code}）`,
      value: String(c.id)
    }));
  } catch {
    cityOptions.value = [];
  }
}

/** 按 province_id 反查省份名称（region level=1） */
function provinceName(provinceId: any): string {
  const p = store.provinces.find((x: any) => String(x.id) === String(provinceId));
  return p ? p.name : provinceId ? String(provinceId) : '—';
}

// 页面挂载即预加载候选城市，供新增下拉使用（异步完成后 ref 更新、下拉自动刷新）
ensureCityOptions();

const columns: DataTableColumns<any> = [
  { title: '省份', key: 'provinceId', width: 120, render: (row: any) => provinceName(row.provinceId) },
  { title: '城市', key: 'cityName', width: 140 },
  { title: '城市编码', key: 'cityCode', width: 140 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
  { title: '排序', key: 'sort', width: 80, align: 'right' }
];

const searchFields: SearchField[] = [{ key: 'cityName', label: '城市', placeholder: '城市名称' }];

const formFields: FormField[] = [
  {
    key: 'regionId',
    label: '活动城市',
    type: 'select',
    options: () => cityOptions.value,
    placeholder: '请从城市库中选择',
    rules: [{ required: true, message: '请选择活动城市', trigger: ['change', 'blur'] }]
  },
  { key: 'sort', label: '排序', type: 'number' },
  { key: 'remark', label: '备注' }
];

const toolbar: RowAction[] = [{ label: '新增活动城市', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该活动城市？（停用后小程序不再下发该城市）',
    handler: async (row, _reason) =>
      await store.update('activityCities', row.id, { status: 'disabled' }, '活动城市', 'cityName'),
    visible: row => row.status !== 'disabled'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该活动城市？（启用后小程序下发该城市）',
    handler: async (row, _reason) =>
      await store.update('activityCities', row.id, { status: 'enabled' }, '活动城市', 'cityName'),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该活动城市？（停用状态才能删除，请填写备注）',
    handler: async (row, reason) => await store.remove('activityCities', row.id, '活动城市', 'cityName', reason),
    visible: row => row.status === 'disabled'
  }
];

const config: AdminListConfig = {
  title: '活动城市管理',
  remoteKey: 'activityCities',
  remoteDeps: ['provinces'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    // 活动城市列表直接查 activity_city，搜索走 cityName 模糊匹配。
    return store.queryRemote('activityCities', { ...search }, page, pageSize);
  },
  form: {
    title: '活动城市',
    fields: formFields,
    /**
     * 编辑回填：活动城市的 city_name/city_code/province_id 是冗余字段，编辑时不可改
     * （要换城市应删除后新增），只回填 regionId/sort/remark。
     */
    toFormData: (row: any) => ({
      regionId: row.regionId == null ? null : String(row.regionId),
      sort: row.sort == null ? 0 : Number(row.sort),
      remark: row.remark || ''
    }),
    onSubmit: async (data, editing) => {
      // 选中 region 城市后，从候选库里回填冗余字段（city_code/city_name/province_id）。
      await ensureCityOptions();
      const regionId = Number(data.regionId);
      const payload: Record<string, any> = {
        regionId,
        sort: Number(data.sort) || 0,
        remark: data.remark || ''
      };
      if (!editing) {
        // 新增：必须回填冗余字段，否则列表「城市/编码」列为空。
        const raw = (await store.queryRemote('cities', { eq_level: '2' }, 1, 1000)).data.find(
          (c: any) => String(c.id) === String(regionId)
        );
        payload.cityCode = raw?.code ?? '';
        payload.cityName = raw?.name ?? '';
        payload.provinceId = raw?.parentId ?? null;
        payload.status = 'enabled';
        await store.add('activityCities', payload, '活动城市', 'cityName');
      } else {
        await store.update('activityCities', editing.id, payload, '活动城市', 'cityName');
      }
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
