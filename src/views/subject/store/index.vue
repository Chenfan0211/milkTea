<script setup lang="ts">
defineOptions({
  name: 'subject_store'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { geocodeAddress } from '@/utils/tencent-map';

const store = useAdminStore();

const load = async (p: any) => {
  const keyword = String(p.search?.name || '').trim();
  const status = String(p.search?.status || '').trim();
  const result = await store.fetchAdminStores({
    current: p.page,
    size: p.pageSize,
    search: keyword || undefined,
    status: status || undefined
  });
  await store.loadRemote('subjects');
  return result;
};

const columns: DataTableColumns<any> = [
  { title: '名称', key: 'name', minWidth: 150 },
  { title: '绑定用户', key: 'boundUserName', width: 120, render: (row: any) => row.boundUserName || '未绑定' },
  {
    title: '可提现余额(元)',
    key: 'balance',
    width: 140,
    align: 'right',
    render: (row: any) => {
      const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id);
      return (acc ? (acc.availableBalance ?? 0) : 0).toFixed(2);
    }
  },
  { title: '门店类型', key: 'storeType', width: 110 },
  { title: '城市', key: 'city', width: 100 },
  {
    title: '营业状态',
    key: 'businessStatus',
    width: 110,
    render: renderTag('businessStatus', statusMap({ open: ['营业中', 'success'], closed: ['停业', 'default'] }))
  },
  { title: '负责人', key: 'manager', width: 100 },
  { title: '地址', key: 'location', minWidth: 180 },
  { title: '电话', key: 'phone', width: 130 },
  { title: '关联投资人', key: 'investorName', width: 120 },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 175 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '营业状态',
    type: 'select',
    options: [
      { label: '营业中', value: 'open' },
      { label: '停业', value: 'closed' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增门店', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '绑定用户',
    type: 'info',
    picker: {
      title: '选择用户',
      options: () =>
        store.users
          .filter((u: any) => !u.boundSubjectId && !u.deleted)
          .map((u: any) => ({ label: u.nickName, value: String(u.id) }))
    },
    handler: async (row, picked) => {
      if (picked) await store.bindSubjectUser(row.id, Number(picked));
    },
    visible: row => !row.boundUserId
  },
  {
    label: '解绑用户',
    type: 'warning',
    reasonPrompt: '确认解绑该经营者绑定的用户？（请填写备注）',
    handler: (row, reason) => store.unbindSubjectUser(row.id, reason),
    visible: row => Boolean(row.boundUserId) && row.businessStatus === 'closed'
  },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停业该门店？（请填写备注）',
    handler: async (row, _reason) =>
      await store.updateAdminStore(row.id, {
        name: row.name,
        city: row.city,
        manager: row.manager,
        location: row.location,
        phone: row.phone,
        storeType: row.storeType,
        businessStatus: 'closed'
      }),
    visible: row => row.businessStatus === 'open'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认营业该门店？（请填写备注）',
    handler: async (row, _reason) =>
      await store.updateAdminStore(row.id, {
        name: row.name,
        city: row.city,
        manager: row.manager,
        location: row.location,
        phone: row.phone,
        storeType: row.storeType,
        businessStatus: 'open'
      }),
    visible: row => row.businessStatus === 'closed'
  },
  {
    label: '绑定投资人',
    type: 'info',
    picker: {
      title: '选择投资人',
      options: () =>
        store.subjects.filter(s => s.type === 'investor').map(s => ({ label: s.name, value: String(s.id) }))
    },
    handler: async (row, picked) => {
      if (picked) await store.bindStoreInvestor(row.id, Number(picked));
    },
    visible: row => !row.investorSubjectId
  },
  {
    label: '解绑投资人',
    type: 'warning',
    reasonPrompt: '确认解绑该门店的投资人？（请填写备注）',
    handler: async row => await store.unbindStoreInvestor(row.id),
    visible: row => Boolean(row.investorSubjectId)
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该门店？删除后列表不再展示（逻辑删除），请填写备注',
    handler: async (row, reason) => await store.remove('subjects', row.id, '主体管理', 'name', reason),
    visible: row => row.businessStatus === 'closed'
  }
];
const requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] } as const;

