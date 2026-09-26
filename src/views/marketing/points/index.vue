<script setup lang="ts">
defineOptions({
  name: 'marketing_points'
});

import { computed, h } from 'vue';
import { useRouter } from 'vue-router';
import { NImage, NTag } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField, SelectOption } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const router = useRouter();

function isEnabled(value: any): boolean {
  return value === true || Number(value) === 1 || value === 'enabled';
}

const categories = computed(() =>
  store.pointsCategories
    .filter((category: any) => !category.deleted)
    .slice()
    .sort((a: any, b: any) => Number(a.sort || 0) - Number(b.sort || 0) || Number(a.id || 0) - Number(b.id || 0))
);

const categoryOptions = computed<SelectOption[]>(() =>
  categories.value.map((category: any) => ({
    label: `${category.name || category.code}${isEnabled(category.enabled) ? '' : '（停用）'}`,
    value: String(category.code)
  }))
);

const enabledCoupons = computed(() =>
  store.coupons.filter((coupon: any) => String(coupon.status || '').toLowerCase() === 'enabled')
);

const couponOptions = computed<SelectOption[]>(() =>
  enabledCoupons.value.map((coupon: any) => ({
    label: coupon.name || coupon.title || `优惠券 #${coupon.id}`,
    value: String(coupon.id)
  }))
);

function categoryByCode(code: any): any {
  return store.pointsCategories.find((category: any) => !category.deleted && String(category.code || '') === String(code || ''));
}

function isCouponCategory(code: any): boolean {
  if (!code) return false;
  if (String(code) === 'coupon') return true;
  return Number(categoryByCode(code)?.systemLocked) === 1;
}

function isCouponForm(form: Record<string, any>): boolean {
  return isCouponCategory(form.category);
}

function couponById(id: any): any {
  return store.coupons.find((coupon: any) => String(coupon.id) === String(id));
}

function couponName(coupon: any): string {
  return String(coupon?.name || coupon?.title || '').trim() || `优惠券 #${coupon?.id ?? ''}`;
}

function formCouponOptions(form: Record<string, any>): SelectOption[] {
  const options = couponOptions.value.map(item => ({ ...item }));
  const currentId = Number(form.couponId);
  if (currentId && !options.some(item => Number(item.value) === currentId)) {
    const coupon = couponById(currentId);
    options.unshift({
      label: coupon ? `${couponName(coupon)}（已停用或失效，请重新选择）` : `优惠券 #${currentId}（已删除）`,
      value: String(currentId)
    });
  }
  return options;
}

function categoryLabel(code: any): string {
  const category = categoryByCode(code);
  if (!category) return String(code || '') || '—';
  return `${category.name || category.code}${isEnabled(category.enabled) ? '' : '（停用）'}`;
}

function couponBinding(row: any): { text: string; invalid: boolean } {
  if (row.couponId == null) return { text: '—', invalid: false };
  const coupon = couponById(row.couponId);
  if (!coupon) return { text: `优惠券 #${row.couponId}（已失效）`, invalid: true };
  const active = String(coupon.status || '').toLowerCase() === 'enabled';
  return {
    text: active ? couponName(coupon) : `${couponName(coupon)}（已停用，绑定失效）`,
    invalid: !active
  };
}