const formFields: FormField[] = [
  { key: 'name', label: '名称', rules: [requiredRule] },
  {
    key: 'storeType',
    label: '门店类型',
    type: 'select',
    options: () =>
      store.storeTypes.filter((t: any) => t.enabled !== false).map((t: any) => ({ label: t.name, value: t.name })),
    rules: [requiredRule]
  },
  {
    key: 'city',
    label: '城市',
    type: 'select',
    options: () => store.cities.map((c: any) => ({ label: c.name, value: c.name })),
    rules: [requiredRule]
  },
  { key: 'manager', label: '负责人', rules: [requiredRule] },
  { key: 'address', label: '详细地址', type: 'textarea', rules: [requiredRule] },
  { key: 'phone', label: '联系人电话', rules: [requiredRule] },
  {
    key: 'geocode',
    label: '坐标',
    type: 'geocode',
    geocodeSourceKey: 'address',
    geocodeLatKey: 'latitude',
    geocodeLngKey: 'longitude'
  }
];

const config: AdminListConfig = {
  remoteKey: 'subjects',
  // 「可提现余额」列按 subjectId 从 subjectAccounts 取数，需预加载该资源，否则恒显示 0.00
  // subjectAccounts：供「可提现余额」列取数；storeTypes：供「门店类型」下拉选项
  remoteDeps: ['subjectAccounts', 'storeTypes'],
  title: '门店管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '门店',
    fields: formFields,
    toFormData: (row: any) => ({ ...row, address: row.location }),
    onSubmit: async (data, editing) => {
      for (const key of ['name', 'storeType', 'city', 'manager', 'address', 'phone']) {
        if (!String(data[key] ?? '').trim())
          throw new Error(
            '请填写必填项：' +
              (
                {
                  name: '名称',
                  storeType: '门店类型',
                  city: '城市',
                  manager: '负责人',
                  address: '详细地址',
                  phone: '联系人电话'
                } as Record<string, string>
              )[key]
          );
      }
      if (data.latitude == null || data.longitude == null || data.latitude === '' || data.longitude === '') {
        throw new Error('请先填写详细地址并点击「按地址解析经纬度」');
      }
      const payload = {
        name: data.name,
        storeType: data.storeType,
        city: data.city,
        manager: data.manager,
        location: data.address,
        phone: data.phone,
        latitude: data.latitude,
        longitude: data.longitude
      };
      if (editing) await store.updateAdminStore(editing.id, payload);
      else await store.addAdminStore(payload);
    }
  },
  importConfig: {
    title: '门店',
    fields: [
      { key: 'name', label: '名称', required: true },
      { key: 'storeType', label: '门店类型', required: true },
      { key: 'city', label: '城市', required: true },
      { key: 'manager', label: '负责人', required: true },
      { key: 'address', label: '地址', required: true },
      { key: 'phone', label: '电话', required: true }
    ],
    template: () => '名称,门店类型,城市,负责人,地址,电话\n新门店,奶茶/饮品,长沙,店长5,某详细地址,0731-0000\n',
    parse: rows => {
      const errors: string[] = [];
      const ok: Record<string, any>[] = [];
      rows.forEach((r, i) => {
        const missing = ['name', 'storeType', 'city', 'manager', 'address', 'phone'].filter(
          key => !String(r[key] || '').trim()
        );
        if (missing.length) {
          errors.push('第 ' + (i + 2) + ' 行：' + missing.join('/') + ' 必填');
          return;
        }
        ok.push({
          name: r.name,
          storeType: r.storeType,
          city: r.city,
          manager: r.manager,
          address: r.address,
          phone: r.phone
        });
      });
      return { ok, errors };
    },
    commit: async rows => {
      let added = 0,
        skipped = 0;
      for (const r of rows) {
        const exists = store.subjects.some(s => s.type === 'store' && s.name === r.name);
        if (exists) {
          skipped++;
          continue;
        }
        let latitude: number | null = null;
        let longitude: number | null = null;
        try {
          const geo = await geocodeAddress(r.address);
          if (geo) {
            latitude = geo.latitude;
            longitude = geo.longitude;
          }
        } catch {
          // 地址解析失败不阻断导入，仅缺少坐标，后续可在编辑页补解析
        }
        await store.addAdminStore({
          name: r.name,
          storeType: r.storeType,
          city: r.city,
          manager: r.manager,
          location: r.address,
          phone: r.phone,
          latitude,
          longitude
        });
        added++;
      }
      return { added, skipped };
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