const columns: DataTableColumns<any> = [
  {
    title: '图片',
    key: 'image',
    width: 80,
    render: (row: any) =>
      row.image
        ? h(NImage, { src: row.image, width: 56, height: 56, objectFit: 'cover', style: 'border-radius:6px' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '商品名称', key: 'name', minWidth: 180 },
  { title: '分类', key: 'category', width: 150, render: (row: any) => categoryLabel(row.category) },
  { title: '所需积分', key: 'points', width: 100, align: 'right' },
  { title: '库存', key: 'stock', width: 90, align: 'right' },
  {
    title: '每人限兑',
    key: 'purchaseLimit',
    width: 100,
    align: 'right',
    render: (row: any) => (Number(row.purchaseLimit) > 0 ? String(row.purchaseLimit) : '不限')
  },
  {
    title: '绑定优惠券',
    key: 'couponId',
    minWidth: 180,
    render: (row: any) => {
      const binding = couponBinding(row);
      return binding.invalid
        ? h(NTag, { type: 'warning', bordered: false }, { default: () => binding.text })
        : binding.text;
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row: any) =>
      h(
        NTag,
        { type: isEnabled(row.status) ? 'success' : 'default', bordered: false },
        { default: () => (isEnabled(row.status) ? '启用' : '停用') }
      )
  },
  { title: '限购说明', key: 'limitText', minWidth: 150, render: (row: any) => row.limitText || '—' }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '商品', placeholder: '商品名称' },
  { key: 'eq_category', label: '分类', type: 'select', options: () => categoryOptions.value },
  {
    key: 'eq_status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'enabled' },
      { label: '停用', value: 'disabled' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'image', label: '商品图片', type: 'image' },
  { key: 'name', label: '商品名称' },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: () => categoryOptions.value,
    placeholder: '请选择积分商城分类'
  },
  { key: 'points', label: '所需积分', type: 'number' },
  { key: 'stock', label: '库存', type: 'number' },
  {
    key: 'purchaseLimit',
    label: '每人限兑次数',
    type: 'number',
    placeholder: '0 表示不限次数'
  },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'enabled' },
      { label: '停用', value: 'disabled' }
    ]
  },
  {
    key: 'couponId',
    label: '绑定优惠券',
    type: 'select',
    options: formCouponOptions,
    placeholder: '请选择启用中的优惠券',
    visible: isCouponForm
  },
  { key: 'badge', label: '角标' },
  { key: 'limitText', label: '限购说明' },
  { key: 'description', label: '商品描述', type: 'textarea' }
];

const toolbar: RowAction[] = [
  { label: '分类管理', handler: () => router.push('/marketing/points-category') },
  { label: '新增兑换商品', type: 'primary', modal: 'add' }
];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该商品？（请填写备注）',
    handler: async (row, reason) => await store.remove('pointsProducts', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '积分商城',
  remoteKey: 'pointsProducts',
  remoteDeps: ['pointsCategories', 'coupons'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('pointsProducts', search, page, pageSize),
  form: {
    title: '兑换商品',
    fields: formFields,
    toFormData: (row: any) => ({
      image: row.image,
      name: row.name,
      category: row.category,
      points: row.points == null ? 0 : Number(row.points),
      stock: row.stock == null ? 0 : Number(row.stock),
      purchaseLimit: row.purchaseLimit == null ? 0 : Number(row.purchaseLimit),
      status: String(row.status || 'enabled'),
      couponId: row.couponId == null ? null : String(row.couponId),
      badge: row.badge,
      limitText: row.limitText,
      description: row.description
    }),
    onSubmit: async (data, editing) => {
      const category = String(data.category || '').trim();
      if (!category) throw new Error('请选择积分商城分类');
      if (!categoryByCode(category)) throw new Error('所选积分商城分类已失效，请重新选择');

      const points = Number(data.points);
      const stock = Number(data.stock);
      const purchaseLimit = Number(data.purchaseLimit);
      if (!Number.isFinite(points) || points <= 0) throw new Error('所需积分必须大于 0');
      if (!Number.isInteger(stock) || stock < 0) throw new Error('库存必须是不小于 0 的整数');
      if (!Number.isInteger(purchaseLimit) || purchaseLimit < 0) throw new Error('每人限兑次数必须是不小于 0 的整数');

      const payload: Record<string, any> = {
        ...data,
        category,
        points,
        stock,
        purchaseLimit,
        status: data.status === 'disabled' ? 'disabled' : 'enabled',
        couponId: null
      };

      if (isCouponCategory(category)) {
        const couponId = Number(data.couponId);
        const coupon = enabledCoupons.value.find((item: any) => Number(item.id) === couponId);
        if (!coupon) throw new Error('优惠券分类商品必须绑定一张启用中的优惠券');
        payload.couponId = couponId;
      }

      if (editing) await store.update('pointsProducts', editing.id, payload, '营销中心', 'name');
      else await store.add('pointsProducts', payload, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
